package fi.nikosavola.karooext.testing.robolectric

import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf

private val STEP: Duration = Duration.ofMillis(20)

/**
 * Advances the main looper a little, so work the extension posts to the main thread still runs
 * while a test thread blocks in a recorder. Pass [invoke] as a recorder's `pump`.
 */
object RobolectricPump {
  fun pumpMainLooper() = shadowOf(Looper.getMainLooper()).idleFor(STEP)

  operator fun invoke(): () -> Unit = ::pumpMainLooper
}
