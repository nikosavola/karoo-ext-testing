package fi.nikosavola.karooext.testing

import android.os.Bundle
import android.os.RemoteException
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooSystem
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.KarooInfo
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.UserProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

const val KAROO_SYSTEM_PACKAGE = "io.hammerhead.appstore"
const val KAROO_SYSTEM_SERVICE = "io.hammerhead.appstore.service.AppStoreService"

private const val LIB_VERSION = "1.1.9"
private const val BRIDGE_MAX_BODY = 100_000
private const val DEFAULT_HTTP_THREADS = 1
private const val FULL_GRID = 60
private const val SERIAL = "fake"

/**
 * The Karoo system end of the karoo-ext binder: what `KarooSystemService` in an extension talks to.
 * Location, navigation, ride state, the user profile and the active page are sticky, like on the
 * device, so a consumer that registers late still gets the current value; [reset] clears them.
 *
 * HTTP is answered on [httpThreads] (one by default, so responses land in request order).
 *
 * Suppressing TooManyFunctions: it implements the whole IKarooSystem AIDL surface plus a setter per
 * sticky event.
 */
@Suppress("TooManyFunctions")
class FakeKarooSystem(
  @Volatile var responder: HttpResponder = HttpResponses.notFound(),
  /** The companion bridge rejects bodies over ~100 KB; real Karoos fail such requests. */
  @Volatile var maxBodyBytes: Int = BRIDGE_MAX_BODY,
  httpThreads: Int = DEFAULT_HTTP_THREADS,
  @Volatile var hardwareType: HardwareType = HardwareType.KAROO,
) : IKarooSystem.Stub() {
  private class Consumer(val params: KarooEventParams, val handler: IHandler)

  private val consumers = ConcurrentHashMap<String, Consumer>()
  private val http = Executors.newFixedThreadPool(httpThreads)

  val effects = CopyOnWriteArrayList<KarooEffect>()
  val httpRequests = CopyOnWriteArrayList<OnHttpResponse.MakeHttpRequest>()

  @Volatile
  var location: OnLocationChanged? = null
    private set

  @Volatile
  var navigation: OnNavigationState = OnNavigationState(OnNavigationState.NavigationState.Idle)
    private set

  @Volatile
  var rideState: RideState = RideState.Idle
    private set

  @Volatile
  var userProfile: UserProfile = metricProfile()
    private set

  @Volatile
  var activePage: ActiveRidePage? = null
    private set

  val consumerCount: Int
    get() = consumers.size

  override fun libVersion(): String = LIB_VERSION

  override fun info(): Bundle =
    KarooInfo(SERIAL, hardwareType).bundleWithSerializable(KAROO_SYSTEM_PACKAGE)

  override fun dispatchEffect(bundle: Bundle) {
    bundle.serializableFromBundle<KarooEffect>()?.let(effects::add)
  }

  override fun addEventConsumer(id: String, params: Bundle, handler: IHandler) {
    val parsed = params.serializableFromBundle<KarooEventParams>()
    if (parsed == null) {
      handler.onError("Unreadable params")
      return
    }
    if (parsed is OnHttpResponse.MakeHttpRequest) {
      http.execute { serve(parsed, handler) }
      return
    }
    consumers[id] = Consumer(parsed, handler)
    // The Karoo sends the current state to a new consumer straight away.
    current(parsed)?.let { send(handler, it) }
  }

  override fun removeEventConsumer(id: String) {
    consumers.remove(id)
  }

  fun setLocation(lat: Double, lng: Double, orientation: Double? = null) {
    val event = OnLocationChanged(lat, lng, orientation)
    location = event
    publish(OnLocationChanged.Params, event)
  }

  fun setNavigation(state: OnNavigationState.NavigationState) {
    val event = OnNavigationState(state)
    navigation = event
    publish(OnNavigationState.Params, event)
  }

  /**
   * Navigates [points] (lat to lng) as the active route, the way the ride app follows a planned
   * one; distance along the polyline unless [routeDistanceMeters] says otherwise.
   */
  fun setRoute(
    points: List<Pair<Double, Double>>,
    name: String = "Test route",
    routeDistanceMeters: Double = polylineLengthMeters(points),
  ) {
    setNavigation(
      OnNavigationState.NavigationState.NavigatingRoute(
        routePolyline = encodePolyline(points),
        routeDistance = routeDistanceMeters,
        routeElevationPolyline = "",
        rejoinPolyline = "",
        rejoinDistance = null,
        name = name,
        reversed = false,
        breadcrumb = false,
        pois = emptyList(),
        climbs = emptyList(),
      )
    )
  }

  fun setRideState(state: RideState) {
    rideState = state
    publish(RideState.Params, state)
  }

  fun setUserProfile(profile: UserProfile) {
    userProfile = profile
    publish(UserProfile.Params, profile)
  }

  /** Shows a ride page holding [dataTypeIds]; set [mapPage] for the map page. */
  fun showPage(dataTypeIds: List<String>, mapPage: Boolean = false) {
    val page =
      ActiveRidePage(
        RideProfile.Page(
          mapPage = mapPage,
          elements = dataTypeIds.map { RideProfile.Page.Element(it, FULL_GRID to FULL_GRID) },
        )
      )
    activePage = page
    publish(ActiveRidePage.Params, page)
  }

  inline fun <reified T : KarooEffect> effectsOf(): List<T> = effects.filterIsInstance<T>()

  /** Forgets every consumer and sticky value and drops the recorded effects and requests. */
  fun reset() {
    consumers.clear()
    location = null
    navigation = OnNavigationState(OnNavigationState.NavigationState.Idle)
    rideState = RideState.Idle
    userProfile = metricProfile()
    activePage = null
    effects.clear()
    httpRequests.clear()
    responder = HttpResponses.notFound()
    maxBodyBytes = BRIDGE_MAX_BODY
  }

  private fun current(params: KarooEventParams): KarooEvent? =
    when (params) {
      OnLocationChanged.Params -> location
      OnNavigationState.Params -> navigation
      RideState.Params -> rideState
      UserProfile.Params -> userProfile
      ActiveRidePage.Params -> activePage
      else -> null
    }

  private fun publish(params: KarooEventParams, event: KarooEvent) {
    consumers.values.filter { it.params == params }.forEach { send(it.handler, event) }
  }

  private fun serve(request: OnHttpResponse.MakeHttpRequest, handler: IHandler) {
    httpRequests += request
    val response = responder.respond(request)
    val body = response.body
    val complete =
      if (body != null && body.size > maxBodyBytes) {
        HttpResponseState.Complete(0, emptyMap(), null, "Response too large: ${body.size}")
      } else {
        response
      }
    send(handler, OnHttpResponse(HttpResponseState.InProgress))
    send(handler, OnHttpResponse(complete))
  }

  private fun send(handler: IHandler, event: KarooEvent) {
    try {
      handler.onNext(event.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))
    } catch (_: RemoteException) {
      // The consumer's process went away; the real Karoo drops such consumers too.
      consumers.entries.removeIf { it.value.handler === handler }
    }
  }

  companion object {
    fun metricProfile(): UserProfile = profile(UserProfile.PreferredUnit.UnitType.METRIC)

    fun imperialProfile(): UserProfile = profile(UserProfile.PreferredUnit.UnitType.IMPERIAL)

    private fun profile(unit: UserProfile.PreferredUnit.UnitType) =
      UserProfile(
        weight = 75f,
        preferredUnit = UserProfile.PreferredUnit(unit, unit, unit, unit),
        maxHr = 190,
        restingHr = 50,
        heartRateZones = emptyList(),
        ftp = 250,
        powerZones = emptyList(),
      )
  }
}
