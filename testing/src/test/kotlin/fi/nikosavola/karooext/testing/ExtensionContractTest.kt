package fi.nikosavola.karooext.testing

import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.aidl.IKarooExtension
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

private const val CONTRACT_AWAIT_MS = 10_000L

/**
 * Pins extension-side SDK behavior through a real [ContractExtension] bound over the generated AIDL
 * binder: unknown type ids and malformed view configs are silent no-ops, wire ids and view configs
 * survive serialization, session cancels fire once, and recorders decode terminal states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
class ExtensionContractTest {
  private lateinit var controller: ServiceController<ContractExtension>
  private lateinit var ext: IKarooExtension
  private val hosts = mutableListOf<FakeKarooHost>()

  @Before
  fun setUp() {
    ContractExtension.reset()
    controller = Robolectric.buildService(ContractExtension::class.java)
    controller.create()
    RobolectricPump.pumpMainLooper()
    ext = IKarooExtension.Stub.asInterface(controller.get().onBind(Intent()))
    RobolectricPump.pumpMainLooper()
  }

  @After
  fun tearDown() {
    // Close hosts first: SDK onDestroy does not cancel emitters, so the host stops are the cleanup.
    hosts.forEach { it.close() }
    controller.destroy()
  }

  private fun host() = FakeKarooHost(ext, RobolectricPump.invoke()).also { hosts += it }

  private fun viewConfig() =
    ViewConfig(
      gridSize = 30 to 15,
      viewSize = 123 to 456,
      textSize = 7,
      alignment = ViewConfig.Alignment.CENTER,
      boundariesEnabled = true,
      preview = true,
    )

  @Test
  fun `an unknown stream typeId is a silent no-op`() {
    val stream = host().startStream("no-such-type")
    RobolectricPump.pumpMainLooper()
    assertTrue(stream.items.isEmpty())
    assertNull(stream.error)
    assertFalse(stream.completed)
  }

  @Test
  fun `a view config bundle without a value is a silent no-op`() {
    val handler = CapturingHandler()
    ext.startView("raw-view", CONTRACT_VIEW_TYPE, Bundle(), handler)
    RobolectricPump.pumpMainLooper()
    assertTrue(handler.bundles.isEmpty())
    assertTrue(handler.views.isEmpty())
    assertFalse(handler.completed)
  }

  @Test
  fun `streamed point carries the full TYPE_EXT wire id`() {
    val stream = host().startStream(CONTRACT_STREAM_TYPE)
    val state =
      stream.await(CONTRACT_AWAIT_MS) { it is StreamState.Streaming } as StreamState.Streaming
    assertEquals(CONTRACT_STREAM_DATA_TYPE, state.dataPoint.dataTypeId)
    assertEquals(
      DataType.dataTypeId(CONTRACT_EXTENSION_ID, CONTRACT_STREAM_TYPE),
      state.dataPoint.dataTypeId,
    )
  }

  @Test
  fun `non-default view config fields survive host serialization`() {
    val config = viewConfig()
    RobolectricPump.advanceBy(1.seconds)
    host().startView(CONTRACT_VIEW_TYPE, config)
    assertEquals(config, ContractExtension.lastViewConfig)
  }

  @Test
  fun `view events and frames are routed separately`() {
    RobolectricPump.advanceBy(1.seconds)
    val view = host().startView(CONTRACT_VIEW_TYPE, viewConfig())
    val frame = view.awaitFrame(CONTRACT_AWAIT_MS)
    assertEquals(1, view.frames.size)
    assertEquals(2, view.events.size)
    assertTrue(view.events[0] is UpdateGraphicConfig)
    assertTrue(view.events[1] is ShowCustomStreamState)
    assertTrue(frame.inflate(ApplicationProvider.getApplicationContext()).texts().isNotEmpty())
  }

  @Test
  fun `two same-type streams get unique ids and stopping one leaves the other running`() {
    val host = host()
    val first = host.startStream(CONTRACT_STREAM_TYPE)
    val second = host.startStream(CONTRACT_STREAM_TYPE)
    assertNotEquals(first.id, second.id)

    val firstState =
      first.await(CONTRACT_AWAIT_MS) { it is StreamState.Streaming } as StreamState.Streaming
    val secondState =
      second.await(CONTRACT_AWAIT_MS) { it is StreamState.Streaming } as StreamState.Streaming
    assertNotEquals(firstState.dataPoint.singleValue, secondState.dataPoint.singleValue)

    host.stopStream(first)
    assertEquals(1, ContractExtension.cancels.count { it == "stream" })

    ContractExtension.streamEmitters[1].onNext(
      StreamState.Streaming(
        DataPoint(CONTRACT_STREAM_DATA_TYPE, mapOf(DataType.Field.SINGLE to 99.0))
      )
    )
    val updated =
      second.await(CONTRACT_AWAIT_MS, after = 1) {
        it is StreamState.Streaming && it.dataPoint.singleValue == 99.0
      }
    assertTrue(updated is StreamState.Streaming)
    assertEquals(1, first.items.size)
  }

  @Test
  fun `every session kind cancels exactly once across repeated stop and close`() {
    val host = host()
    val scan = host.startScan()
    val device = host.connectDevice("contract-uid")
    val stream = host.startStream(CONTRACT_STREAM_TYPE)
    RobolectricPump.advanceBy(1.seconds)
    val view = host.startView(CONTRACT_VIEW_TYPE, viewConfig())
    val map = host.startMap()
    val fit = host.startFit()

    host.stopScan(scan)
    host.stopScan(scan)
    host.disconnectDevice(device)
    host.disconnectDevice(device)
    host.stopStream(stream)
    host.stopStream(stream)
    host.stopView(view)
    host.stopView(view)
    host.stopMap(map)
    host.stopMap(map)
    host.stopFit(fit)
    host.stopFit(fit)
    host.close()
    host.close()

    for (kind in listOf("scan", "device", "stream", "view", "map", "fit")) {
      assertEquals(
        "$kind cancellations: ${ContractExtension.cancels}",
        1,
        ContractExtension.cancels.count { it == kind },
      )
    }
  }

  @Test
  fun `a recorder decodes a terminal error from the real emitter`() {
    val stream = host().startStream(CONTRACT_ERROR_STREAM_TYPE)
    assertEquals("contract failed", stream.awaitError(CONTRACT_AWAIT_MS))
  }

  @Test
  fun `a recorder decodes completion from the real emitter`() {
    val stream = host().startStream(CONTRACT_COMPLETE_STREAM_TYPE)
    stream.awaitComplete(CONTRACT_AWAIT_MS)
    assertTrue(stream.completed)
  }

  @Test
  fun `await fails fast on a terminal error instead of timing out`() {
    val stream = host().startStream(CONTRACT_ERROR_STREAM_TYPE)
    val failure =
      assertThrows(IllegalStateException::class.java) { stream.await(CONTRACT_AWAIT_MS) { false } }
    assertTrue(failure.message!!.contains("terminal error"))
    assertTrue(failure.message!!.contains("contract failed"))
  }
}
