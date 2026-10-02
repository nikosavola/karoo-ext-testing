package fi.nikosavola.karooext.testing

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EARTH_RADIUS_M = 6_371_000.0

class PolylineEncoderTest {
  @Test
  fun `encodes the google reference polyline`() {
    val points = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
    assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encodePolyline(points))
    assertPointsRoundtrip(points)
  }

  @Test
  fun `an empty route encodes to an empty string`() {
    assertEquals("", encodePolyline(emptyList()))
  }

  @Test
  fun `a single point encodes its absolute position`() {
    assertEquals("??", encodePolyline(listOf(0.0 to 0.0)))
    assertEquals("_ibE_seK", encodePolyline(listOf(1.0 to 2.0)))
  }

  @Test
  fun `duplicate points encode zero deltas`() {
    assertEquals("_ibE_seK??", encodePolyline(listOf(1.0 to 2.0, 1.0 to 2.0)))
    assertPointsRoundtrip(listOf(1.0 to 2.0, 1.0 to 2.0))
  }

  @Test
  fun `negative coordinates encode the inverted delta reference`() {
    // -1 encodes as '@' and -2 as 'B' once zig-zagged.
    assertEquals("??@B", encodePolyline(listOf(0.0 to 0.0, -0.00001 to -0.00002)))
    assertPointsRoundtrip(listOf(0.0 to 0.0, -0.00001 to -0.00002))
  }

  @Test
  fun `empty, single and duplicate routes have zero length`() {
    assertEquals(0.0, polylineLengthMeters(emptyList()), 0.0)
    assertEquals(0.0, polylineLengthMeters(listOf(1.0 to 2.0)), 0.0)
    assertEquals(0.0, polylineLengthMeters(listOf(1.0 to 2.0, 1.0 to 2.0)), 0.0)
  }

  @Test
  fun `length is symmetric`() {
    val forward = polylineLengthMeters(listOf(0.0 to 0.0, 1.0 to 0.0))
    val backward = polylineLengthMeters(listOf(1.0 to 0.0, 0.0 to 0.0))
    assertEquals(forward, backward, 0.001)
  }

  @Test
  fun `crosses the antimeridian by the short way`() {
    val meters = polylineLengthMeters(listOf(0.0 to 179.999, 0.0 to -179.999))
    assertEquals(222.4, meters, 1.0)
  }

  @Test
  fun `measures the length of a one degree arc`() {
    // Quarter of a meridian quadrant on a sphere: pi/2 * R / 180.
    assertEquals(111_194.9, polylineLengthMeters(listOf(0.0 to 0.0, 1.0 to 0.0)), 5.0)
  }

  @Test
  fun `antipodal points stay finite`() {
    assertEquals(PI * EARTH_RADIUS_M, polylineLengthMeters(listOf(0.0 to 0.0, 0.0 to 180.0)), 1.0)
  }

  private fun assertPointsRoundtrip(points: List<Pair<Double, Double>>) {
    val decoded = decodePolyline(encodePolyline(points))
    assertEquals(points.size, decoded.size)
    points.zip(decoded).forEach { (expected, actual) ->
      assertEquals(expected.first, actual.first, 1e-5)
      assertEquals(expected.second, actual.second, 1e-5)
    }
    assertTrue(decoded.isNotEmpty())
  }

  // Independent reference: the inverse of the standard algorithm, not a copy of the encoder.
  private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
    val points = mutableListOf<Pair<Double, Double>>()
    var index = 0
    var lat = 0L
    var lng = 0L
    while (index < encoded.length) {
      lat += decodeValue { encoded[index++] }
      lng += decodeValue { encoded[index++] }
      points += lat / 1e5 to lng / 1e5
    }
    return points
  }

  private fun decodeValue(next: () -> Char): Long {
    var shift = 0
    var value = 0L
    var byte = 0
    do {
      byte = next().code - 63
      value = value or ((byte and 0x1f).toLong() shl shift)
      shift += 5
    } while (byte >= 0x20)
    return if (value and 1L != 0L) (value shr 1).inv() else value shr 1
  }
}
