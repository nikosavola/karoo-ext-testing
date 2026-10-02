package fi.nikosavola.karooext.testing

import android.os.Bundle
import android.os.RemoteException
import io.hammerhead.karooext.EXT_LIB_VERSION
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.KarooInfo
import io.hammerhead.karooext.models.Lap
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.UserProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val AWAIT_MS = 5_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FakeKarooSystemTest {
  private val systems = mutableListOf<FakeKarooSystem>()

  @After
  fun closeSystems() {
    systems.forEach { it.close() }
  }

  private fun track(system: FakeKarooSystem): FakeKarooSystem = system.also { systems += it }

  private fun makeRequest(
    url: String = "https://example.test/value",
    headers: Map<String, String> = mapOf("Authorization" to "Bearer token"),
  ) = OnHttpResponse.MakeHttpRequest("GET", url, headers, null, false)

  // The bundle must be encoded as the base KarooEventParams, the way karoo-ext's addConsumer does,
  // or the fake cannot decode it back.
  private fun FakeKarooSystem.add(id: String, params: KarooEventParams, handler: IHandler) {
    addEventConsumer(id, params.bundleWithSerializable(KAROO_SYSTEM_PACKAGE), handler)
  }

  @Test
  fun `sticky location reaches a late consumer`() {
    val system = track(FakeKarooSystem())
    system.setLocation(60.0, 24.0)
    val handler = CapturingHandler()
    system.add("late", OnLocationChanged.Params, handler)
    val got = handler.events<OnLocationChanged>()
    assertEquals(1, got.size)
    assertEquals(60.0, got.single().lat, 0.0)
    assertEquals(24.0, got.single().lng, 0.0)
  }

  @Test
  fun `sticky ride state, profile and page reach late consumers`() {
    val system = track(FakeKarooSystem())
    system.setRideState(RideState.Recording)
    system.setUserProfile(FakeKarooSystem.imperialProfile())
    system.showPage(listOf("sample-value"))
    val ride = CapturingHandler()
    val profile = CapturingHandler()
    val page = CapturingHandler()
    system.add("ride", RideState.Params, ride)
    system.add("profile", UserProfile.Params, profile)
    system.add("page", ActiveRidePage.Params, page)
    assertEquals(RideState.Recording, ride.events<RideState>().single())
    assertEquals(
      UserProfile.PreferredUnit.UnitType.IMPERIAL,
      profile.events<UserProfile>().single().preferredUnit.distance,
    )
    assertEquals(
      "sample-value",
      page.events<ActiveRidePage>().single().page.elements.single().dataTypeId,
    )
  }

  @Test
  fun `setRoute publishes a navigating route with its own length`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("nav", OnNavigationState.Params, handler)
    val points = listOf(60.0 to 24.0, 60.01 to 24.0, 60.01 to 24.01)
    system.setRoute(points, name = "Loop")
    // The sticky Idle arrives first, then the route.
    val route = handler.events<OnNavigationState>().last().state
    assertTrue(route is OnNavigationState.NavigationState.NavigatingRoute)
    route as OnNavigationState.NavigationState.NavigatingRoute
    assertEquals("Loop", route.name)
    // 0.01 deg of latitude plus 0.01 deg of longitude at 60 deg, within rounding.
    assertEquals(1_667.9, route.routeDistance, 2.0)
    assertTrue(route.routePolyline.isNotEmpty())
  }

  @Test
  fun `a set route is sticky for a late consumer`() {
    val system = track(FakeKarooSystem())
    system.setRoute(listOf(60.0 to 24.0, 60.0 to 24.01), name = "Out and back")
    val handler = CapturingHandler()
    system.add("late", OnNavigationState.Params, handler)
    val route = handler.events<OnNavigationState>().single().state
    assertTrue(route is OnNavigationState.NavigationState.NavigatingRoute)
  }

  @Test
  fun `setLocation publishes to a matching consumer`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("c", OnLocationChanged.Params, handler)
    system.setLocation(1.5, 2.5, orientation = 90.0)
    val got = handler.events<OnLocationChanged>().single()
    assertEquals(1.5, got.lat, 0.0)
    assertEquals(90.0, got.orientation!!, 0.0)
  }

  @Test
  fun `bridged http sends in progress then the responder answer and records headers`() {
    val system = track(FakeKarooSystem())
    system.responder = HttpResponses.success("42".toByteArray(), mapOf("X-Test" to "1"))
    val handler = CapturingHandler()
    system.add("http", makeRequest(), handler)
    val events = awaitValue(AWAIT_MS) { handler.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertTrue(events[0].state is HttpResponseState.InProgress)
    val complete = events[1].state as HttpResponseState.Complete
    assertEquals(200, complete.statusCode)
    assertEquals("42", complete.body!!.decodeToString())
    assertEquals("Bearer token", system.httpRequests.single().headers.getValue("Authorization"))
  }

  @Test
  fun `bridged http turns an oversized body into an error`() {
    val system = track(FakeKarooSystem())
    system.maxBodyBytes = 4
    system.responder = HttpResponses.success("12345".toByteArray())
    val handler = CapturingHandler()
    system.add("http", makeRequest(), handler)
    val events = awaitValue(AWAIT_MS) { handler.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    val complete = events[1].state as HttpResponseState.Complete
    assertEquals(0, complete.statusCode)
    assertTrue(complete.error!!.contains("too large"))
  }

  @Test
  fun `a dead handler is dropped`() {
    val system = track(FakeKarooSystem())
    val dead =
      object : IHandler.Stub() {
        override fun onNext(bundle: Bundle): Unit = throw RemoteException()

        override fun onError(message: String?) = Unit

        override fun onComplete() = Unit
      }
    system.add("dead", OnLocationChanged.Params, dead)
    assertEquals(1, system.consumerCount)
    system.setLocation(1.0, 2.0)
    assertEquals(0, system.consumerCount)
  }

  @Test
  fun `reset clears state, consumers and records`() {
    val system = track(FakeKarooSystem())
    system.responder = HttpResponses.success(ByteArray(1))
    system.setLocation(1.0, 2.0)
    system.setRideState(RideState.Recording)
    system.showPage(listOf("x"))
    system.add("c", OnLocationChanged.Params, CapturingHandler())
    system.add("http", makeRequest(), CapturingHandler())
    system.dispatchEffect(
      (TurnScreenOn as KarooEffect).bundleWithSerializable(KAROO_SYSTEM_PACKAGE)
    )
    awaitValue(AWAIT_MS) { system.httpRequests.takeIf { it.isNotEmpty() } }

    system.reset()

    assertEquals(0, system.consumerCount)
    assertNull(system.location)
    assertEquals(RideState.Idle, system.rideState)
    assertEquals(FakeKarooSystem.metricProfile(), system.userProfile)
    assertNull(system.activePage)
    assertTrue(system.effects.isEmpty())
    assertTrue(system.httpRequests.isEmpty())
  }

  @Test
  fun `info uses a configurable hardware type`() {
    val system = track(FakeKarooSystem())
    assertEquals(
      HardwareType.KAROO,
      system.info().serializableFromBundle<KarooInfo>()!!.hardwareType,
    )
    assertEquals(
      HardwareType.K2,
      track(FakeKarooSystem(hardwareType = HardwareType.K2))
        .info()
        .serializableFromBundle<KarooInfo>()!!
        .hardwareType,
    )
  }

  @Test
  fun `laps are published to current consumers but not sticky`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("lap", Lap.Params, handler)
    system.publish(Lap.Params, Lap(1, 60_000, "manual"))
    assertEquals(1, handler.events<Lap>().size)
    val late = CapturingHandler()
    system.add("late", Lap.Params, late)
    assertTrue(late.events<Lap>().isEmpty())
  }

  @Test
  fun `consumerParams lists registered params`() {
    val system = track(FakeKarooSystem())
    system.add("loc", OnLocationChanged.Params, CapturingHandler())
    system.add("ride", RideState.Params, CapturingHandler())
    assertEquals(2, system.consumerParams.size)
    assertTrue(
      system.consumerParams.containsAll(listOf(OnLocationChanged.Params, RideState.Params))
    )
  }

  @Test
  fun `a throwing responder reports only its class and later requests still work`() {
    val system = track(FakeKarooSystem())
    system.responder = HttpResponder {
      throw IllegalStateException("secret https://example.test?token=abc")
    }
    val first = CapturingHandler()
    system.add("http", makeRequest(), first)
    val events = awaitValue(AWAIT_MS) { first.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    val complete = events[1].state as HttpResponseState.Complete
    assertEquals(0, complete.statusCode)
    assertEquals("java.lang.IllegalStateException", complete.error)
    assertFalse(complete.error!!.contains("secret"))
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }

    system.responder = HttpResponses.success("ok".toByteArray())
    val second = CapturingHandler()
    system.add("http2", makeRequest(), second)
    val secondEvents =
      awaitValue(AWAIT_MS) { second.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals(200, (secondEvents[1].state as HttpResponseState.Complete).statusCode)
  }

  @Test
  fun `pending http count tracks an in flight request`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    system.responder = BlockingResponder(entered, release)
    val handler = CapturingHandler()
    system.add("http", makeRequest(), handler)
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    assertEquals(1, system.pendingHttpCount)
    release.countDown()
    // Removal happens after the Complete is delivered, so wait for the count, then check events.
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
    assertEquals(2, handler.events<OnHttpResponse>().size)
  }

  @Test
  fun `removing an http consumer suppresses a response already in flight`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    system.responder = BlockingResponder(entered, release)
    val stale = CapturingHandler()
    system.add("http", makeRequest(), stale)
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    system.removeEventConsumer("http")
    assertEquals(0, system.pendingHttpCount)
    system.responder = HttpResponses.success("ok".toByteArray())
    val followUp = CapturingHandler()
    system.add("http2", makeRequest(), followUp)
    release.countDown()
    // The executor is FIFO: the follow-up finishes only after the stale task has run to completion.
    awaitValue(AWAIT_MS) { followUp.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals(1, stale.events<OnHttpResponse>().size)
    assertTrue(stale.events<OnHttpResponse>().single().state is HttpResponseState.InProgress)
  }

  @Test
  fun `reset suppresses a response already in flight`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    system.responder = BlockingResponder(entered, release)
    val stale = CapturingHandler()
    system.add("http", makeRequest(), stale)
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    system.reset()
    assertEquals(0, system.pendingHttpCount)
    system.responder = HttpResponses.success("ok".toByteArray())
    val followUp = CapturingHandler()
    system.add("http2", makeRequest(), followUp)
    release.countDown()
    awaitValue(AWAIT_MS) { followUp.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals(1, stale.events<OnHttpResponse>().size)
  }

  @Test
  fun `close is idempotent and blocks new registrations`() {
    val system = track(FakeKarooSystem())
    system.add("loc", OnLocationChanged.Params, CapturingHandler())
    system.close()
    system.close()
    assertEquals(0, system.consumerCount)
    assertThrows(IllegalStateException::class.java) {
      system.add("late", OnLocationChanged.Params, CapturingHandler())
    }
  }

  @Test
  fun `close suppresses a response already in flight`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    system.responder = BlockingResponder(entered, release)
    val handler = CapturingHandler()
    system.add("http", makeRequest(), handler)
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    system.close()
    release.countDown()
    assertEquals(1, handler.events<OnHttpResponse>().size)
    assertTrue(handler.events<OnHttpResponse>().single().state is HttpResponseState.InProgress)
  }

  @Test
  fun `negative max body bytes is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { FakeKarooSystem(maxBodyBytes = -1) }
    val system = track(FakeKarooSystem())
    assertThrows(IllegalArgumentException::class.java) { system.maxBodyBytes = -1 }
  }

  @Test
  fun `pending count includes the request while its Complete handler runs`() {
    val system = track(FakeKarooSystem())
    system.responder = HttpResponses.success("x".toByteArray())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val handler = PendingObservingHandler(system, entered, release)
    system.add("http", makeRequest(), handler)
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    assertEquals(1, handler.pendingDuringComplete)
    release.countDown()
    val followUp = CapturingHandler()
    system.add("http2", makeRequest(), followUp)
    // FIFO executor: the follow-up only runs after the first task has removed its entry.
    awaitValue(AWAIT_MS) { followUp.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
  }

  @Test
  fun `a handler re-registering its id while completing keeps the new request`() {
    val system = track(FakeKarooSystem())
    system.responder = HttpResponder { request ->
      HttpResponses.success(request.url.substringAfterLast('/').toByteArray()).response
    }
    val replacement = CapturingHandler()
    val first = CompleteCallbackHandler {
      system.add("dup", makeRequest("https://example.test/second"), replacement)
    }
    system.add("dup", makeRequest("https://example.test/first"), first)
    awaitValue(AWAIT_MS) { replacement.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    val body = replacement.events<OnHttpResponse>().last().state as HttpResponseState.Complete
    assertEquals("second", body.body!!.decodeToString())
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
  }

  @Test
  fun `a request reusing an id after reset is delivered while the stale one is dropped`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    system.responder = BlockingResponder(entered, release)
    val stale = CapturingHandler()
    system.add("http", makeRequest(), stale)
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    system.reset()
    system.responder = HttpResponses.success("fresh".toByteArray())
    val fresh = CapturingHandler()
    system.add("http", makeRequest("https://example.test/fresh"), fresh)
    release.countDown()
    awaitValue(AWAIT_MS) { fresh.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals(1, stale.events<OnHttpResponse>().size)
    val body = fresh.events<OnHttpResponse>().last().state as HttpResponseState.Complete
    assertEquals("fresh", body.body!!.decodeToString())
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
  }

  @Test
  fun `a handler cancelling itself mid callback does not corrupt state`() {
    val system = track(FakeKarooSystem())
    val delivered = AtomicInteger()
    val handler =
      object : IHandler.Stub() {
        override fun onNext(bundle: Bundle) {
          if (
            bundle.serializableFromBundle<OnHttpResponse>()?.state is HttpResponseState.Complete
          ) {
            delivered.incrementAndGet()
            system.removeEventConsumer("http")
          }
        }

        override fun onError(message: String?) = Unit

        override fun onComplete() = Unit
      }
    system.responder = HttpResponses.success("x".toByteArray())
    system.add("http", makeRequest(), handler)
    // Removal follows the callback, so a zero count implies the handler already ran.
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
    assertEquals(1, delivered.get())
  }

  @Test
  fun `libVersion reports the SDK constant and can be overridden`() {
    assertEquals(EXT_LIB_VERSION, track(FakeKarooSystem()).libVersion())
    assertEquals("9.9.9", track(FakeKarooSystem(libVersion = "9.9.9")).libVersion())
  }

  @Test
  fun `httpThreads must be positive`() {
    assertThrows(IllegalArgumentException::class.java) { FakeKarooSystem(httpThreads = 0) }
    assertThrows(IllegalArgumentException::class.java) { FakeKarooSystem(httpThreads = -1) }
  }

  @Test
  fun `reset after close does not reopen and double close is harmless`() {
    val system = track(FakeKarooSystem())
    system.add("loc", OnLocationChanged.Params, CapturingHandler())
    system.close()
    system.close()
    system.reset()
    assertEquals(0, system.consumerCount)
    assertThrows(IllegalStateException::class.java) {
      system.add("late", OnLocationChanged.Params, CapturingHandler())
    }
  }

  @Test
  fun `an unreadable params bundle reports onError and registers nothing`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.addEventConsumer("bad", Bundle(), handler)
    // Fake policy: the SDK itself would drop-and-log a bad payload.
    assertEquals(listOf("Unreadable params"), handler.errors.toList())
    assertEquals(0, system.consumerCount)
  }

  @Test
  fun `the exact max body boundary is allowed and zero allows empty or null bodies`() {
    val atLimit = track(FakeKarooSystem(maxBodyBytes = 4))
    atLimit.responder = HttpResponses.success("1234".toByteArray())
    val exact = CapturingHandler()
    atLimit.add("at", makeRequest(), exact)
    val exactEvents =
      awaitValue(AWAIT_MS) { exact.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals(200, (exactEvents[1].state as HttpResponseState.Complete).statusCode)

    val zero = track(FakeKarooSystem(maxBodyBytes = 0))
    zero.responder = HttpResponses.success(ByteArray(0))
    val empty = CapturingHandler()
    zero.add("empty", makeRequest(), empty)
    val emptyEvents =
      awaitValue(AWAIT_MS) { empty.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals(200, (emptyEvents[1].state as HttpResponseState.Complete).statusCode)

    zero.responder = HttpResponses.status(204)
    val noBody = CapturingHandler()
    zero.add("null", makeRequest(), noBody)
    val nullEvents =
      awaitValue(AWAIT_MS) { noBody.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals(204, (nullEvents[1].state as HttpResponseState.Complete).statusCode)
  }

  @Test
  fun `responder and body limit are captured at registration`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    system.responder = BlockingResponder(entered, release)
    val handler = CapturingHandler()
    system.add("http", makeRequest(), handler)
    assertTrue(entered.await(5, TimeUnit.SECONDS))

    // Changing either afterwards must not affect the in-flight request.
    system.responder = HttpResponses.success("changed".toByteArray())
    system.maxBodyBytes = 1
    release.countDown()
    val events = awaitValue(AWAIT_MS) { handler.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals("late", (events[1].state as HttpResponseState.Complete).body!!.decodeToString())
  }

  @Test
  fun `a handler throwing during InProgress never runs the responder and drains pending`() {
    val system = track(FakeKarooSystem())
    val seen = CopyOnWriteArrayList<Throwable>()
    val urls = CopyOnWriteArrayList<String>()
    system.responder = HttpResponder { request ->
      urls += request.url
      HttpResponses.success("x".toByteArray()).response
    }
    system.add(
      "http",
      makeRequest(),
      ThrowingHandler({ it is HttpResponseState.InProgress }, seen),
    )
    awaitValue(AWAIT_MS) { seen.takeIf { it.isNotEmpty() } }
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
    assertTrue(urls.isEmpty())
  }

  @Test
  fun `a handler throwing during Complete drains pending and the next request still runs`() {
    val system = track(FakeKarooSystem())
    val seen = CopyOnWriteArrayList<Throwable>()
    system.responder = HttpResponses.success("first".toByteArray())
    system.add(
      "http",
      makeRequest("https://example.test/first"),
      ThrowingHandler({ it is HttpResponseState.Complete }, seen),
    )
    awaitValue(AWAIT_MS) { seen.takeIf { it.isNotEmpty() } }
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }

    system.responder = HttpResponses.success("second".toByteArray())
    val followUp = CapturingHandler()
    system.add("http2", makeRequest("https://example.test/second"), followUp)
    val events = awaitValue(AWAIT_MS) { followUp.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals("second", (events[1].state as HttpResponseState.Complete).body!!.decodeToString())
  }

  @Test
  fun `a dead handler during InProgress never runs the responder and leaves nothing pending`() {
    val system = track(FakeKarooSystem())
    val urls = CopyOnWriteArrayList<String>()
    system.responder = HttpResponder { request ->
      urls += request.url
      HttpResponses.success("x".toByteArray()).response
    }
    val dead =
      object : IHandler.Stub() {
        override fun onNext(bundle: Bundle): Unit = throw RemoteException()

        override fun onError(message: String?) = Unit

        override fun onComplete() = Unit
      }
    system.add("http", makeRequest("https://example.test/dead"), dead)
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
    assertTrue(urls.isEmpty())
  }

  @Test
  fun `cancelling a queued http request before it runs never invokes its responder`() {
    val system = track(FakeKarooSystem())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val urls = CopyOnWriteArrayList<String>()
    system.responder = UrlRecordingResponder("https://example.test/first", urls, entered, release)
    system.add("first", makeRequest("https://example.test/first"), CapturingHandler())
    assertTrue(entered.await(5, TimeUnit.SECONDS))

    system.add("second", makeRequest("https://example.test/second"), CapturingHandler())
    system.removeEventConsumer("second")
    release.countDown()
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
    assertEquals(listOf("https://example.test/first"), urls.toList())
  }

  @Test
  fun `multiple http threads answer distinct requests without cross delivery`() {
    val system = track(FakeKarooSystem(httpThreads = 2))
    system.responder = BarrierEchoResponder(CyclicBarrier(2))
    val first = CapturingHandler()
    val second = CapturingHandler()
    system.add("a", makeRequest("https://example.test/a"), first)
    system.add("b", makeRequest("https://example.test/b"), second)
    val firstEvents =
      awaitValue(AWAIT_MS) { first.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    val secondEvents =
      awaitValue(AWAIT_MS) { second.events<OnHttpResponse>().takeIf { it.size >= 2 } }
    assertEquals("a", (firstEvents[1].state as HttpResponseState.Complete).body!!.decodeToString())
    assertEquals("b", (secondEvents[1].state as HttpResponseState.Complete).body!!.decodeToString())
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
  }

  @Test
  fun `completeConsumer invokes then removes a raw consumer and rejects unknown ids`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("c", OnLocationChanged.Params, handler)
    assertTrue(system.completeConsumer("c"))
    assertTrue(handler.completed)
    assertEquals(0, system.consumerCount)
    assertFalse(system.completeConsumer("c"))
  }

  @Test
  fun `errorConsumer passes the message then removes a raw consumer`() {
    val system = track(FakeKarooSystem())
    val handler = CapturingHandler()
    system.add("c", OnLocationChanged.Params, handler)
    assertTrue(system.errorConsumer("c", "system failed"))
    assertEquals(listOf("system failed"), handler.errors.toList())
    assertEquals(0, system.consumerCount)
    assertFalse(system.errorConsumer("c", "again"))
  }

  @Test
  fun `terminal injection preserves a replacement registered during the callback`() {
    val system = track(FakeKarooSystem())
    val first =
      object : IHandler.Stub() {
        override fun onNext(bundle: Bundle) = Unit

        override fun onError(message: String?) = Unit

        override fun onComplete() {
          system.add("same", RideState.Params, CapturingHandler())
        }
      }
    system.add("same", OnLocationChanged.Params, first)
    assertTrue(system.completeConsumer("same"))
    assertEquals(1, system.consumerCount)
    assertTrue(system.consumerParams.contains(RideState.Params))
  }

  @Test
  fun `terminal injection ignores pending http consumers`() {
    val system = track(FakeKarooSystem())
    system.responder = HttpResponses.success("x".toByteArray())
    system.add("http", makeRequest(), CapturingHandler())
    assertFalse(system.completeConsumer("http"))
    assertFalse(system.errorConsumer("http", "nope"))
    awaitValue(AWAIT_MS) { system.pendingHttpCount.takeIf { it == 0 } }
  }
}

/** Runs [onResponse] the first time a Complete arrives, to exercise a reentrant callback. */
private class CompleteCallbackHandler(private val onResponse: () -> Unit) : IHandler.Stub() {
  private val done = AtomicBoolean(false)

  override fun onNext(bundle: Bundle) {
    val complete = bundle.serializableFromBundle<OnHttpResponse>()?.state
    if (complete is HttpResponseState.Complete && done.compareAndSet(false, true)) onResponse()
  }

  override fun onError(message: String?) = Unit

  override fun onComplete() = Unit
}

/** Records [FakeKarooSystem.pendingHttpCount] as seen inside a Complete callback, then blocks. */
private class PendingObservingHandler(
  private val system: FakeKarooSystem,
  private val entered: CountDownLatch,
  private val release: CountDownLatch,
) : IHandler.Stub() {
  @Volatile
  var pendingDuringComplete: Int = -1
    private set

  override fun onNext(bundle: Bundle) {
    if (bundle.serializableFromBundle<OnHttpResponse>()?.state is HttpResponseState.Complete) {
      pendingDuringComplete = system.pendingHttpCount
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
    }
  }

  override fun onError(message: String?) = Unit

  override fun onComplete() = Unit
}

/**
 * Blocks inside respond until [release] opens, so a test controls exactly when an answer finishes
 * and can cancel the request first.
 */
private class BlockingResponder(
  private val entered: CountDownLatch,
  private val release: CountDownLatch,
) : HttpResponder {
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete {
    entered.countDown()
    release.await(5, TimeUnit.SECONDS)
    return HttpResponses.success("late".toByteArray()).respond(request)
  }
}

/**
 * Records a Throwable locally and then rethrows it, so a test observes the executor's background
 * failure without a global uncaught-exception handler.
 */
private class ThrowingHandler(
  private val throwOn: (HttpResponseState) -> Boolean,
  private val seen: CopyOnWriteArrayList<Throwable>,
) : IHandler.Stub() {
  override fun onNext(bundle: Bundle) {
    val state = bundle.serializableFromBundle<OnHttpResponse>()?.state ?: return
    if (throwOn(state)) {
      val failure = IllegalStateException("callback threw on $state")
      seen += failure
      throw failure
    }
  }

  override fun onError(message: String?) = Unit

  override fun onComplete() = Unit
}

/** Records each request URL, blocking on [blockedUrl] until released. */
private class UrlRecordingResponder(
  private val blockedUrl: String,
  private val urls: CopyOnWriteArrayList<String>,
  private val entered: CountDownLatch,
  private val release: CountDownLatch,
) : HttpResponder {
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete {
    urls += request.url
    if (request.url == blockedUrl) {
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
    }
    return HttpResponses.success("ok".toByteArray()).respond(request)
  }
}

/** Waits for [barrier] in respond so two tasks are provably in flight together. */
private class BarrierEchoResponder(private val barrier: CyclicBarrier) : HttpResponder {
  override fun respond(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete {
    barrier.await(5, TimeUnit.SECONDS)
    return HttpResponses.success(request.url.substringAfterLast('/').toByteArray()).respond(request)
  }
}
