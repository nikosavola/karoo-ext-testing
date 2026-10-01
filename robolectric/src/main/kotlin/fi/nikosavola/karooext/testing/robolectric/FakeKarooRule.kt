package fi.nikosavola.karooext.testing.robolectric

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.FakeKarooSystem
import io.hammerhead.karooext.aidl.IKarooExtension
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric

/**
 * Binds the extension's KarooSystemService to an in-process [system] and resets it after each test.
 * Generic: it knows nothing about the extension under test. Use with Robolectric.
 */
class FakeKarooRule(val system: FakeKarooSystem = FakeKarooSystem()) : ExternalResource() {
  val app: Application = ApplicationProvider.getApplicationContext()

  override fun before() {
    FakeKarooBinding.install(app, system)
    RobolectricPump.pumpMainLooper()
  }

  override fun after() {
    system.reset()
  }

  /** Starts [extension] like the Karoo ride app and connects to it over the binder. */
  fun host(extension: Class<out Service>): FakeKarooHost {
    val binder = Robolectric.buildService(extension).create().get().onBind(Intent())
    RobolectricPump.pumpMainLooper()
    return FakeKarooHost(IKarooExtension.Stub.asInterface(binder), RobolectricPump.invoke())
  }

  inline fun <reified T : Service> host(): FakeKarooHost = host(T::class.java)
}
