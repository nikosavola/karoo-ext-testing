package fi.nikosavola.karooext.testing

import android.os.Bundle
import android.os.RemoteException
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.KarooInfo
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val AWAIT_MS = 5_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FakeKarooSystemTest {
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
    val system = FakeKarooSystem()
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
    val system = FakeKarooSystem()
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
  fun `setLocation publishes to a matching consumer`() {
    val system = FakeKarooSystem()
    val handler = CapturingHandler()
    system.add("c", OnLocationChanged.Params, handler)
    system.setLocation(1.5, 2.5, orientation = 90.0)
    val got = handler.events<OnLocationChanged>().single()
    assertEquals(1.5, got.lat, 0.0)
    assertEquals(90.0, got.orientation!!, 0.0)
  }

  @Test
  fun `bridged http sends in progress then the responder answer and records headers`() {
    val system = FakeKarooSystem()
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
    val system = FakeKarooSystem()
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
    val system = FakeKarooSystem()
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
    val system = FakeKarooSystem()
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
    val system = FakeKarooSystem()
    assertEquals(
      HardwareType.KAROO,
      system.info().serializableFromBundle<KarooInfo>()!!.hardwareType,
    )
    assertEquals(
      HardwareType.K2,
      FakeKarooSystem(hardwareType = HardwareType.K2)
        .info()
        .serializableFromBundle<KarooInfo>()!!
        .hardwareType,
    )
  }
}
