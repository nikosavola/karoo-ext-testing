package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.OnHttpResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutingResponderTest {
  private fun request(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
  ) = OnHttpResponse.MakeHttpRequest(method, url, headers, body, false)

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
  fun `the first overlapping rule wins`() {
    val responder = RoutingResponder.build {
      path("/a", HttpResponses.status(201))
      path("/a/b", HttpResponses.status(202))
    }
    assertEquals(201, responder.respond(request("https://x/a/b")).statusCode)
  }

  @Test
  fun `matches on the whole request method header and body`() {
    val responder = RoutingResponder.build {
      request(
        {
          it.method == "POST" &&
            it.headers["X-Mode"] == "sync" &&
            it.body?.contentEquals(byteArrayOf(1, 2)) == true
        },
        HttpResponses.success("matched".toByteArray()),
      )
      path("/", HttpResponses.status(418))
    }
    val matched =
      responder.respond(request("https://x/", "POST", mapOf("X-Mode" to "sync"), byteArrayOf(1, 2)))
    assertEquals(200, matched.statusCode)
    assertArrayEquals("matched".toByteArray(), matched.body)
    val mismatched = responder.respond(request("https://x/", "POST", mapOf("X-Mode" to "other")))
    assertEquals(418, mismatched.statusCode)
  }

  @Test
  fun `build snapshots the rules`() {
    val builder = RoutingResponder.Builder()
    builder.path("/one", HttpResponses.status(200))
    val responder = builder.build()
    builder.path("/two", HttpResponses.status(200))
    assertEquals(200, responder.respond(request("https://x/one")).statusCode)
    assertEquals(404, responder.respond(request("https://x/two")).statusCode)
  }

  @Test
  fun `a responder failure propagates`() {
    val responder = RoutingResponder.build {
      request({ true }, HttpResponder { throw IllegalStateException("boom") })
    }
    val failure =
      assertThrows(IllegalStateException::class.java) { responder.respond(request("https://x/")) }
    assertEquals("boom", failure.message)
  }

  @Test
  fun `falls back to not found`() {
    val responder = RoutingResponder.build { path("/tiles/", HttpResponses.success(ByteArray(0))) }
    assertEquals(404, responder.respond(request("https://x/other")).statusCode)
  }
}
