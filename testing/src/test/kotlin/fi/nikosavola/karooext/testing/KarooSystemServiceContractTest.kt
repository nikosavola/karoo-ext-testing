package fi.nikosavola.karooext.testing

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.hammerhead.karooext.BUNDLE_PACKAGE
import io.hammerhead.karooext.EXT_LIB_VERSION
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooSystem
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.Lap
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.UserProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val AWAIT_MS = 10_000L
private const val EXT_PACKAGE = "fi.nikosavola.contract.ext"
private val SERVICE_COMPONENT = ComponentName(KAROO_SYSTEM_PACKAGE, KAROO_SYSTEM_SERVICE)

/**
 * Drives the real karoo-ext [KarooSystemService] against [FakeKarooSystem] over a hand-driven
 * binder. [RecordingContext] captures the bind and the test delivers onServiceConnected,
 * onServiceDisconnected and onBindingDied itself, so reconnects are deterministic rather than
 * depending on Android's binding timing.
 *
 * Assertions on the fake's own serial, version, sticky replay and HTTP responder are labeled as
 * fake policy, not device guarantees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KarooSystemServiceContractTest {
  private val app: Context = ApplicationProvider.getApplicationContext()
  private val systems = mutableListOf<FakeKarooSystem>()
  private val services = mutableListOf<KarooSystemService>()

  @After
  fun tearDown() {
    services.forEach { it.disconnect() }
    systems.forEach { it.close() }
  }

  private fun track(system: FakeKarooSystem): FakeKarooSystem = system.also { systems += it }

  private fun newService(context: Context): KarooSystemService =
    KarooSystemService(context).also { services += it }

  @Test
  fun `connect binds the appstore component, action, package extra and flags`() {
    val context = RecordingContext(app)
    newService(context).connect()

    val intent = context.intents.single()
    assertEquals(KAROO_SYSTEM_PACKAGE, intent.component!!.packageName)
    assertEquals(KAROO_SYSTEM_SERVICE, intent.component!!.className)
    assertEquals("KarooSystem", intent.action)
    assertEquals(EXT_PACKAGE, intent.getStringExtra(BUNDLE_PACKAGE))
    assertEquals(Context.BIND_AUTO_CREATE, context.flags.single())
  }

  @Test
  fun `metadata is null before connection and reads the fake after it`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    service.connect()

    assertFalse(service.connected)
    assertNull(service.libVersion)
    assertNull(service.info)
    assertNull(service.serial)
    assertNull(service.hardwareType)

    context.deliver(fake)
    assertTrue(service.connected)
    // The fake's own values, not an SDK guarantee.
    assertEquals(EXT_LIB_VERSION, service.libVersion)
    assertEquals("fake", service.serial)
    assertEquals(HardwareType.KAROO, service.hardwareType)
  }

  @Test
  fun `hardware type follows a K2 fake`() {
    val context = RecordingContext(app)
    val service = newService(context)
    service.connect()
    context.deliver(track(FakeKarooSystem(hardwareType = HardwareType.K2)))
    assertEquals(HardwareType.K2, service.hardwareType)
  }

  @Test
  fun `dispatch is false before connection and true after it`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    service.connect()

    assertFalse(service.dispatch(TurnScreenOn))

    context.deliver(fake)
    assertTrue(service.dispatch(TurnScreenOn))
    assertEquals(listOf(TurnScreenOn), fake.effectsOf<TurnScreenOn>())
  }

  @Test
  fun `a consumer registered before connect is delivered after it`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    val laps = CopyOnWriteArrayList<Lap>()

    // Explicit params exercise the generic addConsumer overload.
    service.addConsumer<Lap>(Lap.Params) { laps += it }
    assertEquals(0, fake.consumerCount)

    service.connect()
    context.deliver(fake)
    assertEquals(1, fake.consumerCount)

    fake.publish(Lap.Params, Lap(1, 60_000, "manual"))
    assertEquals(listOf(Lap(1, 60_000, "manual")), laps.toList())
  }

  @Test
  fun `default RideState and UserProfile params deliver the fake's values`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    val ride = CopyOnWriteArrayList<RideState>()
    val profile = CopyOnWriteArrayList<UserProfile>()

    service.addConsumer<RideState> { ride += it }
    service.addConsumer<UserProfile> { profile += it }
    service.connect()
    context.deliver(fake)

    // The fake replays its current value to a new consumer; a fake policy, not a device contract.
    assertEquals(listOf<RideState>(RideState.Idle), ride.toList())
    assertEquals(FakeKarooSystem.metricProfile(), profile.single())
  }

  @Test
  fun `OnStreamState decodes a POWER data point with its source id`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    val states = CopyOnWriteArrayList<OnStreamState>()

    service.addConsumer<OnStreamState>(OnStreamState.StartStreaming(DataType.Type.POWER)) {
      states += it
    }
    service.connect()
    context.deliver(fake)

    fake.setDataPoint(
      DataPoint(
        DataType.Type.POWER,
        mapOf(DataType.Field.POWER to 250.0),
        sourceId = "power-meter-1",
      )
    )

    val streaming = states.single().state as StreamState.Streaming
    assertEquals(DataType.Type.POWER, streaming.dataPoint.dataTypeId)
    assertEquals(250.0, streaming.dataPoint.values.getValue(DataType.Field.POWER), 0.0)
    assertEquals("power-meter-1", streaming.dataPoint.sourceId)
  }

  @Test
  fun `onBindingDied re-registers the consumer id on the replacement fake`() {
    val context = RecordingContext(app)
    val first = RecordingKarooSystem(track(FakeKarooSystem()))
    val second = RecordingKarooSystem(track(FakeKarooSystem()))
    val service = newService(context)
    val laps = CopyOnWriteArrayList<Lap>()

    service.addConsumer<Lap> { laps += it }
    service.connect()
    context.deliver(first)
    val id = first.addedIds.single()

    context.bindingDied()
    context.deliver(second)

    assertEquals(listOf(id), second.addedIds)
    second.delegate.publish(Lap.Params, Lap(2, 1_000, "reconnected"))
    assertEquals(listOf(Lap(2, 1_000, "reconnected")), laps.toList())
  }

  @Test
  fun `removing a consumer before connect keeps it unregistered`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    val laps = CopyOnWriteArrayList<Lap>()

    val id = service.addConsumer<Lap> { laps += it }
    service.removeConsumer(id)
    service.connect()
    context.deliver(fake)

    assertEquals(0, fake.consumerCount)
    fake.publish(Lap.Params, Lap(3, 500, "nobody"))
    assertTrue(laps.isEmpty())
  }

  @Test
  fun `disconnect unregisters consumers and reconnect does not revive them`() {
    val context = RecordingContext(app)
    val first = RecordingKarooSystem(track(FakeKarooSystem()))
    val second = RecordingKarooSystem(track(FakeKarooSystem()))
    val service = newService(context)

    service.addConsumer<Lap> {}
    service.connect()
    context.deliver(first)
    val id = first.addedIds.single()
    assertEquals(1, first.delegate.consumerCount)

    service.disconnect()
    assertFalse(service.connected)
    assertEquals(0, first.delegate.consumerCount)
    assertEquals(listOf(id), first.removedIds)
    assertEquals(1, context.unbinds)

    service.connect()
    context.deliver(second)
    assertTrue(second.addedIds.isEmpty())
  }

  @Test
  fun `onServiceDisconnected drops the controller`() {
    val context = RecordingContext(app)
    val service = newService(context)
    service.connect()
    context.deliver(track(FakeKarooSystem()))
    assertTrue(service.connected)

    context.serviceDisconnected()
    assertFalse(service.connected)
    assertNull(service.serial)
    assertFalse(service.dispatch(TurnScreenOn))
  }

  @Test
  fun `onComplete runs once while still registered then unregisters`() {
    val context = RecordingContext(app)
    val wrapper = RecordingKarooSystem(track(FakeKarooSystem()))
    val service = newService(context)
    var callbacks = 0
    var countDuringCallback = -1

    service.addConsumer<Lap>(
      onComplete = {
        callbacks += 1
        countDuringCallback = wrapper.delegate.consumerCount
      }
    ) {}
    service.connect()
    context.deliver(wrapper)
    val id = wrapper.addedIds.single()

    wrapper.handlers.getValue(id).onComplete()

    assertEquals(1, callbacks)
    assertEquals(1, countDuringCallback)
    assertEquals(0, wrapper.delegate.consumerCount)
    assertEquals(listOf(id), wrapper.removedIds)
  }

  @Test
  fun `onError passes the message and unregisters`() {
    val context = RecordingContext(app)
    val wrapper = RecordingKarooSystem(track(FakeKarooSystem()))
    val service = newService(context)
    val messages = CopyOnWriteArrayList<String>()

    service.addConsumer<Lap>(onError = { messages += it }) {}
    service.connect()
    context.deliver(wrapper)
    val id = wrapper.addedIds.single()

    wrapper.handlers.getValue(id).onError("boom")

    assertEquals(listOf("boom"), messages.toList())
    assertEquals(0, wrapper.delegate.consumerCount)
    assertEquals(listOf(id), wrapper.removedIds)
  }

  @Test
  fun `an error with no callback still unregisters`() {
    val context = RecordingContext(app)
    val wrapper = RecordingKarooSystem(track(FakeKarooSystem()))
    val service = newService(context)

    service.addConsumer<Lap> {}
    service.connect()
    context.deliver(wrapper)
    val id = wrapper.addedIds.single()

    wrapper.handlers.getValue(id).onError("unhandled")

    assertEquals(0, wrapper.delegate.consumerCount)
    assertEquals(listOf(id), wrapper.removedIds)
  }

  @Test
  fun `http consumer round-trips the request and decodes the response`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    fake.responder = HttpResponses.success("pong".toByteArray(), mapOf("X-Test" to "yes"))
    val service = newService(context)
    val events = CopyOnWriteArrayList<OnHttpResponse>()

    val request =
      OnHttpResponse.MakeHttpRequest(
        method = "POST",
        url = "https://example.test/api",
        headers = mapOf("Authorization" to "Bearer token"),
        body = "body".toByteArray(),
        waitForConnection = true,
      )
    service.addConsumer<OnHttpResponse>(request) { events += it }
    service.connect()
    context.deliver(fake)

    val got = awaitValue(AWAIT_MS) { events.takeIf { it.size >= 2 } }
    assertTrue(got[0].state is HttpResponseState.InProgress)
    val complete = got[1].state as HttpResponseState.Complete
    assertEquals(200, complete.statusCode)
    assertEquals("pong", complete.body!!.decodeToString())
    assertEquals("yes", complete.headers.getValue("X-Test"))

    val recorded = fake.httpRequests.single()
    assertEquals("POST", recorded.method)
    assertEquals("https://example.test/api", recorded.url)
    assertEquals("Bearer token", recorded.headers.getValue("Authorization"))
    assertEquals("body", recorded.body!!.decodeToString())
    assertTrue(recorded.waitForConnection)
  }

  @Test
  fun `http error response decodes status and error`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    fake.responder = HttpResponses.failure("no route")
    val service = newService(context)
    val events = CopyOnWriteArrayList<OnHttpResponse>()

    service.addConsumer<OnHttpResponse>(
      OnHttpResponse.MakeHttpRequest("GET", "https://example.test/api")
    ) {
      events += it
    }
    service.connect()
    context.deliver(fake)

    val got = awaitValue(AWAIT_MS) { events.takeIf { it.size >= 2 } }
    val complete = got[1].state as HttpResponseState.Complete
    assertEquals(0, complete.statusCode)
    assertEquals("no route", complete.error)
  }

  @Test
  fun `completeConsumer makes the SDK unregister the consumer it was returned for`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    var completed = 0
    var countDuringCallback = -1

    service.addConsumer<Lap>(
      onComplete = {
        completed += 1
        countDuringCallback = fake.consumerCount
      }
    ) {}
    service.connect()
    context.deliver(fake)
    val id = fake.consumerIds.single()
    assertEquals(1, fake.consumerCount)

    assertTrue(fake.completeConsumer(id))

    assertEquals(1, completed)
    // The SDK callback runs while still registered, then the SDK itself removes it.
    assertEquals(1, countDuringCallback)
    assertEquals(0, fake.consumerCount)
  }

  @Test
  fun `errorConsumer makes the SDK unregister and delivers the message`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    val messages = CopyOnWriteArrayList<String>()

    service.addConsumer<Lap>(onError = { messages += it }) {}
    service.connect()
    context.deliver(fake)
    val id = fake.consumerIds.single()

    assertTrue(fake.errorConsumer(id, "stream failed"))

    assertEquals(listOf("stream failed"), messages.toList())
    assertEquals(0, fake.consumerCount)
  }

  @Test
  fun `default params map registers all eleven default event types`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    service.connect()
    context.deliver(fake)

    // Registration only: the params are what the SDK maps for the no-params overload.
    service.addConsumer<RideState> {}
    service.addConsumer<Lap> {}
    service.addConsumer<UserProfile> {}
    service.addConsumer<OnLocationChanged> {}
    service.addConsumer<OnGlobalPOIs> {}
    service.addConsumer<OnNavigationState> {}
    service.addConsumer<OnMapZoomLevel> {}
    service.addConsumer<SavedDevices> {}
    service.addConsumer<Bikes> {}
    service.addConsumer<ActiveRideProfile> {}
    service.addConsumer<ActiveRidePage> {}

    val expected =
      setOf(
        RideState.Params,
        Lap.Params,
        UserProfile.Params,
        OnLocationChanged.Params,
        OnGlobalPOIs.Params,
        OnNavigationState.Params,
        OnMapZoomLevel.Params,
        SavedDevices.Params,
        Bikes.Params,
        ActiveRideProfile.Params,
        ActiveRidePage.Params,
      )
    assertEquals(11, fake.consumerCount)
    assertTrue(fake.consumerParams.toSet().containsAll(expected))
  }

  @Test
  fun `stream and http consumers without explicit params throw`() {
    val context = RecordingContext(app)
    val service = newService(context)
    assertThrows(IllegalArgumentException::class.java) { service.addConsumer<OnStreamState> {} }
    assertThrows(IllegalArgumentException::class.java) { service.addConsumer<OnHttpResponse> {} }
  }

  @Test
  fun `onConnection reports true, false, true across connect, drop and reconnect`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    val events = CopyOnWriteArrayList<Boolean>()

    service.connect(onConnection = { events += it })
    context.deliver(fake)
    context.serviceDisconnected()
    context.deliver(fake)

    assertEquals(listOf(true, false, true), events.toList())
  }

  @Test
  fun `disconnect clears the onConnection listener so reconnect does not re-report`() {
    val context = RecordingContext(app)
    val fake = track(FakeKarooSystem())
    val service = newService(context)
    val events = CopyOnWriteArrayList<Boolean>()

    service.connect(onConnection = { events += it })
    context.deliver(fake)
    service.disconnect()
    assertEquals(listOf(true), events.toList())

    service.connect()
    context.deliver(fake)
    assertEquals(listOf(true), events.toList())
  }
}

/** Captures what the SDK binds and lets the test deliver binder callbacks by hand. */
private class RecordingContext(base: Context) : ContextWrapper(base) {
  val intents = CopyOnWriteArrayList<Intent>()
  val flags = CopyOnWriteArrayList<Int>()
  private val connections = CopyOnWriteArrayList<ServiceConnection>()

  var unbinds: Int = 0
    private set

  override fun bindService(service: Intent, conn: ServiceConnection, flag: Int): Boolean {
    intents += service
    flags += flag
    connections += conn
    return true
  }

  override fun unbindService(conn: ServiceConnection) {
    unbinds += 1
  }

  override fun getPackageName(): String = EXT_PACKAGE

  fun deliver(system: IKarooSystem) {
    connections.last().onServiceConnected(SERVICE_COMPONENT, system.asBinder())
  }

  fun serviceDisconnected() {
    connections.last().onServiceDisconnected(SERVICE_COMPONENT)
  }

  fun bindingDied() {
    connections.last().onBindingDied(SERVICE_COMPONENT)
  }
}

/**
 * Forwards to [delegate] while recording the ids and handlers the SDK passes, so a test can follow
 * registration across reconnects and drive terminal callbacks itself.
 */
private class RecordingKarooSystem(val delegate: FakeKarooSystem) : IKarooSystem.Stub() {
  val addedIds = CopyOnWriteArrayList<String>()
  val removedIds = CopyOnWriteArrayList<String>()
  val handlers = ConcurrentHashMap<String, IHandler>()

  override fun libVersion(): String = delegate.libVersion()

  override fun info(): Bundle = delegate.info()

  override fun dispatchEffect(bundle: Bundle) = delegate.dispatchEffect(bundle)

  override fun addEventConsumer(id: String, bundle: Bundle, handler: IHandler) {
    addedIds += id
    handlers[id] = handler
    delegate.addEventConsumer(id, bundle, handler)
  }

  override fun removeEventConsumer(id: String) {
    removedIds += id
    handlers.remove(id)
    delegate.removeEventConsumer(id)
  }
}
