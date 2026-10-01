package fi.nikosavola.karooext.testing

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineEncoderTest {
  @Test
  fun `encodes the google reference polyline`() {
    val points = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
    assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encodePolyline(points))
  }

  @Test
  fun `measures the length of a one degree arc`() {
    // Quarter of a meridian quadrant on a sphere: pi/2 * R / 180.
    assertEquals(111_194.9, polylineLengthMeters(listOf(0.0 to 0.0, 1.0 to 0.0)), 5.0)
  }
}
