package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse

/**
 * Routes a request to the first responder whose rule matches, and answers 404 when none do. A
 * drop-in replacement for the hand-rolled routing the app-specific fixture responders used to have.
 */
class RoutingResponder private constructor(private val rules: List<Rule>) : HttpResponder {
  private class Rule(
    val matches: (OnHttpResponse.MakeHttpRequest) -> Boolean,
    val responder: HttpResponder,
  )

  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete =
    rules.firstOrNull { it.matches(request) }?.responder?.respond(request)
      ?: HttpResponses.notFound().respond(request)

  class Builder internal constructor() {
    private val rules = mutableListOf<Rule>()

    /** Matches when the URL contains [substring], e.g. a path like "/tiles/". */
    fun path(substring: String, responder: HttpResponder): Builder = apply {
      rules += Rule({ substring in it.url }, responder)
    }

    fun url(predicate: (String) -> Boolean, responder: HttpResponder): Builder = apply {
      rules += Rule({ predicate(it.url) }, responder)
    }

    /** Matches on the whole request, e.g. a method or a header such as Authorization. */
    fun request(
      predicate: (OnHttpResponse.MakeHttpRequest) -> Boolean,
      responder: HttpResponder,
    ): Builder = apply { rules += Rule(predicate, responder) }

    fun build(): RoutingResponder = RoutingResponder(rules.toList())
  }

  companion object {
    fun build(configure: Builder.() -> Unit): RoutingResponder = Builder().apply(configure).build()
  }
}
