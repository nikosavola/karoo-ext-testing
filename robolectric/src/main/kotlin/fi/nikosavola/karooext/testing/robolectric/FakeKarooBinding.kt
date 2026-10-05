package fi.nikosavola.karooext.testing.robolectric

import android.app.Application
import android.content.ComponentName
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.KAROO_SYSTEM_PACKAGE
import fi.nikosavola.karooext.testing.KAROO_SYSTEM_SERVICE
import org.robolectric.Shadows.shadowOf

/**
 * Points KarooSystemService's next bind at the given `system`, as if the Karoo system app were
 * installed.
 *
 * Call it before the service under test binds. A later install replaces the stored component, so
 * each test can point the same application at its own `system`.
 */
object FakeKarooBinding {
  private var early: Pair<Application, FakeKarooSystem>? = null

  /**
   * Registers [system] as the binder for the component the SDK binds to, using Robolectric's shadow
   * of [application]. Nothing is actually installed: the shadow only influences binds made through
   * this application. The caller owns the [system] and must close it.
   *
   * @param application the application whose next bind should reach the fake.
   * @param system the fake to hand back from that bind.
   */
  fun install(application: Application, system: FakeKarooSystem) {
    shadowOf(application)
      .setComponentNameAndServiceForBindService(
        ComponentName(KAROO_SYSTEM_PACKAGE, KAROO_SYSTEM_SERVICE),
        system,
      )
  }

  /**
   * For apps that create `KarooSystemService` and connect in `Application.onCreate`, often through
   * a DI container: that bind happens before any JUnit rule runs, and Robolectric then hands the
   * SDK a null component. Call this from a test `Application` before `super.onCreate()` and
   * register it with `@Config(application = ...)`. The next [FakeKarooRule] adopts and closes the
   * returned system.
   *
   * ```kotlin
   * class TestApp : Application() {
   *   override fun onCreate() {
   *     FakeKarooBinding.installEarly(this)
   *     super.onCreate()
   *     startKoin { androidContext(this@TestApp); modules(appModule) }
   *   }
   * }
   * ```
   */
  fun installEarly(application: Application): FakeKarooSystem {
    val system = FakeKarooSystem()
    install(application, system)
    synchronized(this) {
      early?.second?.close()
      early = application to system
    }
    return system
  }

  /**
   * Hands the system from [installEarly] to the rule of the same [application] once. One left over
   * from an earlier test's application is closed rather than adopted.
   */
  internal fun takeEarly(application: Application): FakeKarooSystem? =
    synchronized(this) {
      val waiting = early
      early = null
      if (waiting?.first === application) {
        waiting.second
      } else {
        waiting?.second?.close()
        null
      }
    }
}
