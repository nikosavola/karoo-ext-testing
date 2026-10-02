package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SensorInputTest {
  private val systems = mutableListOf<FakeKarooSystem>()

  @After
  fun closeSystems() {
    systems.forEach { it.close() }
  }

  private fun track(system: FakeKarooSystem): FakeKarooSystem = system.also { systems += it }

  private fun FakeKarooSystem.add(id: String, params: KarooEventParams, handler: IHandler) {
    addEventConsumer(id, params.bundleWithSerializable(KAROO_SYSTEM_PACKAGE), handler)
  }

  private fun start(id: String) = OnStreamState.StartStreaming(id)

  private fun streaming(id: String, value: Double) =
    StreamState.Streaming(DataPoint(id, mapOf(DataType.Field.SINGLE to value)))

  private fun OnStreamState.streaming(): StreamState.Streaming = state as StreamState.Streaming

  @Test
  fun `routes stream state to the matching data type id only`() {
    val system = track(FakeKarooSystem())
    val a = CapturingHandler()
    val b = CapturingHandler()
    system.add("a", start("A"), a)
    system.add("b", start("B"), b)
    system.setStreamState("A", streaming("A", 1.0))
    assertEquals(1.0, a.events<OnStreamState>().single().streaming().dataPoint.singleValue!!, 0.0)
    assertTrue(b.events<OnStreamState>().isEmpty())
  }

  @Test
  fun `sends to every consumer of the same id`() {
    val system = track(FakeKarooSystem())
    val first = CapturingHandler()
    val second = CapturingHandler()
    system.add("one", start("A"), first)
    system.add("two", start("A"), second)
    system.setStreamState("A", streaming("A", 2.0))
    assertEquals(
      2.0,
      first.events<OnStreamState>().single().streaming().dataPoint.singleValue!!,
      0.0,
    )
    assertEquals(
      2.0,
      second.events<OnStreamState>().single().streaming().dataPoint.singleValue!!,
      0.0,
    )
  }

  @Test
  fun `a late consumer gets the latest state`() {
    val system = track(FakeKarooSystem())
    system.setStreamState("A", streaming("A", 3.0))
    val handler = CapturingHandler()
    system.add("late", start("A"), handler)
    assertEquals(
      3.0,
      handler.events<OnStreamState>().single().streaming().dataPoint.singleValue!!,
      0.0,
    )
    assertEquals(
      3.0,
      (system.streams.getValue("A") as StreamState.Streaming).dataPoint.singleValue!!,
      0.0,
    )
  }

  @Test
  fun `loss states are stored and delivered`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("c", start("A"), handler)
    system.setStreamState("A", StreamState.Searching)
    system.setStreamState("A", StreamState.NotAvailable)
    assertEquals(
      listOf(StreamState.Searching, StreamState.NotAvailable),
      handler.events<OnStreamState>().map { it.state },
    )
    val late = CapturingHandler()
    system.add("late", start("A"), late)
    assertEquals(StreamState.NotAvailable, late.events<OnStreamState>().single().state)
  }

  @Test
  fun `a data point keeps all fields and its source`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("c", start("A"), handler)
    val point = DataPoint("A", mapOf("x" to 1.0, "y" to 2.0), sourceId = "sensor-1")
    system.setDataPoint(point)
    val state = handler.events<OnStreamState>().single().streaming()
    assertEquals(point, state.dataPoint)
    assertEquals("sensor-1", state.dataPoint.sourceId)
  }

  @Test
  fun `setDataPoint single value uses the SINGLE field`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("c", start("A"), handler)
    system.setDataPoint("A", 4.5)
    val point = handler.events<OnStreamState>().single().streaming().dataPoint
    assertEquals(4.5, point.values.getValue(DataType.Field.SINGLE), 0.0)
    assertNull(point.sourceId)
  }

  @Test
  fun `reset clears stream states`() {
    val system = track(FakeKarooSystem())
    system.setStreamState("A", streaming("A", 1.0))
    system.reset()
    assertTrue(system.streams.isEmpty())
    val handler = CapturingHandler()
    system.add("late", start("A"), handler)
    assertTrue(handler.events<OnStreamState>().isEmpty())
  }

  @Test
  fun `mismatched streaming id and blank id are rejected`() {
    val system = track(FakeKarooSystem())
    assertThrows(IllegalArgumentException::class.java) {
      system.setStreamState("A", streaming("B", 1.0))
    }
    assertThrows(IllegalArgumentException::class.java) {
      system.setStreamState(" ", StreamState.Searching)
    }
    assertThrows(IllegalArgumentException::class.java) { system.setDataPoint("", 1.0) }
  }

  @Test
  fun `a removed consumer stops receiving but the state is kept`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("c", start("A"), handler)
    system.setStreamState("A", streaming("A", 1.0))
    system.removeEventConsumer("c")
    system.setStreamState("A", streaming("A", 2.0))
    assertEquals(1, handler.events<OnStreamState>().size)
    assertEquals(
      2.0,
      (system.streams.getValue("A") as StreamState.Streaming).dataPoint.singleValue!!,
      0.0,
    )
  }
}
