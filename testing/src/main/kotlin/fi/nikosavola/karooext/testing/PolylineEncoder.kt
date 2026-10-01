package fi.nikosavola.karooext.testing

import kotlin.math.roundToLong

private const val PRECISION = 1e5
private const val CHUNK_BITS = 5
private const val CHUNK_MASK = 0x1f
private const val CONTINUATION = 0x20
private const val ASCII_OFFSET = 63

/** Google encoded polyline, precision 5, as the Karoo sends routes. Points are lat to lng. */
fun encodePolyline(points: List<Pair<Double, Double>>): String = buildString {
  var lastLat = 0L
  var lastLng = 0L
  for ((lat, lng) in points) {
    val latE5 = (lat * PRECISION).roundToLong()
    val lngE5 = (lng * PRECISION).roundToLong()
    appendValue(latE5 - lastLat)
    appendValue(lngE5 - lastLng)
    lastLat = latE5
    lastLng = lngE5
  }
}

private fun StringBuilder.appendValue(delta: Long) {
  var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
  while (value >= CONTINUATION) {
    append(((CONTINUATION or (value and CHUNK_MASK.toLong()).toInt()) + ASCII_OFFSET).toChar())
    value = value shr CHUNK_BITS
  }
  append((value + ASCII_OFFSET).toInt().toChar())
}
