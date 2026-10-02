package fi.nikosavola.karooext.testing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

// awaitValue is instrumented here so a wall-clock deadline would be caught by the clock-jump test.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], instrumentedPackages = ["fi.nikosavola.karooext.testing"])
class AwaitValueTest {
  @Test
  fun `returns an immediate result without sleeping`() {
    assertEquals("value", awaitValue(5.seconds) { "value" })
  }

  @Test
  fun `waits for the third probe`() {
    var calls = 0
    val result =
      awaitValue(5.seconds) {
        calls++
        if (calls >= 3) "value" else null
      }
    assertEquals("value", result)
    assertTrue("probe calls: $calls", calls >= 3)
  }

  @Test
  fun `zero timeout takes an immediate result`() {
    var probes = 0
    assertEquals(
      "value",
      awaitValue(0) {
        probes++
        "value"
      },
    )
    assertEquals(1, probes)
  }

  @Test
  fun `zero timeout fails without sleeping when the probe is empty`() {
    val failure =
      assertThrows(IllegalStateException::class.java) { awaitValue<String>(Duration.ZERO) { null } }
    assertTrue(failure.message!!.contains("Timed out"))
  }

  @Test
  fun `negative timeouts are rejected`() {
    assertThrows(IllegalArgumentException::class.java) { awaitValue<Long>(-1) { null } }
    assertThrows(IllegalArgumentException::class.java) { awaitValue<Long>((-1).seconds) { null } }
  }

  @Test
  fun `non-finite timeout is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      awaitValue<String>(Duration.INFINITE) { "value" }
    }
  }

  @Test
  fun `sub-millisecond timeout is kept`() {
    assertEquals("value", awaitValue(500.microseconds) { "value" })
  }

  @Test
  fun `an idle failure propagates before the probe runs`() {
    val boom = IllegalStateException("idle failed")
    var probes = 0
    val thrown =
      assertThrows(IllegalStateException::class.java) {
        awaitValue<String>(1.seconds, idle = { throw boom }) {
          probes++
          "value"
        }
      }
    assertSame(boom, thrown)
    assertEquals(0, probes)
  }

  @Test
  fun `a probe failure propagates`() {
    val boom = IllegalArgumentException("probe failed")
    val thrown =
      assertThrows(IllegalArgumentException::class.java) {
        awaitValue<Any>(1.seconds) { throw boom }
      }
    assertSame(boom, thrown)
  }

  @Test
  fun `times out for an empty pump`() {
    // Robolectric's virtual clock does not advance on its own, so guard against an unbounded hang.
    val failure =
      watchdog(5_000) {
        assertThrows(IllegalStateException::class.java) {
          awaitValue<String>(150.milliseconds) { null }
        }
      }
    assertTrue(failure.message!!.contains("Timed out"))
  }

  @Test
  fun `positive timeout ignores wall clock changes`() {
    var probes = 0
    val result =
      awaitValue(
        5.seconds,
        idle = { ShadowSystemClock.advanceBy(30, TimeUnit.SECONDS) },
      ) {
        probes++
        if (probes >= 3) "value" else null
      }
    assertEquals("value", result)
  }

  private fun <T> watchdog(millis: Long, block: () -> T): T {
    val done = CountDownLatch(1)
    val result = AtomicReference<Result<T>>()
    val thread = Thread {
      result.set(runCatching(block))
      done.countDown()
    }
    thread.isDaemon = true
    thread.start()
    if (!done.await(millis, TimeUnit.MILLISECONDS)) {
      thread.interrupt()
      throw AssertionError("block did not finish within ${millis}ms")
    }
    return result.get().getOrThrow()
  }
}
