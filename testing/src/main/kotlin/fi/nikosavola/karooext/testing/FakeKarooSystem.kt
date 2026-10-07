package fi.nikosavola.karooext.testing

import android.os.Bundle
import android.os.RemoteException
import io.hammerhead.karooext.EXT_LIB_VERSION
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooSystem
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.ActiveRideProfile
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
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.ReleaseAnt
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestAnt
import io.hammerhead.karooext.models.RequestBluetooth
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

/** Package of the Karoo system app the SDK binds to, mirrored by the appstore stand-in. */
const val KAROO_SYSTEM_PACKAGE = "io.hammerhead.appstore"

/** Fully-qualified `AppStoreService` name the SDK binds to by name. */
const val KAROO_SYSTEM_SERVICE = "io.hammerhead.appstore.service.AppStoreService"

private const val BRIDGE_MAX_BODY = 100_000
private const val DEFAULT_HTTP_THREADS = 1
private const val FULL_GRID = 60
private const val SERIAL = "fake"
private const val DEFAULT_ACCURACY_METERS = 5.0

private const val BLUETOOTH = "bluetooth"
private const val ANT = "ant"

/**
 * The Karoo system end of the karoo-ext binder: what `KarooSystemService` in an extension talks to.
 *
 * Sticky delivery here is a fake policy, not a device guarantee. The SDK only documents that a new
 * consumer of [UserProfile] and [RideState] receives the current value; location, navigation, the
 * active page and stream state are replayed too because extension tests rely on it. [reset] clears
 * every stored value.
 *
 * HTTP is answered on `httpThreads` (one by default, so responses land in request order; it must be
 * positive). The `responder` and body limit are captured when a request is registered, so changing
 * either later does not affect a request already in flight. [reset] and [removeEventConsumer]
 * invalidate in-flight requests and [close] is terminal. Registration, cancellation and delivery
 * share one lock, so a cancel that returns before a delivery starts prevents it, but a delivery
 * already inside a handler completes. Ordinary [publish] shares that lock and drops a consumer that
 * a callback cancelled, reset or replaced; sticky setters store and publish under it too. Response
 * arrival order is only guaranteed when `httpThreads` is one; with several threads two answers can
 * interleave. After [close], the setters and [info] stay usable and update stored state, but
 * publication delivers to nothing.
 *
 * [completeConsumer] and [errorConsumer] drive an ordinary consumer's terminal callbacks for tests
 * that need the real SDK's auto-unregister path; they are a raw hook, not a device guarantee.
 *
 * @property responder answers each bridged HTTP request, a `404` by default; captured per request
 *   at registration. Its exceptions are turned into a status 0 error carrying only the
 *   fully-qualified class name, never the request, so a URL in the message cannot leak.
 * @param maxBodyBytes largest response body the bridge accepts; larger bodies become a status 0
 *   error.
 * @param httpThreads size of the HTTP executor.
 * @property hardwareType what `info()` reports and what [reset] restores.
 * @param libVersion what `libVersion()` reports; fixed constructor metadata that [reset] keeps.
 * @throws IllegalArgumentException if `maxBodyBytes` is negative or `httpThreads` is not positive.
 */
@Suppress("TooManyFunctions")
class FakeKarooSystem(
  @Volatile var responder: HttpResponder = HttpResponses.notFound(),
  maxBodyBytes: Int = BRIDGE_MAX_BODY,
  httpThreads: Int = DEFAULT_HTTP_THREADS,
  @Volatile var hardwareType: HardwareType = HardwareType.KAROO,
  /**
   * Version `libVersion` reports, for simulating a system older or newer than the SDK this fake is
   * built against. The default is karoo-ext's EXT_LIB_VERSION, which is a compile-time constant
   * inlined into this class: it is the SDK version the fake was built against, not the version on a
   * consumer's runtime classpath.
   */
  libVersion: String = EXT_LIB_VERSION,
) : IKarooSystem.Stub(), Closeable {
  private val reportedLibVersion = libVersion

  // Constructor metadata, restored by reset so a mutated hardware type cannot leak across tests.
  private val initialHardwareType = hardwareType

  private class Consumer(val params: KarooEventParams, val handler: IHandler)

  /**
   * One registered HTTP request; `responder` and `maxBodyBytes` are snapshotted at registration.
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
  private val stickyEvents = ConcurrentHashMap<KarooEventParams, KarooEvent>()

  /**
   * Sent to a new stream consumer whose data type has no state yet, or nothing when null (the
   * default). Set it to [StreamState.Searching] or [StreamState.NotAvailable] when the extension
   * combines several streams and would otherwise wait forever on one the test does not care about.
   * Fake policy: the device's first state for an idle data type is not documented. [reset] clears
   * it.
   */
  @Volatile var initialStreamState: StreamState? = null

  /**
   * Largest response body the bridge accepts; a larger body becomes a status 0 error. Captured per
   * request when it is registered. Setting a negative value throws `IllegalArgumentException`.
   */
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
    // Checked here rather than left to the executor, which throws a bare IllegalArgumentException.
    require(httpThreads > 0) { "httpThreads must be positive, was $httpThreads" }
  }

  private val http = Executors.newFixedThreadPool(httpThreads)

  @Volatile private var closed = false

  /** Every effect dispatched through the binder so far, in order. A live thread-safe log. */
  val effects = CopyOnWriteArrayList<KarooEffect>()

  /**
   * Resource ids passed to `RequestBluetooth` that no `ReleaseBluetooth` has answered yet, in
   * request order. An extension that connects a sensor should end a test with this empty.
   */
  val bluetoothClaims: Set<String>
    get() = radioLedger().held(BLUETOOTH)

  /** Like [bluetoothClaims], for `RequestAnt` and `ReleaseAnt`. */
  val antClaims: Set<String>
    get() = radioLedger().held(ANT)

  /**
   * Every HTTP request registered so far, in order. A live thread-safe log of arrivals, not a
   * snapshot of what is still pending; see [pendingHttpCount] for that.
   */
  val httpRequests = CopyOnWriteArrayList<OnHttpResponse.MakeHttpRequest>()

  /** Latest location from [setLocation], or null before the first. Replayed to new consumers. */
  @Volatile
  var location: OnLocationChanged? = null
    private set

  /**
   * Latest navigation state, idle by default. [setNavigation] and [setRoute] update it, and it is
   * replayed to new consumers.
   */
  @Volatile
  var navigation: OnNavigationState = OnNavigationState(OnNavigationState.NavigationState.Idle)
    private set

  /** Latest ride state from [setRideState], idle by default. Sticky for new consumers. */
  @Volatile
  var rideState: RideState = RideState.Idle
    private set

  /** Latest user profile from [setUserProfile], metric by default. Sticky for new consumers. */
  @Volatile
  var userProfile: UserProfile = metricProfile()
    private set

  /** Page shown by [showPage], or null before the first. Sticky for new consumers. */
  @Volatile
  var activePage: ActiveRidePage? = null
    private set

  /** Number of registered ordinary event consumers. HTTP consumers are counted separately. */
  val consumerCount: Int
    get() = consumers.size

  /** Registered event params, so a test can assert a consumer was cancelled without its handler. */
  val consumerParams: List<KarooEventParams>
    get() = consumers.values.map { it.params }

  /** Registered ordinary consumer ids, e.g. to drive a terminal callback on one. */
  val consumerIds: List<String>
    get() = consumers.keys.toList()

  /** HTTP requests registered but not yet answered or cancelled. */
  val pendingHttpCount: Int
    get() = pendingHttp.size

  /** Latest stream state per data type id, a read-only snapshot. */
  val streams: Map<String, StreamState>
    get() = streamStates.toMap()

  /** Reports the constructor's `libVersion`, not whatever SDK is on the runtime classpath. */
  override fun libVersion(): String = reportedLibVersion

  /** Returns `KarooInfo` with the fixed serial and the current `hardwareType`. */
  override fun info(): Bundle =
    KarooInfo(SERIAL, hardwareType).bundleWithSerializable(KAROO_SYSTEM_PACKAGE)

  /** Records the `KarooEffect` in [bundle] into [effects]; an unreadable bundle is ignored. */
  override fun dispatchEffect(bundle: Bundle) {
    bundle.serializableFromBundle<KarooEffect>()?.let(effects::add)
  }

  /**
   * Registers [handler] for the event params in [params]. A params bundle that does not decode
   * calls `onError("Unreadable params")` and registers nothing. An HTTP params bundle becomes a
   * pending request answered on the executor rather than an ordinary consumer; an ordinary consumer
   * is sent the current sticky value immediately when one exists. Throws if the system is closed.
   */
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

  /**
   * Removes an ordinary consumer or a pending HTTP request by [id]. A pending request that has not
   * begun delivery is dropped; one already inside its handler finishes. Unknown ids are ignored.
   */
  override fun removeEventConsumer(id: String) {
    lifecycleLock.withLock {
      consumers.remove(id)
      // Cancels delivery of a response that has not been sent yet.
      pendingHttp.remove(id)
    }
  }

  /**
   * Ends [id]'s ordinary consumer like the system completing a stream: invokes the handler's
   * `onComplete` without removing it first, so the SDK's wrapper is the one that unregisters. This
   * is a raw handler hook for driving the SDK's auto-unregister contract, not a device guarantee
   * that the system ever completes a consumer.
   *
   * Returns whether an ordinary consumer with [id] was registered. Pending HTTP consumers are out
   * of scope and report `false`. To stop a raw handler that does not unregister itself from
   * leaking, the consumer is removed by identity after the callback; a reentrant replacement
   * registered under the same id during the callback is a different instance and is preserved.
   */
  fun completeConsumer(id: String): Boolean = injectTerminal(id) { it.onComplete() }

  /**
   * Ends [id]'s ordinary consumer like the system failing a stream, passing [message]. Semantics
   * match [completeConsumer], including the `false` return when [id] is unknown.
   */
  fun errorConsumer(id: String, message: String): Boolean =
    injectTerminal(id) { it.onError(message) }

  private fun injectTerminal(id: String, terminal: (IHandler) -> Unit): Boolean =
    lifecycleLock.withLock {
      if (closed) return false
      val consumer = consumers[id] ?: return false
      try {
        terminal(consumer.handler)
      } catch (_: RemoteException) {
        // The consumer's process went away; the real Karoo drops such consumers too. The identity
        // removal below still runs.
      } finally {
        consumers.remove(id, consumer)
      }
      true
    }

  /**
   * Publishes a location and stores it for late consumers, both as [OnLocationChanged] and as a
   * [DataType.Type.LOCATION] stream point, since extensions read either.
   *
   * @param lat latitude in degrees.
   * @param lng longitude in degrees.
   * @param orientation bearing in degrees, or null when unknown, which leaves
   *   [DataType.Field.LOC_BEARING] out of the point.
   * @param accuracy horizontal accuracy in meters for [DataType.Field.LOC_ACCURACY]. Some
   *   extensions drop fixes above a threshold, so the default is a good fix.
   */
  fun setLocation(
    lat: Double,
    lng: Double,
    orientation: Double? = null,
    accuracy: Double = DEFAULT_ACCURACY_METERS,
  ) {
    val event = OnLocationChanged(lat, lng, orientation)
    val point =
      DataPoint(
        DataType.Type.LOCATION,
        buildMap {
          put(DataType.Field.LOC_LATITUDE, lat)
          put(DataType.Field.LOC_LONGITUDE, lng)
          put(DataType.Field.LOC_ACCURACY, accuracy)
          orientation?.let { put(DataType.Field.LOC_BEARING, it) }
        },
      )
    lifecycleLock.withLock {
      location = event
      publishLocked(OnLocationChanged.Params, event)
      streamStates[DataType.Type.LOCATION] = StreamState.Streaming(point)
      publishLocked(
        OnStreamState.StartStreaming(DataType.Type.LOCATION),
        OnStreamState(StreamState.Streaming(point)),
      )
    }
  }

  /** Publishes a navigation state and stores it as the latest, replayed to new consumers. */
  fun setNavigation(state: OnNavigationState.NavigationState) {
    val event = OnNavigationState(state)
    lifecycleLock.withLock {
      navigation = event
      publishLocked(OnNavigationState.Params, event)
    }
  }

  /**
   * Navigates [points] (lat to lng) as the active route, the way the ride app follows a planned
   * one; distance along the polyline unless [routeDistanceMeters] says otherwise.
   *
   * @sample fi.nikosavola.karooext.testing.samples.routeInput
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

  /** Publishes a ride state and stores it as the latest, replayed to new consumers. */
  fun setRideState(state: RideState) {
    lifecycleLock.withLock {
      rideState = state
      publishLocked(RideState.Params, state)
    }
  }

  /** Publishes a user profile and stores it as the latest, replayed to new consumers. */
  fun setUserProfile(profile: UserProfile) {
    lifecycleLock.withLock {
      userProfile = profile
      publishLocked(UserProfile.Params, profile)
    }
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
    lifecycleLock.withLock {
      activePage = page
      publishLocked(ActiveRidePage.Params, page)
    }
  }

  /** Publishes the active ride profile, e.g. an indoor one, and replays it to new consumers. */
  fun setActiveRideProfile(profile: RideProfile) =
    setSticky(ActiveRideProfile.Params, ActiveRideProfile(profile))

  /** Publishes the map zoom level and replays it to new consumers. */
  fun setMapZoom(level: Double) = setSticky(OnMapZoomLevel.Params, OnMapZoomLevel(level))

  /**
   * Stores [event] as the latest value for [params], publishes it and replays it to consumers that
   * register later. For state-like events without a typed setter here, such as `SavedDevices`,
   * `Bikes`, `OnGlobalPOIs` or `OnMapZoomLevel`. Use [publish] for one-shot events like laps.
   *
   * @throws IllegalArgumentException if [params] has a typed setter, which owns its sticky state.
   */
  fun setSticky(params: KarooEventParams, event: KarooEvent) {
    require(!params.hasTypedSetter()) { "Use the typed setter for $params" }
    lifecycleLock.withLock {
      stickyEvents[params] = event
      publishLocked(params, event)
    }
  }

  private fun KarooEventParams.hasTypedSetter() =
    this == OnLocationChanged.Params ||
      this == OnNavigationState.Params ||
      this == RideState.Params ||
      this == UserProfile.Params ||
      this == ActiveRidePage.Params ||
      this is OnStreamState.StartStreaming ||
      this is OnHttpResponse.MakeHttpRequest

  /**
   * Stores the latest [state] for [dataTypeId] and sends it to every consumer waiting on
   * [OnStreamState.StartStreaming] for that id. A consumer that registers later still gets it. A
   * [StreamState.Streaming] whose point names a different id is rejected as a test bug.
   *
   * @throws IllegalArgumentException if [dataTypeId] is blank, or if a [StreamState.Streaming]
   *   [state] carries a point whose id is not [dataTypeId].
   */
  fun setStreamState(dataTypeId: String, state: StreamState) {
    require(dataTypeId.isNotBlank()) { "dataTypeId must not be blank" }
    if (state is StreamState.Streaming) {
      require(state.dataPoint.dataTypeId == dataTypeId) {
        "Streaming point id ${state.dataPoint.dataTypeId} does not match $dataTypeId"
      }
    }
    lifecycleLock.withLock {
      streamStates[dataTypeId] = state
      publishLocked(OnStreamState.StartStreaming(dataTypeId), OnStreamState(state))
    }
  }

  /**
   * Publishes [point] as the latest state for the data type id it carries.
   *
   * @sample fi.nikosavola.karooext.testing.samples.nativePowerInput
   */
  fun setDataPoint(point: DataPoint) =
    setStreamState(point.dataTypeId, StreamState.Streaming(point))

  /**
   * Publishes a single [DataType.Field.SINGLE] value as the latest state for [dataTypeId]. Only
   * correct for types whose native field is SINGLE; a type such as POWER needs its own field and
   * source, so use the [DataPoint] overload instead.
   */
  fun setDataPoint(dataTypeId: String, value: Double) =
    setDataPoint(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))

  /**
   * Sends [event] to consumers registered with [params], and only those. Not sticky: laps and other
   * one-shot events are not replayed to later consumers. A consumer cancelled in a callback is
   * skipped too, so a removed consumer does not receive the event.
   */
  fun publish(params: KarooEventParams, event: KarooEvent) {
    lifecycleLock.withLock { publishLocked(params, event) }
  }

  private fun publishLocked(params: KarooEventParams, event: KarooEvent) {
    if (closed) return
    val snapshot = consumers.entries.filter { it.value.params == params }.map { it.key to it.value }
    for ((id, consumer) in snapshot) {
      // Callbacks reenter and may cancel, reset or replace consumers, so recheck each one.
      if (!closed && consumers[id] === consumer) send(consumer.handler, event)
    }
  }

  /**
   * A readable snapshot of what the extension registered and sent: consumers, pending and recorded
   * HTTP requests, stream states and effects. For failure messages, not for assertions.
   */
  fun describe(): String = buildString {
    appendLine("FakeKarooSystem${if (closed) " (closed)" else ""}")
    appendLine("  consumers: ${consumerParams.map { it.label() }.ifEmpty { "none" }}")
    appendLine("  stream states: ${streams.ifEmpty { "none" }}")
    appendLine(
      "  http requests: ${httpRequests.map { "${it.method} ${it.url}" }.ifEmpty { "none" }}"
    )
    appendLine("  pending http: $pendingHttpCount")
    appendLine("  radio claims: bluetooth=$bluetoothClaims, ant=$antClaims")
    append("  effects: ${effects.ifEmpty { "none" }}")
  }

  // Object params such as RideState.Params print as a bare "Params".
  private fun KarooEventParams.label(): String =
    toString().takeUnless { it == "Params" }
      ?: javaClass.name.substringAfterLast('.').replace('$', '.')

  /** Effects of [T] recorded so far, backed by [effects]. */
  inline fun <reified T : KarooEffect> effectsOf(): List<T> = effects.filterIsInstance<T>()

  /** Whether every Bluetooth and ANT request has been released and no release came unasked. */
  fun radiosBalanced(): Boolean = radioLedger().let { it.held.isEmpty() && it.unmatched.isEmpty() }

  /**
   * Fails when a Bluetooth or ANT resource was requested and never released, or released without
   * being requested. A leaked claim keeps the radio on for the whole ride on a real device.
   *
   * @throws AssertionError listing the unbalanced resource ids.
   */
  fun assertRadiosReleased() {
    val ledger = radioLedger()
    val problems = buildList {
      if (ledger.held.isNotEmpty()) add("requested and never released: ${ledger.held}")
      if (ledger.unmatched.isNotEmpty()) add("released but never requested: ${ledger.unmatched}")
    }
    if (problems.isNotEmpty()) {
      throw AssertionError("Radio claims unbalanced; ${problems.joinToString("; ")}")
    }
  }

  private class RadioLedger {
    val held = linkedSetOf<String>()
    val unmatched = mutableListOf<String>()

    fun held(kind: String): Set<String> =
      held
        .filter { it.startsWith("$kind:") }
        .map { it.substringAfter(':') }
        .toCollection(linkedSetOf())

    private fun key(kind: String, id: String) = "$kind:$id"

    fun request(kind: String, id: String) {
      held += key(kind, id)
    }

    fun release(kind: String, id: String) {
      if (!held.remove(key(kind, id))) unmatched += key(kind, id)
    }
  }

  private fun radioLedger(): RadioLedger =
    RadioLedger().also { ledger ->
      effects.toList().forEach { effect ->
        when (effect) {
          is RequestBluetooth -> ledger.request(BLUETOOTH, effect.resourceId)
          is ReleaseBluetooth -> ledger.release(BLUETOOTH, effect.resourceId)
          is RequestAnt -> ledger.request(ANT, effect.resourceId)
          is ReleaseAnt -> ledger.release(ANT, effect.resourceId)
          else -> Unit
        }
      }
    }

  /** Whether something streams [dataTypeId], so a test can publish only once it is listened to. */
  fun hasStreamConsumer(dataTypeId: String): Boolean =
    consumers.values.any { (it.params as? OnStreamState.StartStreaming)?.dataTypeId == dataTypeId }

  /**
   * Forgets every consumer and sticky value, drops the recorded effects and requests, and
   * invalidates in-flight HTTP requests. A response that has not begun delivery is dropped; one
   * already inside a handler has already happened and cannot be recalled.
   *
   * Also resets the mutable HTTP config to library defaults, not constructor values: `responder`
   * goes back to a 404 and `maxBodyBytes` back to 100000. By contrast the configured `hardwareType`
   * is restored to the value passed to the constructor, while `libVersion` is fixed constructor
   * metadata and is retained.
   */
  fun reset() {
    lifecycleLock.withLock {
      consumers.clear()
      pendingHttp.clear()
      streamStates.clear()
      stickyEvents.clear()
      initialStreamState = null
      location = null
      navigation = OnNavigationState(OnNavigationState.NavigationState.Idle)
      rideState = RideState.Idle
      userProfile = metricProfile()
      activePage = null
      effects.clear()
      httpRequests.clear()
      responder = HttpResponses.notFound()
      maxBodyBytes = BRIDGE_MAX_BODY
      hardwareType = initialHardwareType
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
      is OnStreamState.StartStreaming ->
        (streamStates[params.dataTypeId] ?: initialStreamState)?.let { OnStreamState(it) }
      else -> stickyEvents[params]
    }

  @Suppress("TooGenericExceptionCaught")
  private fun serve(id: String, pending: PendingHttp) {
    try {
      val started = lifecycleLock.withLock {
        if (!isActive(id, pending)) {
          false
        } else {
          // InProgress goes out first, so a throwing responder still looks like a started request.
          send(pending.handler, OnHttpResponse(HttpResponseState.InProgress))
          true
        }
      }
      // A dead handler makes send drop the request, so do not run the responder for a gone
      // consumer.
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
          // Any responder failure must become a transport status, and its message may carry the
          // URL.
          HttpResponseState.Complete(0, emptyMap(), null, e.javaClass.name)
        }
      lifecycleLock.withLock {
        if (isActive(id, pending)) {
          // Deliver before removing: a handler that cancels, resets or re-registers this id
          // reenters the lock, and the identity check then preserves whatever it put in the map.
          send(pending.handler, OnHttpResponse(complete))
        }
      }
    } finally {
      // Always identity-remove, so a consumer callback that throws cannot wedge the queue: without
      // this the entry stays pending forever and later waits hang. A reentrant replacement under
      // the same id is a different instance and is left in place. A callback exception is not
      // converted into a response; it propagates to the executor after this cleanup.
      pendingHttp.remove(id, pending)
    }
  }

  private fun isActive(id: String, pending: PendingHttp): Boolean =
    !closed && pendingHttp[id] === pending

  private fun send(handler: IHandler, event: KarooEvent) {
    try {
      handler.onNext(event.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))
    } catch (_: RemoteException) {
      // The consumer's process went away; the real Karoo drops such consumers too.
      consumers.entries.removeAll { it.value.handler === handler }
      pendingHttp.entries.removeAll { it.value.handler === handler }
    }
  }

  /** Profile fixture factory: metric and imperial representative user profiles for tests. */
  companion object {
    /** A [UserProfile] with metric units and representative values, the default profile. */
    fun metricProfile(): UserProfile = profile(UserProfile.PreferredUnit.UnitType.METRIC)

    /** A [UserProfile] with imperial units, otherwise like [metricProfile]. */
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
