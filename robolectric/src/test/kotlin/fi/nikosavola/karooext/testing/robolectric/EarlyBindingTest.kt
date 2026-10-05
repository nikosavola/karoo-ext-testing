package fi.nikosavola.karooext.testing.robolectric

import android.app.Application
import fi.nikosavola.karooext.testing.awaitValue
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Connects in onCreate like a DI singleton would, before any rule exists. */
class EarlyBindingApp : Application() {
  lateinit var early: fi.nikosavola.karooext.testing.FakeKarooSystem
  lateinit var service: KarooSystemService
  val rideStates = CopyOnWriteArrayList<RideState>()

  override fun onCreate() {
    early = FakeKarooBinding.installEarly(this)
    super.onCreate()
    service = KarooSystemService(this)
    service.connect { connected ->
      if (connected) service.addConsumer { state: RideState -> rideStates += state }
    }
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = EarlyBindingApp::class)
class EarlyBindingTest {
  @get:Rule val karoo = FakeKarooRule()

  private val app
    get() = karoo.app as EarlyBindingApp

  @Test
  fun `rule adopts the system the application bound to`() {
    assertSame(app.early, karoo.system)
    awaitValue(timeoutMs = 5_000) {
      RobolectricPump.pumpMainLooper()
      app.rideStates.firstOrNull()
    }
    karoo.system.setRideState(RideState.Recording)
    RobolectricPump.pumpMainLooper()
    assertEquals(RideState.Recording, app.rideStates.last())
  }

  @Test
  fun `each test gets its own early system`() {
    assertSame(app.early, karoo.system)
  }

  @Test
  fun `a system left for another application is closed, not adopted`() {
    val stale = FakeKarooBinding.installEarly(karoo.app)
    assertNull(FakeKarooBinding.takeEarly(Application()))
    assertThrows(IllegalStateException::class.java) {
      stale.addEventConsumer(
        "x",
        android.os.Bundle(),
        fi.nikosavola.karooext.testing.CapturingHandler(),
      )
    }
  }
}
