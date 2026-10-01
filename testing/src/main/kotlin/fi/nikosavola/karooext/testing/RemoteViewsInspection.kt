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
import kotlin.math.abs

/** Inflates a field frame the way the Karoo does, into a host container. */
fun RemoteViews.inflate(context: Context): View = apply(context, FrameLayout(context))

fun View.descendants(): Sequence<View> = sequence {
  yield(this@descendants)
  if (this@descendants is ViewGroup) {
    for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
  }
}

fun View.texts(): List<String> =
  descendants()
    .filterIsInstance<TextView>()
    .map { it.text.toString() }
    .filter { it.isNotEmpty() }
    .toList()

fun View.bitmaps(): List<Bitmap> =
  descendants()
    .filterIsInstance<ImageView>()
    .mapNotNull { (it.drawable as? BitmapDrawable)?.bitmap }
    .toList()

/** Whether any pixel is within [tolerance] per channel of [color]; RGB_565 frames shift colours. */
fun Bitmap.hasColorNear(color: Int, tolerance: Int = 12): Boolean {
  val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
  return pixels.any { it.isNear(color, tolerance) }
}

/** Share of pixels near [color], e.g. how much of a map frame is still untiled background. */
fun Bitmap.fractionNear(color: Int, tolerance: Int = 4): Double {
  val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
  return pixels.count { it.isNear(color, tolerance) }.toDouble() / pixels.size
}

private fun Int.isNear(color: Int, tolerance: Int): Boolean =
  abs(Color.red(this) - Color.red(color)) <= tolerance &&
    abs(Color.green(this) - Color.green(color)) <= tolerance &&
    abs(Color.blue(this) - Color.blue(color)) <= tolerance
