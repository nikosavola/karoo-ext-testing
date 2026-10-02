package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands out [answers] in order, one per request, from any thread. Built by
 * [HttpResponses.sequence]. Exhaustion throws instead of repeating the last answer, so a test that
 * polls more than expected fails instead of silently serving stale data.
 */
class SequenceResponder internal constructor(private val answers: List<HttpAnswer>) :
  HttpResponder {
  private val next = AtomicInteger()

  /** Answers not handed out yet, counting one still being consumed. */
  val remainingResponses: Int
    get() = (answers.size - next.get()).coerceAtLeast(0)

  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete {
    val index = next.getAndIncrement()
    check(index < answers.size) { "Sequence exhausted after ${answers.size} responses" }
    return answers[index].respond(request)
  }
}
