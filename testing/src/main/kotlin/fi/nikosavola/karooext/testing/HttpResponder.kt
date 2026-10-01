package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse

/** Answers one proxied HTTP request, the way the Karoo forwards it over Wi-Fi or the phone. */
fun interface HttpResponder {
  fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete
}

/** A fixed response that is also a responder, so the [HttpResponses] helpers fit anywhere. */
class HttpAnswer internal constructor(val response: HttpResponseState.Complete) : HttpResponder {
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete =
    response
}

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404

/** Common complete responses, so a responder body stays a one-liner. */
object HttpResponses {
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

  fun notFound(): HttpAnswer = status(HTTP_NOT_FOUND)
}
