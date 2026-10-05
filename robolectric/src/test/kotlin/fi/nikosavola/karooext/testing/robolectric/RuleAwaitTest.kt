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
import io.hammerhead.karooext.models.TurnScreenOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
