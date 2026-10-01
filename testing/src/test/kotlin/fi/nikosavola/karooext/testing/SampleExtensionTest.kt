package fi.nikosavola.karooext.testing

import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
