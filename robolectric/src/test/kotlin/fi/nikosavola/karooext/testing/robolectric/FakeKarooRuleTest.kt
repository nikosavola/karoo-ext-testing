package fi.nikosavola.karooext.testing.robolectric

import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import fi.nikosavola.karooext.testing.CapturingHandler
import fi.nikosavola.karooext.testing.awaitValue
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.HardwareType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FakeKarooRuleTest {
  @get:Rule val karoo = FakeKarooRule()

  @Test
  fun `binding points KarooSystemService at the fake`() {
    val service = KarooSystemService(karoo.app)
    service.connect()
    awaitValue(timeoutMs = 5_000) {
      RobolectricPump.pumpMainLooper()
      if (service.connected) true else null
    }
    assertEquals(HardwareType.KAROO, service.hardwareType)
    service.disconnect()
  }

  @Test
  fun `pump advances the main looper without throwing`() {
    RobolectricPump.pumpMainLooper()
  }

  @Test
  fun `advanceBy runs main looper work due in the window`() {
    var ran = false
    Handler(Looper.getMainLooper()).postDelayed({ ran = true }, 1_000)
    RobolectricPump.pumpMainLooper()
    assertFalse(ran)
    RobolectricPump.advanceBy(1.seconds)
    assertTrue(ran)
  }

  @Test
  fun `advanceBy rejects negative and infinite durations`() {
    assertThrows(IllegalArgumentException::class.java) { RobolectricPump.advanceBy((-1).seconds) }
    assertThrows(IllegalArgumentException::class.java) {
      RobolectricPump.advanceBy(Duration.INFINITE)
    }
  }

  @Test
  @Config(sdk = [35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
  fun `view emitter throttles frames closer than a second apart`() {
    val handler = CapturingHandler()
    val emitter = ViewEmitter(karoo.app.packageName, handler)
    val views = RemoteViews(karoo.app.packageName, android.R.layout.simple_list_item_1)
    // Move past the emitter's 900ms window first, whatever the shadowed clock starts at.
    RobolectricPump.advanceBy(1.seconds)
    emitter.updateView(views)
    emitter.updateView(views)
    assertEquals(1, handler.views.size)
    RobolectricPump.advanceBy(1.seconds)
    emitter.updateView(views)
    assertEquals(2, handler.views.size)
  }

  @Test
  fun `close destroys tracked services and cancels their handlers`() {
    LifecycleExtension.reset()
    val host = karoo.host<LifecycleExtension>()
    val scan = host.startScan()
    scan.await(5_000) { true }
    karoo.close()
    assertTrue(LifecycleExtension.cancelled.contains("scan"))
    assertEquals(1, LifecycleExtension.destroyed)
  }

  @Test
  fun `a service that cannot bind is destroyed and not retained`() {
    ThrowingBindService.reset()
    assertThrows(RuntimeException::class.java) { karoo.host<ThrowingBindService>() }
    assertEquals(1, ThrowingBindService.destroyed)
  }

  @Test
  fun `a service failing onCreate is destroyed and not retained`() {
    ThrowingCreateService.reset()
    assertThrows(IllegalStateException::class.java) { karoo.host<ThrowingCreateService>() }
    assertEquals(1, ThrowingCreateService.destroyed)
  }

  @Test
  fun `a closed rule rejects reuse`() {
    karoo.close()
    val statement =
      object : Statement() {
        override fun evaluate() = Unit
      }
    assertThrows(IllegalStateException::class.java) {
      karoo.apply(statement, Description.EMPTY).evaluate()
    }
    assertThrows(IllegalStateException::class.java) { karoo.host<LifecycleExtension>() }
  }
}
