package fi.nikosavola.karooext.testing

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

private const val PRECISION = 1e5
private const val CHUNK_BITS = 5
private const val CHUNK_MASK = 0x1f
private const val CONTINUATION = 0x20
private const val ASCII_OFFSET = 63
private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Google encoded polyline, precision 5, as the Karoo sends routes. Points are lat to lng in
 * degrees, paired as `first` = latitude and `second` = longitude; altitude is not encoded. Values
 * are not validated or range-checked, so an out-of-range point encodes as given.
 */
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

/**
 * Decodes a Google encoded polyline into lat to lng pairs, the inverse of [encodePolyline].
 * [precision] is the number of decimals: 5 for map polylines, 1 for the elevation profile in
 * `OnNavigationState`, where the pairs are distance to elevation instead.
 *
 * @throws IllegalArgumentException if [encoded] ends in the middle of a value or a point.
 */
fun decodePolyline(encoded: String, precision: Int = 5): List<Pair<Double, Double>> {
  val scale = 10.0.pow(precision)
  val points = mutableListOf<Pair<Double, Double>>()
  var index = 0
  var lat = 0L
  var lng = 0L

  fun next(): Long {
    var result = 0L
    var shift = 0
    while (true) {
      require(index < encoded.length) { "truncated polyline at index $index" }
      val chunk = encoded[index++].code - ASCII_OFFSET
      result = result or ((chunk and CHUNK_MASK).toLong() shl shift)
      shift += CHUNK_BITS
      if (chunk < CONTINUATION) break
    }
    return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
  }
  while (index < encoded.length) {
    lat += next()
    lng += next()
    points += lat / scale to lng / scale
  }
  return points
}

private fun StringBuilder.appendValue(delta: Long) {
  var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
  while (value >= CONTINUATION) {
    append(((CONTINUATION or (value and CHUNK_MASK.toLong()).toInt()) + ASCII_OFFSET).toChar())
    value = value shr CHUNK_BITS
  }
  append((value + ASCII_OFFSET).toInt().toChar())
}

/**
 * Great-circle length of a polyline, metres, using a spherical earth of radius 6371000 m. A point
 * list with zero or one point has length 0; input is paired as in [encodePolyline] and not
 * validated.
 */
fun polylineLengthMeters(points: List<Pair<Double, Double>>): Double =
  points.zipWithNext().sumOf { (from, to) -> haversineMeters(from, to) }

private fun haversineMeters(from: Pair<Double, Double>, to: Pair<Double, Double>): Double {
  val lat1 = Math.toRadians(from.first)
  val lat2 = Math.toRadians(to.first)
  val dLat = Math.toRadians(to.first - from.first)
  val dLng = Math.toRadians(to.second - from.second)
  val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
  // Near-antipodal points can round a just past 1, which would make asin NaN.
  return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
