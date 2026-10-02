package fi.nikosavola.karooext.testing

import android.os.Bundle
import android.os.RemoteException
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooSystem
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.KarooInfo
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

const val KAROO_SYSTEM_PACKAGE = "io.hammerhead.appstore"
const val KAROO_SYSTEM_SERVICE = "io.hammerhead.appstore.service.AppStoreService"

private const val LIB_VERSION = "1.1.9"
private const val BRIDGE_MAX_BODY = 100_000
private const val DEFAULT_HTTP_THREADS = 1
private const val FULL_GRID = 60
private const val SERIAL = "fake"

/**
 * The Karoo system end of the karoo-ext binder: what `KarooSystemService` in an extension talks to.
 *
 * Sticky delivery here is a fake policy, not a device guarantee. The SDK only documents that a new
 * consumer of [UserProfile] and [RideState] receives the current value; location, navigation, the
 * active page and stream state are replayed too because extension tests rely on it. [reset] clears
 * every stored value.
 *
 * HTTP is answered on [httpThreads] (one by default, so responses land in request order). The
 * responder and body limit are captured when a request is registered, so changing either later does
 * not affect a request already in flight. [reset] and [removeEventConsumer] invalidate in-flight
 * requests and [close] is terminal. Registration, cancellation and delivery share one lock, so a
 * cancel that returns before a delivery starts prevents it, but a delivery already inside a handler
 * completes.
 *
 * Suppressing TooManyFunctions: it implements the whole IKarooSystem AIDL surface plus a setter per
 * sticky event.
 */
@Suppress("TooManyFunctions")
class FakeKarooSystem(
  @Volatile var responder: HttpResponder = HttpResponses.notFound(),
  maxBodyBytes: Int = BRIDGE_MAX_BODY,
  httpThreads: Int = DEFAULT_HTTP_THREADS,
  @Volatile var hardwareType: HardwareType = HardwareType.KAROO,
) : IKarooSystem.Stub(), Closeable {
  private class Consumer(val params: KarooEventParams, val handler: IHandler)

  /**
   * One registered HTTP request; [responder] and [maxBodyBytes] are snapshotted at registration.
   */
  private class PendingHttp(
    val request: OnHttpResponse.MakeHttpRequest,
    val handler: IHandler,
    val responder: HttpResponder,
    val maxBodyBytes: Int,
  )

  private val lifecycleLock = ReentrantLock()

  private val consumers = ConcurrentHashMap<String, Consumer>()
  private val pendingHttp = ConcurrentHashMap<String, PendingHttp>()
  private val streamStates = ConcurrentHashMap<String, StreamState>()

  /** The companion bridge rejects bodies over ~100 KB; negative limits make no sense here. */
  @Volatile
  var maxBodyBytes: Int = maxBodyBytes
    set(value) {
      require(value >= 0) { "maxBodyBytes must not be negative" }
      field = value
    }

  init {
    // The initializer writes the backing field directly, bypassing the setter. Checked before the
    // executor below is created, so a bad limit cannot leak a thread pool from a failed
    // constructor.
    require(this.maxBodyBytes >= 0) { "maxBodyBytes must not be negative" }
  }

  private val http = Executors.newFixedThreadPool(httpThreads)

  @Volatile private var closed = false

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

  /** Registered event params, so a test can assert a consumer was cancelled without its handler. */
  val consumerParams: List<KarooEventParams>
    get() = consumers.values.map { it.params }

  /** HTTP requests registered but not yet answered or cancelled. */
  val pendingHttpCount: Int
    get() = pendingHttp.size

  /** Latest stream state per data type id, a read-only snapshot. */
  val streams: Map<String, StreamState>
    get() = streamStates.toMap()

  override fun libVersion(): String = LIB_VERSION

  override fun info(): Bundle =
    KarooInfo(SERIAL, hardwareType).bundleWithSerializable(KAROO_SYSTEM_PACKAGE)

  override fun dispatchEffect(bundle: Bundle) {
    bundle.serializableFromBundle<KarooEffect>()?.let(effects::add)
  }

  override fun addEventConsumer(id: String, params: Bundle, handler: IHandler) {
    val parsed = params.serializableFromBundle<KarooEventParams>()
    lifecycleLock.withLock {
      // Registration, submit and close share the lock, so nothing is submitted after shutdown.
      checkOpen()
      if (parsed == null) {
        handler.onError("Unreadable params")
        return
      }
      if (parsed is OnHttpResponse.MakeHttpRequest) {
        // HTTP consumers live outside `consumers` so consumerCount keeps its old meaning.
        val pending = PendingHttp(parsed, handler, responder, maxBodyBytes)
        pendingHttp[id] = pending
        httpRequests += parsed
        http.execute { serve(id, pending) }
        return
      }
      consumers[id] = Consumer(parsed, handler)
      // The fake replays the current value to a new consumer straight away.
      current(parsed)?.let { send(handler, it) }
    }
  }

  override fun removeEventConsumer(id: String) {
    lifecycleLock.withLock {
      consumers.remove(id)
      // Cancels delivery of a response that has not been sent yet.
      pendingHttp.remove(id)
    }
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

  /**
   * Stores the latest [state] for [dataTypeId] and sends it to every consumer waiting on
   * [OnStreamState.StartStreaming] for that id. A consumer that registers later still gets it. A
   * [StreamState.Streaming] whose point names a different id is rejected as a test bug.
   */
  fun setStreamState(dataTypeId: String, state: StreamState) {
    require(dataTypeId.isNotBlank()) { "dataTypeId must not be blank" }
    if (state is StreamState.Streaming) {
      require(state.dataPoint.dataTypeId == dataTypeId) {
        "Streaming point id ${state.dataPoint.dataTypeId} does not match $dataTypeId"
      }
    }
    streamStates[dataTypeId] = state
    publish(OnStreamState.StartStreaming(dataTypeId), OnStreamState(state))
  }

  /** Publishes [point] as the latest state for the data type id it carries. */
  fun setDataPoint(point: DataPoint) =
    setStreamState(point.dataTypeId, StreamState.Streaming(point))

  /** Publishes a single [DataType.Field.SINGLE] value as the latest state for [dataTypeId]. */
  fun setDataPoint(dataTypeId: String, value: Double) =
    setDataPoint(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))

  /**
   * Sends [event] to consumers registered with [params], and only those. Not sticky: laps and other
   * one-shot events are not replayed to later consumers.
   */
  fun publish(params: KarooEventParams, event: KarooEvent) {
    consumers.values.filter { it.params == params }.forEach { send(it.handler, event) }
  }

  inline fun <reified T : KarooEffect> effectsOf(): List<T> = effects.filterIsInstance<T>()

  /**
   * Forgets every consumer and sticky value, drops the recorded effects and requests, and
   * invalidates in-flight HTTP requests. A response that has not begun delivery is dropped; one
   * already inside a handler has already happened and cannot be recalled.
   */
  fun reset() {
    lifecycleLock.withLock {
      consumers.clear()
      pendingHttp.clear()
      streamStates.clear()
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
  }

  /**
   * Terminal: cancels in-flight HTTP, drops consumers and shuts the executor. Idempotent, and
   * [addEventConsumer] after it throws instead of registering. Safe to call from a handler.
   */
  override fun close() {
    lifecycleLock.withLock {
      if (closed) return
      closed = true
      consumers.clear()
      pendingHttp.clear()
      http.shutdownNow()
    }
  }

  private fun checkOpen() {
    check(!closed) { "FakeKarooSystem is closed" }
  }

  private fun current(params: KarooEventParams): KarooEvent? =
    when (params) {
      OnLocationChanged.Params -> location
      OnNavigationState.Params -> navigation
      RideState.Params -> rideState
      UserProfile.Params -> userProfile
      ActiveRidePage.Params -> activePage
      is OnStreamState.StartStreaming -> streamStates[params.dataTypeId]?.let { OnStreamState(it) }
      else -> null
    }

  @Suppress("TooGenericExceptionCaught")
  private fun serve(id: String, pending: PendingHttp) {
    val started = lifecycleLock.withLock {
      if (!isActive(id, pending)) {
        false
      } else {
        // InProgress goes out first, so a throwing responder still looks like a started request.
        send(pending.handler, OnHttpResponse(HttpResponseState.InProgress))
        true
      }
    }
    // A dead handler makes send drop the request, so do not run the responder for a gone consumer.
    if (!started || !lifecycleLock.withLock { isActive(id, pending) }) return
    val complete =
      try {
        val response = pending.responder.respond(pending.request)
        val body = response.body
        if (body != null && body.size > pending.maxBodyBytes) {
          HttpResponseState.Complete(0, emptyMap(), null, "Response too large: ${body.size}")
        } else {
          response
        }
      } catch (e: Exception) {
        // Any responder failure must become a transport status, and its message may carry the URL.
        HttpResponseState.Complete(0, emptyMap(), null, e.javaClass.name)
      }
    lifecycleLock.withLock {
      if (isActive(id, pending)) {
        // Deliver before removing: a handler that cancels, resets or re-registers this id reenters
        // the lock, and the identity check then preserves whatever it put in the map.
        send(pending.handler, OnHttpResponse(complete))
        pendingHttp.remove(id, pending)
      }
    }
  }

  private fun isActive(id: String, pending: PendingHttp): Boolean =
    !closed && pendingHttp[id] === pending

  private fun send(handler: IHandler, event: KarooEvent) {
    try {
      handler.onNext(event.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))
    } catch (_: RemoteException) {
      // The consumer's process went away; the real Karoo drops such consumers too.
      consumers.entries.removeIf { it.value.handler === handler }
      pendingHttp.entries.removeIf { it.value.handler === handler }
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
