package fi.nikosavola.karooext.testing

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineEncoderTest {
  @Test
  fun `encodes the google reference polyline`() {
    val points = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
    assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encodePolyline(points))
  }
}
