package fi.nikosavola.karooext.testing

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.BUNDLE_PACKAGE
import io.hammerhead.karooext.BUNDLE_VALUE
import io.hammerhead.karooext.EXT_LIB_VERSION
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.Lap
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

private const val BINDER_AWAIT_MS = 10_000L

/**
 * Exercises the generated AIDL proxy path by wrapping a target binder so `queryLocalInterface`
 * returns null, forcing `Stub.asInterface` to build a real `Proxy` whose `transact` calls are
 * forwarded to the target. Everything is still in-process and synchronous; this proves marshalling
 * through the generated proxy, not device-side asynchronous delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
class BinderContractTest {
  private val app: Application = ApplicationProvider.getApplicationContext()
  private val systems = mutableListOf<FakeKarooSystem>()
  private val services = mutableListOf<KarooSystemService>()
  private val hosts = mutableListOf<FakeKarooHost>()
  private var extensionController: ServiceController<ContractExtension>? = null

  @Before
  fun setUp() {
    ContractExtension.reset()
  }

  @After
  fun tearDown() {
    // Close hosts first: SDK onDestroy does not cancel emitters, so the host stops are the cleanup.
    hosts.forEach { it.close() }
    services.forEach { it.disconnect() }
    systems.forEach { it.close() }
    extensionController?.destroy()
  }

  private fun track(system: FakeKarooSystem): FakeKarooSystem = system.also { systems += it }

  @Test
  fun `system metadata, events, effects and http bodies cross a forced proxy`() {
    val fake = track(FakeKarooSystem())
    fake.responder = HttpResponses.success("pong".toByteArray())
    shadowOf(app)
      .setComponentNameAndServiceForBindService(
        ComponentName(KAROO_SYSTEM_PACKAGE, KAROO_SYSTEM_SERVICE),
        ProxyForcingBinder(fake.asBinder()),
      )
    val service = KarooSystemService(app).also { services += it }
    service.connect()
    awaitValue(BINDER_AWAIT_MS) {
      RobolectricPump.pumpMainLooper()
      if (service.connected) true else null
    }

    assertEquals(EXT_LIB_VERSION, service.libVersion)
    assertEquals("fake", service.serial)
    assertEquals(HardwareType.KAROO, service.hardwareType)

    val laps = CopyOnWriteArrayList<Lap>()
    service.addConsumer<Lap>(Lap.Params) { laps += it }
    awaitValue(BINDER_AWAIT_MS) { fake.consumerIds.takeIf { it.isNotEmpty() } }
    fake.publish(Lap.Params, Lap(1, 60_000, "proxy"))
    awaitValue(BINDER_AWAIT_MS) { laps.takeIf { it.isNotEmpty() } }
    assertEquals(Lap(1, 60_000, "proxy"), laps.single())

    assertTrue(service.dispatch(TurnScreenOn))
    assertEquals(listOf(TurnScreenOn), fake.effectsOf<TurnScreenOn>())

    val events = CopyOnWriteArrayList<OnHttpResponse>()
    service.addConsumer<OnHttpResponse>(
      OnHttpResponse.MakeHttpRequest(
        method = "POST",
        url = "https://example.test/proxy",
        body = "hi".toByteArray(),
      )
    ) {
      events += it
    }
    val got = awaitValue(BINDER_AWAIT_MS) { events.takeIf { it.size >= 2 } }
    assertEquals("pong", (got[1].state as HttpResponseState.Complete).body!!.decodeToString())
    assertEquals("hi", fake.httpRequests.last().body!!.decodeToString())
  }

  @Test
  fun `extension scan, view config and cancellation cross a forced proxy`() {
    val controller = Robolectric.buildService(ContractExtension::class.java)
    extensionController = controller
    controller.create()
    RobolectricPump.pumpMainLooper()
    val target = controller.get().onBind(Intent())
    val host =
      FakeKarooHost(
          IKarooExtension.Stub.asInterface(ProxyForcingBinder(target)),
          RobolectricPump.invoke(),
        )
        .also { hosts += it }

    val scan = host.startScan()
    val device = scan.await(BINDER_AWAIT_MS) { it.uid == "contract-sensor" }
    assertEquals(CONTRACT_EXTENSION_ID, device.extension)

    RobolectricPump.advanceBy(1.seconds)
    val config =
      ViewConfig(
        gridSize = 60 to 60,
        viewSize = 10 to 20,
        textSize = 9,
        alignment = ViewConfig.Alignment.LEFT,
        boundariesEnabled = true,
        preview = false,
      )
    val view = host.startView(CONTRACT_VIEW_TYPE, config)
    view.awaitFrame(BINDER_AWAIT_MS)
    assertEquals(config, ContractExtension.lastViewConfig)

    host.stopScan(scan)
    assertEquals(1, ContractExtension.cancels.count { it == "scan" })
  }

  @Test
  fun `golden lap json decodes through the SDK codec and ignores unknown fields`() {
    // Hand-written wire form under the SDK's keys, not a re-encoding of a Lap.
    val json = """{"number":3,"durationMs":1250,"trigger":"auto"}"""
    val bundle =
      Bundle().apply {
        putString(BUNDLE_VALUE, json)
        putString(BUNDLE_PACKAGE, KAROO_SYSTEM_PACKAGE)
      }
    assertEquals(Lap(3, 1250, "auto"), bundle.serializableFromBundle<Lap>())

    val withUnknown = """{"number":4,"durationMs":1,"trigger":"manual","future":true}"""
    val tolerant = Bundle().apply { putString(BUNDLE_VALUE, withUnknown) }
    assertEquals(Lap(4, 1, "manual"), tolerant.serializableFromBundle<Lap>())
  }

  @Test
  fun `malformed json throws from the SDK codec`() {
    val broken = Bundle().apply { putString(BUNDLE_VALUE, "{not json") }
    assertThrows(SerializationException::class.java) { broken.serializableFromBundle<Lap>() }
  }

  @Test
  fun `sealed event wire payload carries the type discriminator and package`() {
    // Typed as the sealed base, otherwise the concrete object serializer omits the discriminator.
    val event: RideState = RideState.Recording
    val bundle = event.bundleWithSerializable(KAROO_SYSTEM_PACKAGE)
    val raw = bundle.getString(BUNDLE_VALUE)!!
    assertTrue(raw.contains("\"type\""))
    assertEquals(KAROO_SYSTEM_PACKAGE, bundle.getString(BUNDLE_PACKAGE))
    assertEquals(RideState.Recording, bundle.serializableFromBundle<RideState>())
  }
}

/**
 * Drives a real SDK Emitter and ViewEmitter through a forced IHandler Proxy into a recorder's
 * handler, so callback parcels (typed events and RemoteViews) also cross the generated proxy. `sdk
 * = [32, 35]` covers both the typed-parcelable and deprecated RemoteViews bundle branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
class ForcedHandlerCallbackTest {
  @Test
  fun `stream state survives a forced IHandler proxy`() {
    val recorder = StreamRecorder("stream", RobolectricPump.invoke())
    val proxied = IHandler.Stub.asInterface(ProxyForcingBinder(recorder.handler.asBinder()))
    assertNotSame(recorder.handler, proxied)

    val emitter = Emitter.create<StreamState>(KAROO_SYSTEM_PACKAGE, proxied)
    emitter.onNext(
      StreamState.Streaming(DataPoint("TYPE_EXT::contract::contract-stream", mapOf("f" to 3.0)))
    )

    val state =
      recorder.await(BINDER_AWAIT_MS) { it is StreamState.Streaming } as StreamState.Streaming
    assertEquals(3.0, state.dataPoint.values.getValue("f"), 0.0)
  }

  @Test
  fun `view events and frames survive a forced IHandler proxy`() {
    val recorder = ViewRecorder("view", RobolectricPump.invoke())
    val proxied = IHandler.Stub.asInterface(ProxyForcingBinder(recorder.handler.asBinder()))
    assertNotSame(recorder.handler, proxied)

    RobolectricPump.advanceBy(1.seconds)
    val appPackage = ApplicationProvider.getApplicationContext<Context>().packageName
    val emitter = ViewEmitter(appPackage, proxied)
    emitter.onNext(UpdateGraphicConfig(showHeader = false))
    emitter.onNext(ShowCustomStreamState("proxy", null))
    val views = RemoteViews(appPackage, android.R.layout.simple_list_item_1)
    views.setTextViewText(android.R.id.text1, "through proxy")
    emitter.updateView(views)

    val frame = recorder.awaitFrame(BINDER_AWAIT_MS)
    assertTrue(recorder.events.any { it is UpdateGraphicConfig })
    assertTrue(recorder.events.any { it is ShowCustomStreamState })
    val inflated = frame.inflate(ApplicationProvider.getApplicationContext())
    assertTrue(inflated.texts().contains("through proxy"))
  }
}

/**
 * A binder whose `queryLocalInterface` is null, so `Stub.asInterface` builds the generated proxy,
 * and whose `onTransact` forwards every transaction to [target].
 */
private class ProxyForcingBinder(private val target: IBinder) : Binder() {
  override fun queryLocalInterface(descriptor: String): IInterface? = null

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
    target.transact(code, data, reply, flags)
}
