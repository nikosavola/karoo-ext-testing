package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StickyStateTest {
  private val system = FakeKarooSystem()
  private val indoor = RideProfile("indoor", "Indoor", emptyList(), true, "indoor_cycling", "road")

  @After fun close() = system.close()

  private fun add(id: String, params: KarooEventParams, handler: IHandler) =
    system.addEventConsumer(id, params.bundleWithSerializable(KAROO_SYSTEM_PACKAGE), handler)

  @Test
  fun `location is published as a LOCATION stream point too`() {
    val handler = CapturingHandler()
    add("loc", OnStreamState.StartStreaming(DataType.Type.LOCATION), handler)
    system.setLocation(60.17, 24.94, orientation = 90.0, accuracy = 12.0)
    val point = (handler.events<OnStreamState>().single().state as StreamState.Streaming).dataPoint
    assertEquals(60.17, point.values.getValue(DataType.Field.LOC_LATITUDE), 0.0)
    assertEquals(24.94, point.values.getValue(DataType.Field.LOC_LONGITUDE), 0.0)
    assertEquals(90.0, point.values.getValue(DataType.Field.LOC_BEARING), 0.0)
    assertEquals(12.0, point.values.getValue(DataType.Field.LOC_ACCURACY), 0.0)
  }

  @Test
  fun `location without orientation leaves bearing out and replays both forms`() {
    system.setLocation(1.0, 2.0)
    val stream = CapturingHandler()
    val event = CapturingHandler()
    add("s", OnStreamState.StartStreaming(DataType.Type.LOCATION), stream)
    add("e", OnLocationChanged.Params, event)
    val point = (stream.events<OnStreamState>().single().state as StreamState.Streaming).dataPoint
    assertFalse(point.values.containsKey(DataType.Field.LOC_BEARING))
    assertEquals(1.0, event.events<OnLocationChanged>().single().lat, 0.0)
  }

  @Test
  fun `active ride profile is replayed to late consumers`() {
    system.setActiveRideProfile(indoor)
    val handler = CapturingHandler()
    add("p", ActiveRideProfile.Params, handler)
    assertTrue(handler.events<ActiveRideProfile>().single().profile.indoor)
  }

  @Test
  fun `generic sticky events reach current and later consumers`() {
    val early = CapturingHandler()
    add("early", OnMapZoomLevel.Params, early)
    system.setSticky(OnMapZoomLevel.Params, OnMapZoomLevel(14.0))
    val late = CapturingHandler()
    add("late", OnMapZoomLevel.Params, late)
    assertEquals(14.0, early.events<OnMapZoomLevel>().single().zoomLevel, 0.0)
    assertEquals(14.0, late.events<OnMapZoomLevel>().single().zoomLevel, 0.0)
  }

  @Test
  fun `setSticky rejects params that have a typed setter`() {
    assertThrows(IllegalArgumentException::class.java) {
      system.setSticky(RideState.Params, RideState.Recording)
    }
    assertThrows(IllegalArgumentException::class.java) {
      system.setSticky(OnStreamState.StartStreaming("x"), OnStreamState(StreamState.Searching))
    }
  }

  @Test
  fun `initial stream state answers types with no state yet`() {
    system.initialStreamState = StreamState.NotAvailable
    system.setStreamState("set", StreamState.Searching)
    val unset = CapturingHandler()
    val set = CapturingHandler()
    add("u", OnStreamState.StartStreaming("unset"), unset)
    add("s", OnStreamState.StartStreaming("set"), set)
    assertEquals(StreamState.NotAvailable, unset.events<OnStreamState>().single().state)
    assertEquals(StreamState.Searching, set.events<OnStreamState>().single().state)
  }

  @Test
  fun `reset clears sticky events and the initial stream state`() {
    system.setActiveRideProfile(indoor)
    system.initialStreamState = StreamState.Searching
    system.reset()
    val profile = CapturingHandler()
    val stream = CapturingHandler()
    add("p", ActiveRideProfile.Params, profile)
    add("s", OnStreamState.StartStreaming("x"), stream)
    assertTrue(profile.events<ActiveRideProfile>().isEmpty())
    assertTrue(stream.events<OnStreamState>().isEmpty())
  }

  @Test
  fun `describe lists consumers, requests and stream states`() {
    add("p", ActiveRideProfile.Params, CapturingHandler())
    system.setStreamState("x", StreamState.Searching)
    val text = system.describe()
    assertTrue(text, text.contains("ActiveRideProfile"))
    assertTrue(text, text.contains("x="))
    assertTrue(text, text.contains("http requests: none"))
  }
}
