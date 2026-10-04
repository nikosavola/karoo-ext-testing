package fi.nikosavola.karooext.testing

import android.os.Bundle
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.KarooInfo
import io.hammerhead.karooext.models.Lap
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConsumerLifecycleTest {
  private val systems = mutableListOf<FakeKarooSystem>()

  @After
  fun closeSystems() {
    systems.forEach { it.close() }
  }

  private fun track(system: FakeKarooSystem): FakeKarooSystem = system.also { systems += it }

  // Encoded as the base KarooEventParams, the way karoo-ext's addConsumer does.
  private fun FakeKarooSystem.add(id: String, params: KarooEventParams, handler: IHandler) {
    addEventConsumer(id, params.bundleWithSerializable(KAROO_SYSTEM_PACKAGE), handler)
  }

  private fun lap(n: Int) = Lap(n, 60_000, "manual")

  @Test
  fun `a callback cancelling both ids suppresses the later consumer`() {
    val system = track(FakeKarooSystem())
    val invocations = AtomicInteger()
    val cancelBoth: (Bundle) -> Unit = {
      invocations.incrementAndGet()
      system.removeEventConsumer("a")
      system.removeEventConsumer("b")
    }
    system.add("a", Lap.Params, CallbackHandler(cancelBoth))
    system.add("b", Lap.Params, CallbackHandler(cancelBoth))

    system.publish(Lap.Params, lap(1))

    // Whichever runs first cancels both, so order does not matter.
    assertEquals(1, invocations.get())
    assertEquals(0, system.consumerCount)
  }

  @Test
  fun `reset during the first callback suppresses the later one`() {
    val system = track(FakeKarooSystem())
    val invocations = AtomicInteger()
    val reset: (Bundle) -> Unit = {
      invocations.incrementAndGet()
      system.reset()
    }
    system.add("a", Lap.Params, CallbackHandler(reset))
    system.add("b", Lap.Params, CallbackHandler(reset))

    system.publish(Lap.Params, lap(1))

    assertEquals(1, invocations.get())
    assertEquals(0, system.consumerCount)
  }

  @Test
  fun `close during the first callback suppresses the later one`() {
    val system = track(FakeKarooSystem())
    val invocations = AtomicInteger()
    val close: (Bundle) -> Unit = {
      invocations.incrementAndGet()
      system.close()
    }
    system.add("a", Lap.Params, CallbackHandler(close))
    system.add("b", Lap.Params, CallbackHandler(close))

    system.publish(Lap.Params, lap(1))

    assertEquals(1, invocations.get())
    assertEquals(0, system.consumerCount)
  }

  @Test
  fun `completing the other consumer during a callback sends no onNext to it`() {
    val system = track(FakeKarooSystem())
    val a = CallbackHandler { system.completeConsumer("b") }
    val b = CallbackHandler { system.completeConsumer("a") }
    system.add("a", Lap.Params, a)
    system.add("b", Lap.Params, b)

    system.publish(Lap.Params, lap(1))

    assertEquals(1, a.laps() + b.laps())
    assertTrue(
      "exactly one consumer must have been completed",
      a.completed.get() != b.completed.get(),
    )
    val ended = if (a.completed.get()) a else b
    val delivered = if (a.completed.get()) b else a
    assertEquals(1, delivered.laps())
    assertEquals("no onNext after the terminal callback", 0, ended.laps())
  }

  @Test
  fun `replacing the other id in a callback does not deliver the prior publication to it`() {
    val system = track(FakeKarooSystem())
    val replacement = CallbackHandler()
    val once = AtomicBoolean(false)

    fun replaceOther(other: String): (Bundle) -> Unit = {
      if (once.compareAndSet(false, true)) {
        system.removeEventConsumer(other)
        system.add(other, Lap.Params, replacement)
      }
    }
    val a = CallbackHandler(replaceOther("b"))
    val b = CallbackHandler(replaceOther("a"))
    system.add("a", Lap.Params, a)
    system.add("b", Lap.Params, b)

    system.publish(Lap.Params, lap(1))

    assertEquals("exactly one original delivered", 1, a.laps() + b.laps())
    assertEquals(0, replacement.laps())
    assertEquals(2, system.consumerCount)

    val survivor = if (a.laps() > 0) a else b
    val removed = if (a.laps() > 0) b else a
    system.publish(Lap.Params, lap(2))
    assertEquals(2, survivor.laps())
    assertEquals(0, removed.laps())
    assertEquals(1, replacement.laps())
  }

  @Test
  fun `reset followed by registration does not send the previous epoch to the new consumer`() {
    val system = track(FakeKarooSystem())
    val replacement = CallbackHandler()
    val original = CallbackHandler {
      system.reset()
      system.add("same", OnLocationChanged.Params, replacement)
    }
    system.add("same", OnLocationChanged.Params, original)

    system.setLocation(1.0, 2.0)

    assertEquals(1, original.locations())
    assertEquals(0, replacement.locations())
    assertEquals(1, system.consumerCount)
  }

  @Test
  fun `a concurrent cancel waits for the in-flight delivery and suppresses later sends`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val blocking = CallbackHandler {
      entered.countDown()
      assertTrue(release.await(5, TimeUnit.SECONDS))
    }
    system.add("blocking", Lap.Params, blocking)

    val pool = Executors.newFixedThreadPool(2)
    try {
      val publish = pool.submit(Runnable { system.publish(Lap.Params, lap(1)) })
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val started = CountDownLatch(1)
      val cancelDone = CountDownLatch(1)
      val cancel =
        pool.submit(
          Runnable {
            started.countDown()
            system.removeEventConsumer("blocking")
            cancelDone.countDown()
          }
        )
      assertTrue(started.await(5, TimeUnit.SECONDS))
      assertFalse(cancelDone.await(200, TimeUnit.MILLISECONDS))
      release.countDown()
      publish.getOrFail()
      cancel.getOrFail()
      assertTrue(cancelDone.await(5, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      pool.shutdownNow()
    }

    assertEquals(0, system.consumerCount)
    system.publish(Lap.Params, lap(2))
    assertEquals(1, blocking.laps())
  }

  @Test
  fun `a concurrent terminal callback waits for the in-flight delivery and ends the consumer`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val blocking = CallbackHandler {
      entered.countDown()
      assertTrue(release.await(5, TimeUnit.SECONDS))
    }
    system.add("blocking", Lap.Params, blocking)

    val pool = Executors.newFixedThreadPool(2)
    try {
      val publish = pool.submit(Runnable { system.publish(Lap.Params, lap(1)) })
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val started = CountDownLatch(1)
      val terminalDone = CountDownLatch(1)
      val terminal =
        pool.submit(
          Runnable {
            started.countDown()
            system.completeConsumer("blocking")
            terminalDone.countDown()
          }
        )
      assertTrue(started.await(5, TimeUnit.SECONDS))
      assertFalse(terminalDone.await(200, TimeUnit.MILLISECONDS))
      release.countDown()
      publish.getOrFail()
      terminal.getOrFail()
      assertTrue(terminalDone.await(5, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      pool.shutdownNow()
    }

    assertTrue(blocking.completed.get())
    assertEquals(0, system.consumerCount)
    system.publish(Lap.Params, lap(2))
    assertEquals(1, blocking.laps())
  }

  @Test
  fun `a concurrent sticky setter cannot mutate state while a delivery is in flight`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val handler = CallbackHandler { bundle ->
      val location = bundle.serializableFromBundle<OnLocationChanged>()
      if (location != null && location.lat == 1.0) {
        entered.countDown()
        assertTrue(release.await(5, TimeUnit.SECONDS))
      }
    }
    system.add("loc", OnLocationChanged.Params, handler)

    val pool = Executors.newFixedThreadPool(2)
    try {
      val first = pool.submit(Runnable { system.setLocation(1.0, 1.0) })
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val started = CountDownLatch(1)
      val secondDone = CountDownLatch(1)
      val second =
        pool.submit(
          Runnable {
            started.countDown()
            system.setLocation(2.0, 2.0)
            secondDone.countDown()
          }
        )
      assertTrue(started.await(5, TimeUnit.SECONDS))
      assertFalse(secondDone.await(200, TimeUnit.MILLISECONDS))
      assertEquals(1.0, system.location!!.lat, 0.0)
      release.countDown()
      first.getOrFail()
      second.getOrFail()
      assertTrue(secondDone.await(5, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      pool.shutdownNow()
    }

    assertEquals(2.0, system.location!!.lat, 0.0)
    assertEquals(
      listOf(1.0, 2.0),
      handler.events.mapNotNull { it.serializableFromBundle<OnLocationChanged>()?.lat },
    )
  }

  @Test
  fun `setters and info stay usable after close and deliver to nothing`() {
    val system = track(FakeKarooSystem(hardwareType = HardwareType.K2))
    system.close()
    system.close()

    system.setLocation(1.0, 2.0)
    system.setRideState(RideState.Recording)
    system.setUserProfile(FakeKarooSystem.metricProfile())
    system.showPage(listOf("x"))
    system.setStreamState("x", StreamState.Searching)

    assertEquals(1.0, system.location!!.lat, 0.0)
    assertEquals(RideState.Recording, system.rideState)
    assertEquals(0, system.consumerCount)
    assertEquals(
      HardwareType.K2,
      system.info().serializableFromBundle<KarooInfo>()!!.hardwareType,
    )
  }
}

private class CallbackHandler(private val onEvent: (Bundle) -> Unit = {}) : IHandler.Stub() {
  val events = CopyOnWriteArrayList<Bundle>()
  val completed = AtomicBoolean(false)

  override fun onNext(bundle: Bundle) {
    events += bundle
    onEvent(bundle)
  }

  override fun onError(message: String?) = Unit

  override fun onComplete() {
    completed.set(true)
  }
}

private fun CallbackHandler.laps(): Int = events.count { it.serializableFromBundle<Lap>() != null }

private fun CallbackHandler.locations(): Int = events.count {
  it.serializableFromBundle<OnLocationChanged>() != null
}

private fun <T> Future<T>.getOrFail(): T =
  try {
    get(5, TimeUnit.SECONDS)
  } catch (e: ExecutionException) {
    throw e.cause ?: e
  } catch (e: TimeoutException) {
    throw AssertionError("background task did not finish", e)
  }
