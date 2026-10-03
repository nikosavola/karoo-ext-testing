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
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController

/**
 * Binds the extension's KarooSystemService to an in-process `system` and tears everything down
 * after each test: hosts close first (stopping their sessions), their services are destroyed, and
 * then `system` closes. Generic: it knows nothing about the extension under test. Use with
 * Robolectric.
 *
 * `after` calls [close], which is public and idempotent, so a test may tear down early and the rule
 * will not run it twice. A rule instance is single-use.
 */
class FakeKarooRule(val system: FakeKarooSystem = FakeKarooSystem()) : ExternalResource() {
  val app: Application = ApplicationProvider.getApplicationContext()

  private val hosts = CopyOnWriteArrayList<FakeKarooHost>()
  private val controllers = CopyOnWriteArrayList<ServiceController<out Service>>()

  @Volatile private var closed = false

  override fun before() {
    checkRuleOpen()
    FakeKarooBinding.install(app, system)
    RobolectricPump.pumpMainLooper()
  }

  override fun after() = close()

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

  /** Starts [extension] like the Karoo ride app and connects to it over the binder. */
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
