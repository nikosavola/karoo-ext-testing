package fi.nikosavola.karooext.testing

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.hammerhead.karooext.models.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31, 35])
class MeasuredRemoteViewsTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun frame(text: String = "hello", textSizePx: Float? = null): RemoteViews {
    val remote = RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
    remote.setTextViewText(android.R.id.text1, text)
    if (textSizePx != null) {
      remote.setTextViewTextSize(android.R.id.text1, TypedValue.COMPLEX_UNIT_PX, textSizePx)
    }
    return remote
  }

  private fun View.textView(): TextView = descendants().filterIsInstance<TextView>().first()

  @Test
  fun `exact non-square bounds are measured and laid out`() {
    val root = frame("hello").inflate(context, 321, 123)

    assertEquals(321, root.width)
    assertEquals(123, root.height)
    assertEquals(321, root.measuredWidth)
    assertEquals(123, root.measuredHeight)
    assertEquals(listOf("hello"), root.texts())
  }

  @Test
  fun `convenience inflate uses viewSize not gridSize`() {
    val config = viewConfig(gridSize = 60 to 10, viewSize = 200 to 80)

    val root = frame().inflate(context, config)

    assertEquals(200, root.width)
    assertEquals(80, root.height)
  }

  @Test
  fun `original overload does not measure or lay out`() {
    val root = frame().inflate(context)

    assertEquals(0, root.width)
    assertEquals(0, root.height)
    assertEquals(0, root.measuredWidth)
    assertEquals(0, root.measuredHeight)
  }

  @Test
  fun `repeated inflates are distinct views with independent bounds`() {
    val remote = frame("same")

    val wide = remote.inflate(context, 400, 100)
    val narrow = remote.inflate(context, 120, 100)

    assertNotSame(wide, narrow)
    assertEquals(400, wide.width)
    assertEquals(120, narrow.width)
    assertEquals(listOf("same"), wide.texts())
    assertEquals(listOf("same"), narrow.texts())
  }

  @Test
  fun `non-positive dimensions are rejected`() {
    val remote = frame()

    assertThrows(IllegalArgumentException::class.java) { remote.inflate(context, 0, 10) }
    assertThrows(IllegalArgumentException::class.java) { remote.inflate(context, 10, 0) }
    assertThrows(IllegalArgumentException::class.java) { remote.inflate(context, -1, 10) }
    assertThrows(IllegalArgumentException::class.java) { remote.inflate(context, 10, -1) }
  }

  @Test
  fun `convenience rejects a non-positive viewSize`() {
    val config = viewConfig(gridSize = 10 to 10, viewSize = 0 to 50)

    assertThrows(IllegalArgumentException::class.java) { frame().inflate(context, config) }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `narrow exact bounds wrap more than wide ones`() {
    val text = "the quick brown fox jumps over the lazy dog ".repeat(6)
    val remote = frame(text, textSizePx = 30f)

    val wideLines = remote.inflate(context, 800, 4000).textView().layout.lineCount
    val narrowLines = remote.inflate(context, 200, 4000).textView().layout.lineCount

    assertTrue("wide=$wideLines narrow=$narrowLines", narrowLines > wideLines)
  }

  private fun viewConfig(
    gridSize: Pair<Int, Int>,
    viewSize: Pair<Int, Int>,
  ) =
    ViewConfig(
      gridSize = gridSize,
      viewSize = viewSize,
      textSize = 24,
      alignment = ViewConfig.Alignment.CENTER,
      boundariesEnabled = false,
      preview = false,
    )
}
