package fi.nikosavola.karooext.testing

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
  private class TestRecorder(pump: () -> Unit = {}) : Recorder<Int>("test", pump) {
    fun emit(item: Int) = record(item)

    fun failWith(message: String?) = fail(message)

    fun finish() = complete()
  }

  @Test
  fun `buffered match returns before a terminal state`() {
    val recorder = TestRecorder()
    recorder.emit(1)
    recorder.finish()
    assertEquals(1, recorder.await(1_000) { it == 1 })
  }

  @Test
  fun `after skips earlier matches`() {
    val recorder = TestRecorder()
    recorder.emit(1)
    recorder.emit(2)
    assertEquals(2, recorder.await(1_000, after = 1) { it == 2 })
    assertEquals(2, recorder.await(1_000, after = 1) { true })
  }

  @Test
  fun `an item recorded while waiting is returned`() {
    var recorder: TestRecorder? = null
    recorder = TestRecorder { recorder?.emit(7) }
    assertEquals(7, recorder.await(5_000) { it == 7 })
  }

  @Test
  fun `completion after a wait fails early without a match`() {
    var recorder: TestRecorder? = null
    val first = AtomicBoolean(true)
    recorder = TestRecorder { if (first.getAndSet(false)) recorder?.finish() }
    val failure =
      assertThrows(IllegalStateException::class.java) { recorder.await(5_000) { it == 1 } }
    assertTrue(failure.message!!.contains("completed"))
    assertTrue(failure.message!!.contains("item"))
  }

  @Test
  fun `error after a wait fails early and awaitError returns it`() {
    val recorder = TestRecorder()
    recorder.failWith("boom")
    val failure = assertThrows(IllegalStateException::class.java) { recorder.await(1_000) { true } }
    assertTrue(failure.message!!.contains("boom"))
    assertEquals("boom", recorder.awaitError(1_000))
    assertEquals("boom", recorder.error)
    assertFalse(recorder.completed)
  }

  @Test
  fun `awaitComplete returns once completed`() {
    val recorder = TestRecorder()
    recorder.finish()
    recorder.awaitComplete(1_000)
    assertTrue(recorder.completed)
  }

  @Test
  fun `awaitError fails when only completion arrives`() {
    val recorder = TestRecorder()
    recorder.finish()
    val failure = assertThrows(IllegalStateException::class.java) { recorder.awaitError(1_000) }
    assertTrue(failure.message!!.contains("completed"))
  }

  @Test
  fun `awaitComplete fails on a terminal error`() {
    val recorder = TestRecorder()
    recorder.failWith("bad")
    val failure = assertThrows(IllegalStateException::class.java) { recorder.awaitComplete(1_000) }
    assertTrue(failure.message!!.contains("bad"))
  }

  @Test
  fun `negative durations are rejected`() {
    val recorder = TestRecorder()
    assertThrows(IllegalArgumentException::class.java) { recorder.await((-1).seconds) { true } }
    assertThrows(IllegalArgumentException::class.java) { recorder.awaitError((-1).seconds) }
    assertThrows(IllegalArgumentException::class.java) { recorder.awaitComplete((-1).seconds) }
  }

  @Test
  fun `non-finite durations are rejected`() {
    val recorder = TestRecorder()
    assertThrows(IllegalArgumentException::class.java) {
      recorder.await(Duration.INFINITE) { true }
    }
    assertThrows(IllegalArgumentException::class.java) { recorder.awaitError(Duration.INFINITE) }
    assertThrows(IllegalArgumentException::class.java) { recorder.awaitComplete(Duration.INFINITE) }
  }

  @Test
  fun `negative after and timeout are rejected`() {
    val recorder = TestRecorder()
    assertThrows(IllegalArgumentException::class.java) { recorder.await(0, after = -1) { true } }
    assertThrows(IllegalArgumentException::class.java) { recorder.await(-1) { true } }
  }

  @Test
  fun `zero duration checks buffered items then gives up`() {
    val recorder = TestRecorder()
    recorder.emit(3)
    assertEquals(3, recorder.await(Duration.ZERO) { it == 3 })
    assertThrows(IllegalStateException::class.java) { recorder.await(Duration.ZERO) { it == 4 } }
  }

  @Test
  fun `a null error message is still terminal`() {
    val recorder = TestRecorder()
    recorder.failWith(null)
    assertEquals("", recorder.error)
    assertEquals("", recorder.awaitError(1_000))
    val failure = assertThrows(IllegalStateException::class.java) { recorder.await(1_000) { true } }
    assertTrue(failure.message!!.contains("terminal error"))
    assertFalse(recorder.completed)
  }

  @Test
  fun `a positive sub-millisecond duration accepts a buffered match`() {
    val recorder = TestRecorder()
    recorder.emit(1)
    assertEquals(1, recorder.await(100.microseconds) { it == 1 })
  }

  @Test(timeout = 5_000)
  fun `a timeout larger than one poll is honored while waiting for an item`() {
    // The item arrives on the third pump, past one 20ms poll; a one-poll deadline would give up.
    val calls = AtomicInteger()
    var recorder: TestRecorder? = null
    recorder = TestRecorder { if (calls.incrementAndGet() == 3) recorder?.emit(7) }
    assertEquals(7, recorder.await(1_000) { it == 7 })
  }

  @Test(timeout = 5_000)
  fun `a timeout larger than one poll is honored while waiting for completion`() {
    val calls = AtomicInteger()
    var recorder: TestRecorder? = null
    recorder = TestRecorder { if (calls.incrementAndGet() == 3) recorder?.finish() }
    recorder.awaitComplete(1_000)
    assertTrue(recorder.completed)
  }

  @Test(timeout = 5_000)
  fun `a timeout larger than one poll is honored while waiting for an error`() {
    val calls = AtomicInteger()
    var recorder: TestRecorder? = null
    recorder = TestRecorder { if (calls.incrementAndGet() == 3) recorder?.failWith("late") }
    assertEquals("late", recorder.awaitError(1_000))
  }

  @Test
  fun `a real timeout without a match fails before the watchdog`() {
    // The empty pump never advances a looper or clock, so this only returns if the wait's own
    // blocking timeout makes progress on the real clock.
    val recorder = TestRecorder()
    val done = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>()
    val worker =
      thread(isDaemon = true) {
        try {
          recorder.await(10.milliseconds) { it == 1 }
        } catch (t: Throwable) {
          thrown.set(t)
        } finally {
          done.countDown()
        }
      }
    val returned = done.await(2, TimeUnit.SECONDS)
    if (!returned) worker.interrupt()
    assertTrue("await must time out within 2s", returned)
    assertTrue(thrown.get() is IllegalStateException)
  }

  // Terminal lands while the predicate blocks on an older nonmatch; the buffered match must win.
  private fun assertMatchWins(terminal: (TestRecorder) -> Unit) {
    val recorder = TestRecorder()
    recorder.emit(0)
    val examining = CountDownLatch(1)
    val release = CountDownLatch(1)
    val predicate: (Int) -> Boolean = { item ->
      if (item == 0) {
        examining.countDown()
        assertTrue(release.await(5, TimeUnit.SECONDS))
        false
      } else {
        item == 1
      }
    }
    val pool = Executors.newSingleThreadExecutor()
    try {
      val result = pool.submit(Callable { recorder.await(5_000, predicate = predicate) })
      assertTrue(examining.await(5, TimeUnit.SECONDS))
      recorder.emit(1)
      terminal(recorder)
      release.countDown()
      val value =
        try {
          result.get(5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
          throw e.cause ?: e
        }
      assertEquals(1, value)
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test(timeout = 15_000)
  fun `completion landing during a predicate still returns the match`() {
    assertMatchWins { it.finish() }
  }

  @Test(timeout = 15_000)
  fun `error landing during a predicate still returns the match`() {
    assertMatchWins { it.failWith("boom") }
  }
}
