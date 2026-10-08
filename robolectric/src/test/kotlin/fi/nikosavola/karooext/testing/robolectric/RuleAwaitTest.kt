package fi.nikosavola.karooext.testing.robolectric

import android.os.Handler
import android.os.Looper
import fi.nikosavola.karooext.testing.CapturingHandler
import fi.nikosavola.karooext.testing.KAROO_SYSTEM_PACKAGE
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.TurnScreenOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleAwaitTest {
  @get:Rule val karoo = FakeKarooRule()

  private fun dispatchLater(effect: KarooEffect) {
    Handler(Looper.getMainLooper()).post {
      karoo.system.dispatchEffect(effect.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))
    }
  }

  @Test
  fun `awaitEffect returns the first matching effect of the type`() {
    dispatchLater(TurnScreenOn)
    dispatchLater(PlayBeepPattern(listOf(PlayBeepPattern.Tone(1_000, 100))))
    dispatchLater(PlayBeepPattern(listOf(PlayBeepPattern.Tone(2_000, 100))))

    val beep =
      karoo.awaitEffect<PlayBeepPattern>(timeoutMs = 1_000) { it.tones[0].frequency == 2_000 }

    assertEquals(2_000, beep.tones.single().frequency)
  }

  @Test
  fun `effects before a mark are ignored and a wait can leave the clock alone`() {
    karoo.system.dispatchEffect(
      (TurnScreenOn as KarooEffect).bundleWithSerializable(KAROO_SYSTEM_PACKAGE)
    )
    val mark = karoo.system.effects.size

    karoo.assertNoEffect<TurnScreenOn>(forMs = 100, after = mark, pump = false)
    assertThrows(AssertionError::class.java) { karoo.assertNoEffect<TurnScreenOn>(forMs = 100) }
    assertEquals(TurnScreenOn, karoo.awaitEffect<TurnScreenOn>(timeoutMs = 100, pump = false))
  }

  @Test
  fun `assertNoEffect passes while nothing of the type arrives`() {
    dispatchLater(TurnScreenOn)

    karoo.assertNoEffect<PlayBeepPattern>(forMs = 200)
  }

  @Test
  fun `assertNoEffect fails when one arrives during the wait`() {
    dispatchLater(PlayBeepPattern(listOf(PlayBeepPattern.Tone(1_000, 100))))

    val error =
      assertThrows(AssertionError::class.java) {
        karoo.assertNoEffect<PlayBeepPattern>(forMs = 500)
      }

    assertTrue(error.message!!.contains("PlayBeepPattern"))
  }

  @Test
  fun `a second host for the same service is refused while its service is running`() {
    val first = karoo.host<LifecycleExtension>()

    val error = assertThrows(IllegalStateException::class.java) { karoo.host<LifecycleExtension>() }
    assertTrue(error.message!!.contains("already running"))
    assertTrue(error.message!!.contains("restart"))

    first.close()
    assertThrows(IllegalStateException::class.java) { karoo.host<LifecycleExtension>() }
    karoo.restart<LifecycleExtension>()
  }

  @Test
  fun `awaitConsumer returns once something listens`() {
    Handler(Looper.getMainLooper()).post {
      karoo.system.addEventConsumer(
        "late",
        (RideState.Params as KarooEventParams).bundleWithSerializable(KAROO_SYSTEM_PACKAGE),
        CapturingHandler(),
      )
    }

    karoo.awaitConsumer(RideState.Params, timeoutMs = 1_000)

    assertTrue(karoo.system.hasConsumer(RideState.Params))
  }

  @Test
  fun `awaitConsumer times out when nothing listens`() {
    assertThrows(IllegalStateException::class.java) {
      karoo.awaitConsumer(RideState.Params, timeoutMs = 100)
    }
  }

  @Test
  fun `restart replaces the service and keeps the fake system`() {
    LifecycleExtension.reset()
    val first = karoo.host<LifecycleExtension>()
    karoo.system.setRideState(RideState.Recording)

    val second = karoo.restart<LifecycleExtension>()

    assertTrue(first.isClosed)
    assertTrue(!second.isClosed)
    assertEquals(1, LifecycleExtension.destroyed)
    assertEquals(RideState.Recording, karoo.system.rideState)
    assertThrows(IllegalStateException::class.java) { karoo.restart<ThrowingBindService>() }
  }

  @Test
  fun `restart drops the consumers of the instance it destroyed`() {
    ListeningExtension.reset()
    karoo.host<ListeningExtension>()
    karoo.awaitConsumer(RideState.Params)

    karoo.restart<ListeningExtension>()
    karoo.awaitValue { true.takeIf { karoo.system.consumerIds.size == 1 } }
    karoo.system.setRideState(RideState.Recording)

    karoo.awaitValue { ListeningExtension.heard.takeIf { 2 in it } }
    assertTrue(1 !in ListeningExtension.heard.drop(1))
  }

  @Test
  fun `assertNoEffect does not hide a failure thrown on the main looper`() {
    Handler(Looper.getMainLooper()).post { error("extension broke") }

    val error =
      assertThrows(IllegalStateException::class.java) {
        karoo.assertNoEffect<PlayBeepPattern>(forMs = 300)
      }

    assertEquals("extension broke", error.message)
  }

  @Test
  fun `awaitEffect times out when nothing matches`() {
    dispatchLater(TurnScreenOn)
    assertThrows(IllegalStateException::class.java) {
      karoo.awaitEffect<PlayBeepPattern>(timeoutMs = 200)
    }
  }

  @Test
  fun `awaitStreamConsumer waits for every listed data type`() {
    Handler(Looper.getMainLooper()).post {
      karoo.system.addEventConsumer(
        "speed",
        (OnStreamState.StartStreaming("SPEED") as KarooEventParams).bundleWithSerializable(
          KAROO_SYSTEM_PACKAGE
        ),
        CapturingHandler(),
      )
    }
    karoo.awaitStreamConsumer("SPEED", timeoutMs = 1_000)
    assertThrows(IllegalStateException::class.java) {
      karoo.awaitStreamConsumer("SPEED", "POWER", timeoutMs = 200)
    }
  }
}
