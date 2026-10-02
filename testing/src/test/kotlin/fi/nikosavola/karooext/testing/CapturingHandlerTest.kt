package fi.nikosavola.karooext.testing

import android.content.Context
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewEvent
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CapturingHandlerTest {
  private val appPackage = ApplicationProvider.getApplicationContext<Context>().packageName

  private fun frame() = RemoteViews(appPackage, android.R.layout.simple_list_item_1)

  @Test
  fun `stream events roundtrip with the wire keys`() {
    val handler = CapturingHandler()
    val emitter = Emitter.create<StreamState>("my.pkg", handler)
    val state =
      StreamState.Streaming(DataPoint("sample-value", mapOf(DataType.Field.SINGLE to 42.0)))

    emitter.onNext(state)

    val bundle = handler.bundles.single()
    assertEquals("my.pkg", bundle.getString("package"))
    val value = bundle.getString("value")!!
    // No @SerialName upstream, so the sealed discriminator is the fully-qualified serial name.
    assertTrue(
      value,
      value.contains("\"type\":\"io.hammerhead.karooext.models.StreamState.Streaming\""),
    )
    assertTrue(value, value.contains("\"dataTypeId\":\"sample-value\""))
    assertEquals(listOf(state), handler.events<StreamState>())
  }

  @Test
  fun `view frames use the view key, not the value key`() {
    val handler = CapturingHandler()
    val emitter = ViewEmitter(appPackage, handler)
    val frame = frame()

    emitter.updateView(frame)

    assertEquals(listOf(frame), handler.views)
    assertTrue(handler.bundles.isEmpty())
    assertTrue(handler.events<ViewEvent>().isEmpty())
  }

  @Test
  fun `view events and frames do not mix`() {
    val handler = CapturingHandler()
    val emitter = ViewEmitter(appPackage, handler)
    val event = ShowCustomStreamState("hi", 0xFF00FF00.toInt())
    val frame = frame()

    emitter.onNext(event)
    emitter.updateView(frame)

    assertEquals(listOf(event), handler.events<ViewEvent>())
    assertEquals(listOf(frame), handler.views)
    assertEquals(1, handler.bundles.size)
  }

  @Test
  fun `completion and a null error are recorded`() {
    val handler = CapturingHandler()

    handler.onComplete()
    handler.onError(null)

    assertTrue(handler.completed)
    assertEquals(listOf(""), handler.errors)
  }

  @Test
  @Config(sdk = [35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `view emitter drops updates inside the throttle window`() {
    val handler = CapturingHandler()
    val emitter = ViewEmitter(appPackage, handler)
    val frame = frame()

    ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS)
    emitter.updateView(frame)
    emitter.updateView(frame)
    assertEquals(1, handler.views.size)

    ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS)
    emitter.updateView(frame)
    assertEquals(2, handler.views.size)
  }
}
