package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TIMEOUT_MS = 15_000
private const val HTTP_ERROR_MIN = 400
private const val UNSUPPORTED_PROTOCOL = "UnsupportedProtocol"

/**
 * Forwards requests to a real endpoint, standing in for the Karoo's HTTP proxy. The URL and the
 * headers carry credentials, so a failure reports only the exception class name, never the request.
 *
 * The SDK response header map is single-valued, so repeated response headers are joined with ", ".
 * That is lossy and not a standards guarantee: a Set-Cookie pair cannot round-trip. Field order is
 * whatever the JDK provides, and the null status-line key is dropped.
 */
class LiveResponder : HttpResponder {
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete {
    var connection: HttpURLConnection? = null
    return try {
      val opened = URL(request.url).openConnection()
      if (opened !is HttpURLConnection) {
        // file:, ftp: and other non-HTTP protocols hand back a different URLConnection.
        return HttpResponses.failure(UNSUPPORTED_PROTOCOL).response
      }
      connection = opened
      opened.requestMethod = request.method
      opened.connectTimeout = TIMEOUT_MS
      opened.readTimeout = TIMEOUT_MS
      request.headers.forEach(opened::setRequestProperty)
      request.body?.let { body ->
        opened.doOutput = true
        opened.setFixedLengthStreamingMode(body.size)
        opened.outputStream.use { it.write(body) }
      }
      val status = opened.responseCode
      val stream = if (status >= HTTP_ERROR_MIN) opened.errorStream else opened.inputStream
      val responseBody = stream?.use { it.readBytes() }
      HttpResponseState.Complete(status, responseHeaders(opened), responseBody, null)
    } catch (e: IOException) {
      // Class name only: the message of e.g. UnknownHostException would leak the URL.
      HttpResponses.failure(e.javaClass.simpleName).response
    } finally {
      connection?.disconnect()
    }
  }

  private fun responseHeaders(connection: HttpURLConnection): Map<String, String> = buildMap {
    connection.headerFields.forEach { (name, values) ->
      if (name != null) put(name, values.orEmpty().joinToString(", "))
    }
  }
}
