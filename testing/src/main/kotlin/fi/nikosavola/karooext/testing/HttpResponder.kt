package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse

/** Answers one proxied HTTP request, the way the Karoo forwards it over Wi-Fi or the phone. */
fun interface HttpResponder {
  /**
   * Produces the answer for [request]. Called on a fake HTTP thread, once per request. An exception
   * thrown here is caught by [FakeKarooSystem] and reported as a status 0 error carrying only the
   * fully-qualified class name, so a message that embeds the URL does not leak.
   */
  fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete
}

/**
 * A fixed response that is also a responder, so the [HttpResponses] helpers fit anywhere.
 *
 * @property response the fixed answer returned for every request.
 */
class HttpAnswer internal constructor(val response: HttpResponseState.Complete) : HttpResponder {
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete =
    response
}

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404

/** Common complete responses, so a responder body stays a one-liner. */
object HttpResponses {
  /** A 200 with [body] and optional [headers], and no error. */
  fun success(
    body: ByteArray,
    headers: Map<String, String> = emptyMap(),
  ): HttpAnswer = HttpAnswer(HttpResponseState.Complete(HTTP_OK, headers, body, null))

  /** A response with a status but no body, e.g. a 204 or a 403. */
  fun status(code: Int): HttpAnswer =
    HttpAnswer(HttpResponseState.Complete(code, emptyMap(), null, null))

  /** A transport failure, which the Karoo reports as status 0 with an error string. */
  fun failure(message: String): HttpAnswer =
    HttpAnswer(HttpResponseState.Complete(0, emptyMap(), null, message))

  /** A 404, the [FakeKarooSystem] default and the no-rule fallback of [RoutingResponder]. */
  fun notFound(): HttpAnswer = status(HTTP_NOT_FOUND)

  /**
   * Answers requests from [answers] in order, one per request, without repeating the last answer.
   *
   * A direct call past the end throws `IllegalStateException`. Routed through [FakeKarooSystem],
   * that exception is caught like any responder failure and delivered as a status 0
   * `HttpResponseState.Complete` whose error is the fully-qualified class name
   * (`java.lang.IllegalStateException`), so an over-eager poll does not fail the test by itself.
   * Keep the responder reference and assert request count/remainingResponses after the expected
   * requests.
   *
   * @sample fi.nikosavola.karooext.testing.samples.scriptedHttpResponses
   * @throws IllegalArgumentException if no [answers] are given.
   */
  fun sequence(vararg answers: HttpAnswer): SequenceResponder {
    require(answers.isNotEmpty()) { "sequence needs at least one answer" }
    return SequenceResponder(answers.toList())
  }
}
