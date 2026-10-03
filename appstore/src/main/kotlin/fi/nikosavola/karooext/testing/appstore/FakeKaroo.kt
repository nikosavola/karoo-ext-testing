package fi.nikosavola.karooext.testing.appstore

import fi.nikosavola.karooext.testing.FakeKarooSystem

/**
 * Process-wide fake so the bound service and the instrumentation test, which run in the same
 * process, drive the same instance.
 *
 * The shared instance exists only within one process. An extension running in its own process
 * reaches the fake through the binder handed back by
 * [io.hammerhead.appstore.service.AppStoreService], not through this field. Tests call
 * [FakeKarooSystem.reset] around each case and never [FakeKarooSystem.close] it, since the service
 * may still be bound.
 */
object FakeKaroo {
  /** The single fake system the bound service returns from `onBind`. Never close it. */
  val system = FakeKarooSystem()
}
