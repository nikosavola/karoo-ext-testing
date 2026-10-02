package fi.nikosavola.karooext.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.hammerhead.karooext.models.OnHttpResponse
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveResponderTest {
  private val responder = LiveResponder()

  private fun server(handler: (HttpExchange) -> Unit): HttpServer =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/", HttpHandler(handler))
      start()
    }

  private fun HttpServer.url(path: String = "/"): String = "http://127.0.0.1:${address.port}$path"

  private fun request(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
  ) = OnHttpResponse.MakeHttpRequest(method, url, headers, body, false)

  // Server field casing and repeated-value order are not guaranteed; compare case-insensitively.
  private fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

  private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
    exchange.sendResponseHeaders(status, body.size.toLong())
    exchange.responseBody.use { it.write(body) }
  }

  @Test
  fun `get returns status bytes and headers`() {
    val server = server { exchange ->
      exchange.responseHeaders.add("X-Test", "yes")
      respond(exchange, 200, "hello".toByteArray())
    }
    try {
      val response = responder.respond(request("GET", server.url("/thing")))
      assertEquals(200, response.statusCode)
      assertArrayEquals("hello".toByteArray(), response.body)
      assertEquals("yes", response.headers.header("X-Test"))
      assertNull(response.error)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `post sends the binary body and headers`() {
    val binary = byteArrayOf(0, 1, 2, -1, 0x7F, 0x00, 0x10)
    val server = server { exchange ->
      exchange.responseHeaders.add("Echo-Method", exchange.requestMethod)
      exchange.responseHeaders.add(
        "Echo-Header",
        exchange.requestHeaders.getFirst("X-Custom").orEmpty(),
      )
      respond(exchange, 200, exchange.requestBody.readBytes())
    }
    try {
      val response =
        responder.respond(
          request("POST", server.url("/submit"), mapOf("X-Custom" to "value"), binary)
        )
      assertEquals(200, response.statusCode)
      assertEquals("POST", response.headers.header("Echo-Method"))
      assertEquals("value", response.headers.header("Echo-Header"))
      assertArrayEquals(binary, response.body)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `put sends its body too`() {
    val server = server { exchange -> respond(exchange, 200, exchange.requestBody.readBytes()) }
    try {
      val body = byteArrayOf(9, 8, 7)
      val response = responder.respond(request("PUT", server.url("/put"), body = body))
      assertArrayEquals(body, response.body)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `error status body comes from the error stream`() {
    val server = server { exchange -> respond(exchange, 404, "missing".toByteArray()) }
    try {
      val response = responder.respond(request("GET", server.url("/gone")))
      assertEquals(404, response.statusCode)
      assertArrayEquals("missing".toByteArray(), response.body)
      assertNull(response.error)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `204 has no body`() {
    val server = server { exchange -> exchange.sendResponseHeaders(204, -1) }
    try {
      val response = responder.respond(request("GET", server.url("/none")))
      assertEquals(204, response.statusCode)
      assertEquals(0, response.body?.size ?: 0)
      assertNull(response.error)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `repeated response headers are joined with a comma`() {
    val server = server { exchange ->
      exchange.responseHeaders.add("X-Multi", "a")
      exchange.responseHeaders.add("X-Multi", "b")
      respond(exchange, 200, ByteArray(0))
    }
    try {
      val response = responder.respond(request("GET", server.url("/multi")))
      val multi = response.headers.header("X-Multi")!!
      assertEquals(setOf("a", "b"), multi.split(",").map { it.trim() }.toSet())
      assertTrue(multi, multi.contains(", "))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `malformed url fails with the exception class only`() {
    val response = responder.respond(request("GET", "not a url"))
    assertEquals(0, response.statusCode)
    assertEquals("MalformedURLException", response.error)
    assertFalse(response.error!!.contains("not a url"))
  }

  @Test
  fun `non-http url is a sanitized transport failure`() {
    val response = responder.respond(request("GET", "file:/tmp/karoo-ext-secret"))
    assertEquals(0, response.statusCode)
    assertEquals("UnsupportedProtocol", response.error)
    assertFalse(response.error!!.contains("karoo-ext-secret"))
  }

  @Test
  fun `io failure reports the class name and leaks nothing`() {
    val closedPort = ServerSocket(0).use { it.localPort }
    val secret = "Bearer very-secret-token"
    val response =
      responder.respond(
        request(
          "GET",
          "http://127.0.0.1:$closedPort/private",
          mapOf("Authorization" to secret),
        )
      )
    assertEquals(0, response.statusCode)
    val error = response.error!!
    assertTrue(error, error.matches(Regex("[A-Za-z]+Exception")))
    assertFalse(error.contains(secret))
    assertFalse(error.contains(closedPort.toString()))
    assertFalse(error.contains("private"))
  }
}
