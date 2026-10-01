package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TIMEOUT_MS = 15_000
private const val HTTP_ERROR_MIN = 400

/**
 * Forwards requests to a real endpoint, standing in for the Karoo's HTTP proxy. The URL and the
 * headers carry credentials, so a failure reports only the exception class name, never the request.
 */
class LiveResponder : HttpResponder {
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete =
    try {
      val connection = URL(request.url).openConnection() as HttpURLConnection
      connection.requestMethod = request.method
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      request.headers.forEach(connection::setRequestProperty)
      try {
        val status = connection.responseCode
        val stream =
          if (status >= HTTP_ERROR_MIN) connection.errorStream else connection.inputStream
        val body = stream?.use { it.readBytes() }
        HttpResponseState.Complete(status, emptyMap(), body, null)
      } finally {
        connection.disconnect()
      }
    } catch (e: IOException) {
      HttpResponses.failure(e.javaClass.simpleName).response
    }
}
