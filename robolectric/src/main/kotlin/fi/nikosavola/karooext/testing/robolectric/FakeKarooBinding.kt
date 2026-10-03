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
  fun install(application: Application, system: FakeKarooSystem) {
    shadowOf(application)
      .setComponentNameAndServiceForBindService(
        ComponentName(KAROO_SYSTEM_PACKAGE, KAROO_SYSTEM_SERVICE),
        system,
      )
  }
}
