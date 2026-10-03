package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands out `answers` in order, one per request, from any thread. Built by
 * [HttpResponses.sequence]. Each answer is consumed on the call, never repeated, so
 * [remainingResponses] tells a test exactly how many requests the extension made.
 */
class SequenceResponder internal constructor(private val answers: List<HttpAnswer>) :
  HttpResponder {
  private val next = AtomicInteger()

  /** Answers not handed out yet; an answer already consumed is excluded. */
  val remainingResponses: Int
    get() = (answers.size - next.get()).coerceAtLeast(0)

  /**
   * Returns and consumes the next answer.
   *
   * @throws IllegalStateException once every answer has been consumed. Routed through
   *   [FakeKarooSystem] this is caught like any responder failure and becomes a status 0 error
   *   carrying the fully-qualified class name; a direct call propagates it.
   */
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete {
    val index = next.getAndIncrement()
    check(index < answers.size) { "Sequence exhausted after ${answers.size} responses" }
    return answers[index].respond(request)
  }
}
