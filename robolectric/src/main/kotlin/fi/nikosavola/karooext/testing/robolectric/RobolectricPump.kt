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

  /**
   * Advances the Robolectric main looper by [duration], running work due in that window.
   *
   * Promises the Robolectric main looper and its clock only. It does not virtualize the Java wall
   * clock that SDK/library classes read by default: `System.currentTimeMillis`/`nanoTime` in a
   * class Robolectric does not instrument stays real, so advancing this pump need not move an SDK
   * throttle window. It also does not advance a coroutines TestCoroutineScheduler or touch
   * Dispatchers.IO.
   */
  fun advanceBy(duration: kotlin.time.Duration) {
    require(duration.isFinite()) { "duration must be finite, was $duration" }
    require(!duration.isNegative()) { "duration must be nonnegative, was $duration" }
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofNanos(duration.inWholeNanoseconds))
  }
}
