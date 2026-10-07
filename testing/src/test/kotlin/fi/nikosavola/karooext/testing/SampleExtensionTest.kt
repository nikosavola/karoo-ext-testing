package fi.nikosavola.karooext.testing

import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.WriteToRecordMesg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SAMPLE_AWAIT_MS = 10_000L

private fun viewConfig() =
  ViewConfig(
    gridSize = 30 to 30,
    viewSize = 30 to 30,
    textSize = 12,
    alignment = ViewConfig.Alignment.LEFT,
    boundariesEnabled = false,
    preview = false,
  )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SampleExtensionTest {
  @get:Rule val karoo = FakeKarooRule()

  @Before fun resetSample() = SampleExtension.reset()

  @Test
  fun `location reaches the extension stream through bridged http`() {
    karoo.system.responder = HttpResponses.success("42".toByteArray())
    val stream = karoo.host<SampleExtension>().startStream(SAMPLE_STREAM_TYPE)
    karoo.system.setLocation(60.0, 24.0)
    val state =
      stream.await(SAMPLE_AWAIT_MS) { it is StreamState.Streaming } as StreamState.Streaming
    assertEquals(42.0, state.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)
    assertEquals(
      "Bearer sample-token",
      karoo.system.httpRequests.first().headers.getValue("Authorization"),
    )
  }

  @Test
  fun `sticky location reaches a late stream`() {
    karoo.system.responder = HttpResponses.success("7".toByteArray())
    karoo.system.setLocation(60.0, 24.0)
    val stream = karoo.host<SampleExtension>().startStream(SAMPLE_STREAM_TYPE)
    val state =
      stream.await(SAMPLE_AWAIT_MS) { it is StreamState.Streaming } as StreamState.Streaming
    assertEquals(7.0, state.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)
  }

  @Test
  fun `view frame is recorded and its text is readable`() {
    val view = karoo.host<SampleExtension>().startView(SAMPLE_VIEW_TYPE, viewConfig())
    val frame = view.awaitFrame(SAMPLE_AWAIT_MS)
    val inflated = frame.inflate(ApplicationProvider.getApplicationContext())
    assertTrue(inflated.texts().any { it == "sample 12" })
  }

  @Test
  fun `map effects are recorded by MapRecorder`() {
    val map = karoo.host<SampleExtension>().startMap()
    val effect = map.await(SAMPLE_AWAIT_MS) { it is ShowSymbols } as ShowSymbols
    assertEquals("start", effect.symbols.single().id)
  }

  @Test
  fun `stopping a stream releases its location consumer`() {
    karoo.system.responder = HttpResponses.success("1".toByteArray())
    val host = karoo.host<SampleExtension>()
    val before = karoo.system.consumerCount
    val stream = host.startStream(SAMPLE_STREAM_TYPE)
    karoo.system.setLocation(60.0, 24.0)
    stream.await(SAMPLE_AWAIT_MS) { it is StreamState.Streaming }
    assertTrue(karoo.system.consumerCount > before)
    host.stopStream(stream)
    assertEquals(before, karoo.system.consumerCount)
  }

  @Test
  fun `power type reads built-in power and recovers after a loss`() {
    val stream = karoo.host<SampleExtension>().startStream(SAMPLE_POWER_TYPE)
    stream.await(SAMPLE_AWAIT_MS) { it is StreamState.Searching }
    karoo.system.setDataPoint(DataPoint(DataType.Type.POWER, mapOf(DataType.Field.POWER to 250.0)))
    val first =
      stream.await(SAMPLE_AWAIT_MS) { it is StreamState.Streaming } as StreamState.Streaming
    assertEquals(250.0, first.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)

    // The built-in source drops out, then comes back: the field should report Searching and
    // recover to Streaming rather than getting stuck.
    karoo.system.setStreamState(DataType.Type.POWER, StreamState.NotAvailable)
    stream.await(SAMPLE_AWAIT_MS, after = 2) { it is StreamState.Searching }
    karoo.system.setDataPoint(DataPoint(DataType.Type.POWER, mapOf(DataType.Field.POWER to 300.0)))
    val second =
      stream.await(SAMPLE_AWAIT_MS) { state ->
        state is StreamState.Streaming && state.dataPoint.values[DataType.Field.SINGLE] == 300.0
      } as StreamState.Streaming
    assertEquals(300.0, second.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)
  }

  @Test
  fun `stopping the power stream unsubscribes from built-in power`() {
    val host = karoo.host<SampleExtension>()
    val before = karoo.system.consumerCount
    val stream = host.startStream(SAMPLE_POWER_TYPE)
    karoo.system.setDataPoint(DataPoint(DataType.Type.POWER, mapOf(DataType.Field.POWER to 100.0)))
    stream.await(SAMPLE_AWAIT_MS) { it is StreamState.Streaming }
    assertTrue(karoo.system.consumerCount > before)
    host.stopStream(stream)
    assertEquals(before, karoo.system.consumerCount)
  }

  @Test
  fun `scan decodes a device with its data types`() {
    val scan = karoo.host<SampleExtension>().startScan()
    val device = scan.await(SAMPLE_AWAIT_MS) { it.uid == SAMPLE_DEVICE_UID }
    assertEquals(SAMPLE_EXTENSION_ID, device.extension)
    assertEquals(listOf(DataType.Type.POWER), device.dataTypes)
  }

  @Test
  fun `connect decodes device events and cancel unsubscribes`() {
    val host = karoo.host<SampleExtension>()
    val device = host.connectDevice(SAMPLE_DEVICE_UID)
    val status = device.await(SAMPLE_AWAIT_MS) { it is OnConnectionStatus } as OnConnectionStatus
    assertEquals(ConnectionStatus.CONNECTED, status.status)
    val battery = device.await(SAMPLE_AWAIT_MS) { it is OnBatteryStatus } as OnBatteryStatus
    assertEquals(BatteryStatus.GOOD, battery.status)
    val point = device.await(SAMPLE_AWAIT_MS) { it is OnDataPoint } as OnDataPoint
    assertEquals(200.0, point.dataPoint.values.getValue(DataType.Field.POWER), 0.0)
    assertEquals(SAMPLE_DEVICE_UID, point.dataPoint.sourceId)
    host.disconnectDevice(device)
    assertTrue(SampleExtension.cancelled.contains("connect"))
  }

  @Test
  fun `device recorder helpers read status and data points`() {
    val host = karoo.host<SampleExtension>()
    val scan = host.startScan()
    val device = host.connectDevice(scan.await(SAMPLE_AWAIT_MS) { true })

    device.awaitConnected(SAMPLE_AWAIT_MS)
    val point =
      device.awaitDataPoint(DataType.Type.POWER, SAMPLE_AWAIT_MS) { it.singleValue == 200.0 }

    assertEquals(ConnectionStatus.CONNECTED, device.connectionStatus)
    assertEquals(listOf(point), device.dataPoints(DataType.Type.POWER))
    assertTrue(device.dataPoints(DataType.Type.CADENCE).isEmpty())
    assertThrows(IllegalStateException::class.java) {
      device.awaitStatus(ConnectionStatus.SEARCHING, timeoutMs = 100)
    }
  }

  @Test
  fun `fit effects decode and can be filtered`() {
    val fit = karoo.host<SampleExtension>().startFit()
    val effect = fit.await(SAMPLE_AWAIT_MS) { it is WriteToRecordMesg } as WriteToRecordMesg
    assertEquals(200.0, effect.values.single().value, 0.0)
    assertEquals(1, fit.effectsOf<WriteToRecordMesg>().size)
  }

  @Test
  fun `bonus action reaches the extension`() {
    val host = karoo.host<SampleExtension>()
    host.bonusAction(SAMPLE_BONUS_ACTION)
    assertEquals(listOf(SAMPLE_BONUS_ACTION), SampleExtension.bonusActions.toList())
  }

  @Test
  fun `closing the host cancels scan and fit sessions`() {
    val host = karoo.host<SampleExtension>()
    host.startScan()
    host.startFit()
    host.close()
    assertTrue(SampleExtension.cancelled.contains("scan"))
    assertTrue(SampleExtension.cancelled.contains("fit"))
  }
}
