package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.OnHttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SequenceResponderTest {
  private fun request() =
    OnHttpResponse.MakeHttpRequest("GET", "https://example.test/value", emptyMap(), null, false)

  @Test
  fun `hands out answers in order and reports remaining`() {
    val responder =
      HttpResponses.sequence(
        HttpResponses.success("one".toByteArray()),
        HttpResponses.status(204),
      )
    assertEquals(2, responder.remainingResponses)
    val first = responder.respond(request())
    assertEquals(200, first.statusCode)
    assertEquals("one", first.body!!.decodeToString())
    assertEquals(1, responder.remainingResponses)
    assertEquals(204, responder.respond(request()).statusCode)
    assertEquals(0, responder.remainingResponses)
  }

  @Test
  fun `exhaustion throws instead of repeating the last answer`() {
    val responder = HttpResponses.sequence(HttpResponses.success("only".toByteArray()))
    responder.respond(request())
    assertThrows(IllegalStateException::class.java) { responder.respond(request()) }
    assertEquals(0, responder.remainingResponses)
  }

  @Test
  fun `an empty sequence is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { HttpResponses.sequence() }
  }

  @Test
  fun `consumes safely from several threads`() {
    val bodies = (0 until 8).map { "answer-$it" }
    val answers = bodies.map { HttpResponses.success(it.toByteArray()) }.toTypedArray()
    val responder = HttpResponses.sequence(*answers)
    val start = CountDownLatch(1)
    val results = CopyOnWriteArrayList<String>()
    val pool = Executors.newFixedThreadPool(bodies.size)
    try {
      val futures =
        bodies.indices.map {
          pool.submit {
            start.await()
            results += responder.respond(request()).body?.decodeToString().orEmpty()
          }
        }
      start.countDown()
      futures.forEach { it.get() }
    } finally {
      pool.shutdownNow()
    }
    assertEquals(bodies.toSet(), results.toSet())
    assertEquals(0, responder.remainingResponses)
  }
}
