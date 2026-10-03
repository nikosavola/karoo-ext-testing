package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse

/**
 * Routes a request to the first responder whose rule matches, in insertion order, and answers 404
 * when none do. A drop-in replacement for the hand-rolled routing the app-specific fixture
 * responders used to have.
 *
 * A rule's predicate and its responder run directly on the calling thread; a predicate exception
 * propagates to the caller, while a responder exception is caught by [FakeKarooSystem] and turned
 * into a status 0 error. The built responder holds an immutable copy of its rules.
 */
class RoutingResponder private constructor(private val rules: List<Rule>) : HttpResponder {
  private class Rule(
    val matches: (OnHttpResponse.MakeHttpRequest) -> Boolean,
    val responder: HttpResponder,
  )

  /** Answers with the first matching rule's response, or a 404 when no rule matches. */
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete =
    rules.firstOrNull { it.matches(request) }?.responder?.respond(request)
      ?: HttpResponses.notFound().respond(request)

  /** Builds rules in order; the first match wins, so put specific rules before broad ones. */
  class Builder internal constructor() {
    private val rules = mutableListOf<Rule>()

    /**
     * Matches when the full request URL contains [substring]. This is a raw substring test, not a
     * parsed path, so query strings and host names are matched too.
     */
    fun path(substring: String, responder: HttpResponder): Builder = apply {
      rules += Rule({ substring in it.url }, responder)
    }

    /** Matches when [predicate] returns true for the request URL. */
    fun url(predicate: (String) -> Boolean, responder: HttpResponder): Builder = apply {
      rules += Rule({ predicate(it.url) }, responder)
    }

    /** Matches on the whole request, e.g. a method or a header such as Authorization. */
    fun request(
      predicate: (OnHttpResponse.MakeHttpRequest) -> Boolean,
      responder: HttpResponder,
    ): Builder = apply { rules += Rule(predicate, responder) }

    /** Freezes the rules added so far; later additions do not affect the returned responder. */
    fun build(): RoutingResponder = RoutingResponder(rules.toList())
  }

  /** Builder entry point for a routing responder. */
  companion object {
    /**
     * Builds a responder from [configure], e.g. `RoutingResponder.build { path(...) }`.
     *
     * @sample fi.nikosavola.karooext.testing.samples.routedHttpResponses
     */
    fun build(configure: Builder.() -> Unit): RoutingResponder = Builder().apply(configure).build()
  }
}
