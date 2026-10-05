package fi.nikosavola.karooext.testing.robolectric

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.FakeKarooSystem
import io.hammerhead.karooext.aidl.IKarooExtension
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.rules.ExternalResource
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController

/**
 * Binds the extension's KarooSystemService to an in-process `system` and tears everything down
 * after each test: hosts close first (stopping their sessions), their services are destroyed, and
 * then `system` closes. Generic: it knows nothing about the extension under test. Use with
 * `RobolectricTestRunner` on JUnit 4, since [app] is resolved when the rule field initializes.
 *
 * [host] starts a service through `create` and `onBind`; `onStartCommand` is never called. [close]
 * attempts every teardown step and rethrows the first failure with the later ones suppressed. It is
 * not itself `Closeable`; `after` calls it, and a test may call it early because it is idempotent.
 * A rule instance is single-use.
 *
 * A failing test gets the fake's [FakeKarooSystem.describe] snapshot attached as a suppressed
 * exception.
 *
 * @property system the fake the bound service talks to; closed by [close]. Defaults to the one from
 *   [FakeKarooBinding.installEarly] when a test application installed one, else a fresh one.
 */
class FakeKarooRule(
  val system: FakeKarooSystem =
    FakeKarooBinding.takeEarly(ApplicationProvider.getApplicationContext()) ?: FakeKarooSystem()
) : ExternalResource() {
  /** Application the rule binds through, resolved from the Robolectric test application. */
  val app: Application = ApplicationProvider.getApplicationContext()

  private val hosts = CopyOnWriteArrayList<FakeKarooHost>()
  private val controllers = CopyOnWriteArrayList<ServiceController<out Service>>()

  @Volatile private var closed = false

  /**
   * Installs the binding for `system` and pumps the looper. Fails if the rule was already closed.
   */
  override fun before() {
    checkRuleOpen()
    FakeKarooBinding.install(app, system)
    RobolectricPump.pumpMainLooper()
  }

  /** Tears down through [close]. */
  override fun after() = close()

  /**
   * Attaches [FakeKarooSystem.describe] to a failing test, since timeouts alone rarely say why. The
   * snapshot is taken before the rule's teardown clears the consumers, but after the test's own
   * `@After` methods, so state those release is already gone.
   */
  @Suppress("TooGenericExceptionCaught")
  override fun apply(base: Statement, description: Description): Statement {
    val described =
      object : Statement() {
        override fun evaluate() {
          try {
            base.evaluate()
          } catch (failure: Throwable) {
            failure.addSuppressed(FakeKarooState(system.describe()))
            throw failure
          }
        }
      }
    return super.apply(described, description)
  }

  /**
   * Polls [probe] until it returns non-null, pumping the main looper before each try. Use it
   * instead of a bare `awaitValue` for state outside a recorder, such as what the extension
   * persisted: without pumping, the SDK's connect callback never runs.
   *
   * @throws IllegalStateException if [probe] is still null after [timeoutMs].
   */
  fun <T : Any> awaitValue(timeoutMs: Long = 20_000, probe: () -> T?): T =
    fi.nikosavola.karooext.testing.awaitValue(timeoutMs, RobolectricPump::pumpMainLooper, probe)

  /** Carries the fake's state on a failed test; not thrown on its own. */
  class FakeKarooState(state: String) : Exception(state, null, false, false)

  /** Closes every host, destroys its service and closes `system`, attempting all of them. */
  @Suppress("TooGenericExceptionCaught")
  fun close() {
    if (closed) return
    closed = true
    var failure: Throwable? = null
    hosts.forEach { host ->
      try {
        host.close()
      } catch (t: Throwable) {
        failure = failure.combine(t)
      }
    }
    controllers.forEach { controller ->
      try {
        controller.destroy()
      } catch (t: Throwable) {
        failure = failure.combine(t)
      }
    }
    try {
      RobolectricPump.pumpMainLooper()
    } catch (t: Throwable) {
      failure = failure.combine(t)
    }
    try {
      system.close()
    } catch (t: Throwable) {
      failure = failure.combine(t)
    }
    hosts.clear()
    controllers.clear()
    failure?.let { throw it }
  }

  /**
   * Starts [extension] like the Karoo ride app and connects to it over the binder. The service is
   * created and bound but not started, so `onStartCommand` never runs. The returned host is tracked
   * and closed by [close]; a failure during create or bind destroys the service before rethrowing.
   */
  @Suppress("RethrowCaughtException", "TooGenericExceptionCaught")
  fun host(extension: Class<out Service>): FakeKarooHost {
    checkRuleOpen()
    // Assign and track the controller before create(), so an onCreate failure still leaves it to
    // destroy in the cleanup below.
    var controller: ServiceController<out Service>? = null
    try {
      val built = Robolectric.buildService(extension)
      controller = built
      controllers += built
      built.create()
      RobolectricPump.pumpMainLooper()
      val binder = built.get().onBind(Intent())
      RobolectricPump.pumpMainLooper()
      val host = FakeKarooHost(IKarooExtension.Stub.asInterface(binder), RobolectricPump.invoke())
      hosts += host
      return host
    } catch (t: Throwable) {
      controller?.let { failed ->
        controllers -= failed
        try {
          failed.destroy()
        } catch (cleanup: Throwable) {
          t.suppress(cleanup)
        }
      }
      throw t
    }
  }

  /** Reified convenience overload of [host] for `karoo.host<MyExtension>()`. */
  inline fun <reified T : Service> host(): FakeKarooHost = host(T::class.java)

  private fun checkRuleOpen() = check(!closed) { "FakeKarooRule is closed; a rule is single-use" }

  private fun Throwable?.combine(other: Throwable): Throwable =
    if (this == null) {
      other
    } else if (this === other) {
      this
    } else {
      addSuppressed(other)
      this
    }

  private fun Throwable.suppress(other: Throwable) {
    if (this !== other) addSuppressed(other)
  }
}
