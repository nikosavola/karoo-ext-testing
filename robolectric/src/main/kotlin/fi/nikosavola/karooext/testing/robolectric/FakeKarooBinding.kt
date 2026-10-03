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
}
