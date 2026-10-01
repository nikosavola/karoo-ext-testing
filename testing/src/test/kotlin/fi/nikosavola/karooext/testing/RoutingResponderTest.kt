package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.OnHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingResponderTest {
  private fun request(url: String) =
    OnHttpResponse.MakeHttpRequest("GET", url, emptyMap(), null, false)

  @Test
  fun `routes by path substring and url predicate, first match wins`() {
    val responder = RoutingResponder.build {
      path("/tiles/", HttpResponses.success("tile".toByteArray()))
      url({ "geocode" in it }, HttpResponses.status(204))
    }
    assertEquals(200, responder.respond(request("https://x/tiles/1")).statusCode)
    assertEquals(204, responder.respond(request("https://x/geocode?q=1")).statusCode)
  }

  @Test
  fun `falls back to not found`() {
    val responder = RoutingResponder.build { path("/tiles/", HttpResponses.success(ByteArray(0))) }
    assertEquals(404, responder.respond(request("https://x/other")).statusCode)
  }
}
