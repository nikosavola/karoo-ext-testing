package fi.nikosavola.karooext.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteViewsInspectionTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun `descendants are depth first from the root and texts skip empty`() {
    val root = FrameLayout(context)
    val header = TextView(context).apply { text = "header" }
    val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val blank = TextView(context).apply { text = "" }
    val nested = TextView(context).apply { text = "nested" }
    group.addView(blank)
    group.addView(nested)
    root.addView(header)
    root.addView(group)

    assertEquals(listOf(root, header, group, blank, nested), root.descendants().toList())
    assertEquals(listOf("header", "nested"), root.texts())
  }

  @Test
  fun `bitmaps picks only bitmap drawables`() {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val bitmapView =
      ImageView(context).apply { setImageDrawable(BitmapDrawable(context.resources, bitmap)) }
    val colorView = ImageView(context).apply { setImageDrawable(ColorDrawable(Color.RED)) }
    val emptyView = ImageView(context)
    val root =
      FrameLayout(context).apply {
        addView(bitmapView)
        addView(colorView)
        addView(emptyView)
      }

    assertEquals(listOf(bitmap), root.bitmaps())
  }

  @Test
  fun `inflates a real remote views frame and reads its text`() {
    val remote = RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
    remote.setTextViewText(android.R.id.text1, "hello frame")

    val inflated = remote.inflate(context)

    assertTrue(inflated.texts().contains("hello frame"))
  }

  @Test
  fun `colour matching respects the per channel tolerance`() {
    val bitmap =
      Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
        setPixel(0, 0, 0xFF808080.toInt())
      }

    assertTrue(bitmap.hasColorNear(0xFF808080.toInt(), 0))
    assertFalse(bitmap.hasColorNear(0xFF808081.toInt(), 0))
    assertTrue(bitmap.hasColorNear(0xFF808081.toInt(), 1))
  }

  @Test
  fun `negative tolerance is rejected`() {
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    assertThrows(IllegalArgumentException::class.java) { bitmap.hasColorNear(Color.RED, -1) }
    assertThrows(IllegalArgumentException::class.java) { bitmap.fractionNear(Color.RED, -1) }
  }

  @Test
  fun `fraction spans none, half and all`() {
    val bitmap =
      Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
        setPixel(0, 0, Color.RED)
        setPixel(1, 0, Color.BLUE)
      }

    assertEquals(0.5, bitmap.fractionNear(Color.RED, 0), 0.0)
    assertEquals(0.0, bitmap.fractionNear(Color.GREEN, 0), 0.0)
    assertEquals(1.0, bitmap.fractionNear(Color.RED, 255), 0.0)
  }

  @Test
  fun `alpha is ignored`() {
    val bitmap =
      Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
        setPixel(0, 0, 0xFF808080.toInt())
      }

    assertTrue(bitmap.hasColorNear(0x00808080, 0))
  }

  @Test
  fun `rgb 565 frames still match within tolerance`() {
    val requested = 0xFF123456.toInt()
    val bitmap =
      Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565).apply { setPixel(0, 0, requested) }

    assertEquals(Bitmap.Config.RGB_565, bitmap.config)
    assertTrue(bitmap.hasColorNear(requested, 12))
    assertEquals(1.0, bitmap.fractionNear(requested, 12), 0.0)
    assertFalse(bitmap.hasColorNear(Color.BLACK, 0))
  }
}
