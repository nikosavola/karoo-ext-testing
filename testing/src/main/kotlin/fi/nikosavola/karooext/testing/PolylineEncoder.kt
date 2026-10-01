package fi.nikosavola.karooext.testing

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

private const val PRECISION = 1e5
private const val CHUNK_BITS = 5
private const val CHUNK_MASK = 0x1f
private const val CONTINUATION = 0x20
private const val ASCII_OFFSET = 63
private const val EARTH_RADIUS_M = 6_371_000.0

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

/** Great-circle length of a polyline, metres. */
fun polylineLengthMeters(points: List<Pair<Double, Double>>): Double =
  points.zipWithNext().sumOf { (from, to) -> haversineMeters(from, to) }

private fun haversineMeters(from: Pair<Double, Double>, to: Pair<Double, Double>): Double {
  val lat1 = Math.toRadians(from.first)
  val lat2 = Math.toRadians(to.first)
  val dLat = Math.toRadians(to.first - from.first)
  val dLng = Math.toRadians(to.second - from.second)
  val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
  return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}
