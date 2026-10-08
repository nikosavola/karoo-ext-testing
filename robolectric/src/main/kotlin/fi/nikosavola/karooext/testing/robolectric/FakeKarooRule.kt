package fi.nikosavola.karooext.testing.robolectric

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.AwaitTimeoutException
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.FakeKarooSystem
import io.hammerhead.karooext.aidl.IKarooExtension
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.KarooEventParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.rules.ExternalResource
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController

private const val RADIO_SETTLE_MS = 500L

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
 * A failing test gets the fake's [FakeKarooSystem.describe] snapshot, plus what each host's
 * recorders received, attached as a suppressed exception.
 *
 * @property system the fake the bound service talks to; closed by [close]. Defaults to the one from
 *   [FakeKarooBinding.installEarly] when a test application installed one, else a fresh one.
 * @property requireReleasedRadios when true, [close] fails the test if the extension still holds a
 *   Bluetooth or ANT claim once its services are destroyed, see
 *   [FakeKarooSystem.assertRadiosReleased]. Off by default.
 */
@Suppress("TooManyFunctions")
class FakeKarooRule(
  val system: FakeKarooSystem =
    FakeKarooBinding.takeEarly(ApplicationProvider.getApplicationContext()) ?: FakeKarooSystem(),
  val requireReleasedRadios: Boolean = false,
) : ExternalResource() {
  /** Application the rule binds through, resolved from the Robolectric test application. */
  val app: Application = ApplicationProvider.getApplicationContext()

  private val hosts = CopyOnWriteArrayList<FakeKarooHost>()
  private val hostsByClass = ConcurrentHashMap<Class<out Service>, FakeKarooHost>()
  private val consumersBefore = ConcurrentHashMap<Class<out Service>, Set<String>>()
  private val controllersByClass =
    ConcurrentHashMap<Class<out Service>, ServiceController<out Service>>()
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
            failure.addSuppressed(FakeKarooState(describe()))
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

  /**
   * Waits for the first dispatched effect of [T] matching [predicate], pumping like [awaitValue].
   */
  inline fun <reified T : KarooEffect> awaitEffect(
    timeoutMs: Long = 20_000,
    crossinline predicate: (T) -> Boolean = { true },
  ): T = awaitValue(timeoutMs) { system.effectsOf<T>().firstOrNull { predicate(it) } }

  /**
   * Waits until the extension streams every one of [dataTypeIds]. Publish after this when the
   * extension needs every point, since only the latest one is replayed to a late consumer. It only
   * proves the consumers exist: when the extension combines a stream with others, wait for all of
   * the inputs, because `combine` drops points that arrive before each input has a value.
   */
  fun awaitStreamConsumer(vararg dataTypeIds: String, timeoutMs: Long = 20_000) {
    awaitValue(timeoutMs) { true.takeIf { dataTypeIds.all(system::hasStreamConsumer) } }
  }

  /**
   * Fails if a [T] effect has been dispatched, or arrives within [forMs]. Use it for "does not
   * alert" assertions, since a negative result can only be shown by waiting. The wait is real
   * elapsed time, pumping the main looper.
   *
   * @throws AssertionError if a matching effect is found.
   */
  inline fun <reified T : KarooEffect> assertNoEffect(
    forMs: Long = 500,
    crossinline predicate: (T) -> Boolean = { true },
  ) {
    val seen =
      try {
        awaitEffect<T>(forMs, predicate)
      } catch (_: AwaitTimeoutException) {
        null
      }
    if (seen != null) throw AssertionError("Expected no ${T::class.java.simpleName}, got $seen")
  }

  /**
   * Waits until the extension listens for [params], for example `RideState.Params`, pumping like
   * [awaitValue]. Publish after this when the extension needs to see the value live.
   */
  fun awaitConsumer(params: KarooEventParams, timeoutMs: Long = 20_000) {
    awaitValue(timeoutMs) { true.takeIf { system.hasConsumer(params) } }
  }

  private fun describe(): String =
    hosts
      .map { it.describe() }
      .filter { it.isNotEmpty() }
      .fold(system.describe()) { text, host ->
        "$text\n  host recorders:\n${host.prependIndent("    ")}"
      }

  /** Carries the fake's state on a failed test; not thrown on its own. */
  class FakeKarooState(state: String) : Exception(state) {
    /** Skips the stack trace, which would only point at the rule. */
    override fun fillInStackTrace(): Throwable = this
  }

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
    if (requireReleasedRadios) {
      try {
        // A release sent from a background coroutine during onDestroy may still be in flight.
        try {
          awaitValue(RADIO_SETTLE_MS) { true.takeIf { system.radiosBalanced() } }
        } catch (_: AwaitTimeoutException) {
          // assertRadiosReleased reports what is left.
        }
        system.assertRadiosReleased()
      } catch (t: Throwable) {
        failure = failure.combine(t)
      }
    }
    try {
      system.close()
    } catch (t: Throwable) {
      failure = failure.combine(t)
    }
    hosts.clear()
    hostsByClass.clear()
    controllersByClass.clear()
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
    check(controllersByClass[extension] == null) {
      "${extension.simpleName} is already running; reuse its host, or call restart() for a new " +
        "instance. Android keeps one service instance per class, and a second one would replace " +
        "singletons the first set up."
    }
    // Assign and track the controller before create(), so an onCreate failure still leaves it to
    // destroy in the cleanup below.
    var controller: ServiceController<out Service>? = null
    try {
      consumersBefore[extension] = system.consumerIds.toSet()
      val built = Robolectric.buildService(extension)
      controller = built
      controllers += built
      built.create()
      RobolectricPump.pumpMainLooper()
      val binder = built.get().onBind(Intent())
      RobolectricPump.pumpMainLooper()
      val host = FakeKarooHost(IKarooExtension.Stub.asInterface(binder), RobolectricPump.invoke())
      hosts += host
      hostsByClass[extension] = host
      controllersByClass[extension] = built
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

  /**
   * Stops [extension] and starts a fresh instance, leaving the fake system and its sticky state in
   * place, as when the Karoo kills the extension process mid-ride. The old host is closed and its
   * service destroyed, and every consumer registered since that service started is dropped, as a
   * dead process would drop them, before the new one is created. With several services running,
   * consumers registered by the others in that time are dropped too. Fails if [extension] is not
   * running.
   */
  fun restart(extension: Class<out Service>): FakeKarooHost {
    checkRuleOpen()
    val old = checkNotNull(hostsByClass[extension]) { "${extension.simpleName} is not running" }
    old.close()
    controllersByClass.remove(extension)?.let {
      controllers -= it
      it.destroy()
    }
    RobolectricPump.pumpMainLooper()
    // A dead process takes its binder consumers with it; an extension that never unregistered
    // them would otherwise keep running callbacks of the destroyed instance.
    val before = consumersBefore[extension].orEmpty()
    (system.consumerIds - before).forEach(system::removeEventConsumer)
    return host(extension)
  }

  /** Reified convenience overload of [restart], for `karoo.restart<MyExtension>()`. */
  inline fun <reified T : Service> restart(): FakeKarooHost = restart(T::class.java)

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
