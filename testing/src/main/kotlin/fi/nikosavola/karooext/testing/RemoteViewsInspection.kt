package fi.nikosavola.karooext.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.abs

/**
 * Applies this frame into a host [FrameLayout] without measuring or laying it out. [context] must
 * resolve the RemoteViews' resource package; an Android or Robolectric context for that package
 * works.
 */
fun RemoteViews.inflate(context: Context): View = apply(context, FrameLayout(context))

/**
 * Applies, exactly measures and lays out this frame under [widthPx] by [heightPx] host bounds, in
 * pixels, with the caller's [context] configuration and no font override, so a test can check
 * layout geometry and content. Does not certify the Karoo's own renderer.
 *
 * @throws IllegalArgumentException if [widthPx] or [heightPx] is not positive.
 */
fun RemoteViews.inflate(context: Context, widthPx: Int, heightPx: Int): View {
  require(widthPx > 0) { "widthPx must be positive, was $widthPx" }
  require(heightPx > 0) { "heightPx must be positive, was $heightPx" }
  val root = apply(context, FrameLayout(context))
  root.measure(
    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
  )
  root.layout(0, 0, widthPx, heightPx)
  return root
}

/**
 * Uses [ViewConfig.viewSize] for the exact host bounds. Only the size is read: `textSize` is
 * already applied to the RemoteViews and the other config fields are not interpreted here.
 *
 * @throws IllegalArgumentException if `config.viewSize` has a non-positive dimension.
 */
fun RemoteViews.inflate(context: Context, config: ViewConfig): View =
  inflate(context, config.viewSize.first, config.viewSize.second)

/** This view and all its descendants, depth first in child order; the root is included. */
fun View.descendants(): Sequence<View> = sequence {
  yield(this@descendants)
  if (this@descendants is ViewGroup) {
    for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
  }
}

/**
 * Visible text of every `TextView` under this view, in depth-first order. Blank-but-nonempty
 * strings are kept; nothing is filtered by visibility.
 */
fun View.texts(): List<String> =
  descendants()
    .filterIsInstance<TextView>()
    .map { it.text.toString() }
    .filter { it.isNotEmpty() }
    .toList()

/**
 * Bitmaps of every `ImageView` under this view that holds a `BitmapDrawable`, in depth-first order.
 * Vector and other drawables are skipped, not rasterized.
 */
fun View.bitmaps(): List<Bitmap> =
  descendants()
    .filterIsInstance<ImageView>()
    .mapNotNull { (it.drawable as? BitmapDrawable)?.bitmap }
    .toList()

/**
 * Whether any pixel is within [tolerance] per channel of [color]; RGB_565 frames shift colours.
 * Alpha is ignored, since 565 frames drop it. Reads the whole bitmap, so it costs width * height.
 *
 * @throws IllegalArgumentException if [tolerance] is negative.
 */
fun Bitmap.hasColorNear(color: Int, tolerance: Int = 12): Boolean {
  require(tolerance >= 0) { "tolerance must be nonnegative, was $tolerance" }
  val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
  return pixels.any { it.isNear(color, tolerance) }
}

/**
 * Share of pixels within [tolerance] per channel of [color], from 0.0 to 1.0, e.g. how much of a
 * map frame is still untiled background. Alpha is ignored, since 565 frames drop it. Reads the
 * whole bitmap, so it costs width * height.
 *
 * @throws IllegalArgumentException if [tolerance] is negative.
 */
fun Bitmap.fractionNear(color: Int, tolerance: Int = 4): Double {
  require(tolerance >= 0) { "tolerance must be nonnegative, was $tolerance" }
  val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
  return pixels.count { it.isNear(color, tolerance) }.toDouble() / pixels.size
}

private fun Int.isNear(color: Int, tolerance: Int): Boolean =
  abs(Color.red(this) - Color.red(color)) <= tolerance &&
    abs(Color.green(this) - Color.green(color)) <= tolerance &&
    abs(Color.blue(this) - Color.blue(color)) <= tolerance
