package fi.nikosavola.karooext.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewEvent
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CapturingHandlerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val appPackage = context.packageName

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

    assertEquals(1, handler.views.size)
    assertTrue(handler.views.single().inflate(context).texts().isEmpty())
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
    assertEquals(1, handler.views.size)
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
  @Config(sdk = [32, 35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `view emitter drops updates inside the throttle window`() {
    val handler = CapturingHandler()
    val emitter = ViewEmitter(appPackage, handler)
    val frame = frame()

    emit(emitter, frame)
    emitter.updateView(frame)
    assertEquals(1, handler.views.size)

    emit(emitter, frame)
    assertEquals(2, handler.views.size)
  }

  @Test
  @Config(sdk = [32, 35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `a reused frame keeps the earlier receipt`() {
    val handler = CapturingHandler()
    assertReusedTextKeepsEarlier(handler) { handler.views }
  }

  @Test
  @Config(sdk = [32, 35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `a view recorder keeps the earlier receipt`() {
    val recorder = ViewRecorder("view", {})
    assertReusedTextKeepsEarlier(recorder.handler) { recorder.frames }
  }

  @Test
  @Config(sdk = [32, 35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `a suppressed update cannot rewrite the first frame`() {
    val handler = CapturingHandler()
    val emitter = ViewEmitter(appPackage, handler)
    val frame = frame()
    frame.setTextViewText(android.R.id.text1, "A")
    emit(emitter, frame)
    frame.setTextViewText(android.R.id.text1, "B")
    emitter.updateView(frame)

    assertEquals(1, handler.views.size)
    assertEquals(listOf("A"), handler.views[0].inflate(context).texts())
  }

  @Test
  @Config(sdk = [32, 35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `replacing the bitmap payload keeps the earlier frame`() {
    val handler = CapturingHandler()
    assertReplacedBitmapKeepsEarlier(handler) { handler.views }
  }

  @Test
  @Config(sdk = [32, 35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `a view recorder keeps the earlier bitmap payload`() {
    val recorder = ViewRecorder("view", {})
    assertReplacedBitmapKeepsEarlier(recorder.handler) { recorder.frames }
  }

  @Test
  @Config(sdk = [32], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `a source bitmap mutation does not rewrite the captured frame`() {
    val handler = CapturingHandler()
    assertMutatedSourceKeepsEarlier(handler) { handler.views }
  }

  @Test
  @Config(sdk = [32], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `a view recorder keeps the frame when the source bitmap mutates`() {
    val recorder = ViewRecorder("view", {})
    assertMutatedSourceKeepsEarlier(recorder.handler) { recorder.frames }
  }

  private fun emit(emitter: ViewEmitter, frame: RemoteViews) {
    ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS)
    emitter.updateView(frame)
  }

  private fun assertReusedTextKeepsEarlier(handler: IHandler, frames: () -> List<RemoteViews>) {
    val emitter = ViewEmitter(appPackage, handler)
    val frame = frame()
    frame.setTextViewText(android.R.id.text1, "A")
    emit(emitter, frame)
    frame.setTextViewText(android.R.id.text1, "B")
    emit(emitter, frame)

    assertEquals(2, frames().size)
    assertEquals(listOf("A"), frames()[0].inflate(context).texts())
    assertEquals(listOf("B"), frames()[1].inflate(context).texts())
  }

  private fun assertReplacedBitmapKeepsEarlier(handler: IHandler, frames: () -> List<RemoteViews>) {
    val emitter = ViewEmitter(appPackage, handler)
    val frame = RemoteViews(appPackage, android.R.layout.activity_list_item)
    frame.setImageViewBitmap(android.R.id.icon, bitmap())
    emit(emitter, frame)

    // Use a different bitmap instance so the cache does not reuse the earlier payload.
    val replacement = bitmap()
    replacement.setPixel(1, 0, Color.GREEN)
    frame.setImageViewBitmap(android.R.id.icon, replacement)
    emit(emitter, frame)

    val earlier = frames()[0].inflate(context).bitmaps().single()
    assertTrue(earlier.hasColorNear(Color.BLUE, 0))
    assertFalse(earlier.hasColorNear(Color.GREEN, 0))
    val later = frames()[1].inflate(context).bitmaps().single()
    assertTrue(later.hasColorNear(Color.GREEN, 0))
  }

  private fun assertMutatedSourceKeepsEarlier(handler: IHandler, frames: () -> List<RemoteViews>) {
    val emitter = ViewEmitter(appPackage, handler)
    val source = bitmap()
    val frame = RemoteViews(appPackage, android.R.layout.activity_list_item)
    frame.setImageViewBitmap(android.R.id.icon, source)
    emit(emitter, frame)

    // SDK 32 keeps the source bitmap mutable; a stored frame must not follow the mutation.
    source.setPixel(1, 0, Color.GREEN)

    val recorded = frames().single().inflate(context).bitmaps().single()
    assertTrue(recorded.hasColorNear(Color.BLUE, 0))
    assertFalse(recorded.hasColorNear(Color.GREEN, 0))
  }

  private fun bitmap(): Bitmap =
    Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
      setPixel(0, 0, Color.RED)
      setPixel(1, 0, Color.BLUE)
    }
}
