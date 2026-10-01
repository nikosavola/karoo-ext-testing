package fi.nikosavola.karooext.testing.appstore

import fi.nikosavola.karooext.testing.FakeKarooSystem

/**
 * Process-wide fake so the bound service and the instrumentation test, which run in the same
 * process, drive the same instance.
 */
object FakeKaroo {
  val system = FakeKarooSystem()
}
