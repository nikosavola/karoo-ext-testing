package fi.nikosavola.karooext.testing.samples

import android.widget.RemoteViews
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.RoutingResponder
import fi.nikosavola.karooext.testing.SequenceResponder
import fi.nikosavola.karooext.testing.ViewRecorder
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import kotlin.time.Duration.Companion.seconds

// Test sources let the docs build type-check these @sample bodies.

/** Sets a native POWER data point: the POWER field plus a source id, not the SINGLE field. */
fun nativePowerInput() {
  FakeKarooSystem().use { system ->
    system.setDataPoint(
      DataPoint(
        DataType.Type.POWER,
        mapOf(DataType.Field.POWER to 250.0),
        sourceId = "power-meter-1",
      )
    )
  }
}

/**
 * Serves a transport failure, then a JSON success, one per request.
 *
 * @param system the fake whose responder is replaced by the sequence.
 * @return the sequence, kept so a test can assert [SequenceResponder.remainingResponses] after the
 *   requests it actually expects.
 */
fun scriptedHttpResponses(system: FakeKarooSystem): SequenceResponder {
  val responses =
    HttpResponses.sequence(
      HttpResponses.failure("no route"),
      HttpResponses.success("""{"value":42}""".toByteArray()),
    )
  system.responder = responses
  return responses
}

/** Navigates a two-point route with latitude first, the way the ride app follows a planned one. */
fun routeInput() {
  FakeKarooSystem().use { system ->
    system.setRoute(listOf(60.17 to 24.94, 60.18 to 24.95), name = "Commute")
  }
}

/**
 * Routes by a path substring first, then by a whole-request predicate; the first match wins.
 *
 * @return the built responder.
 */
fun routedHttpResponses(): RoutingResponder {
  return RoutingResponder.build {
    path("/tiles/", HttpResponses.success("tile".toByteArray()))
    request(
      { it.method == "POST" && it.headers["Authorization"] == "Bearer token" },
      HttpResponses.status(202),
    )
  }
}

/**
 * Waits for the next frame after a caller-triggered update, checkpointing with the total item count
 * so an interleaved view event cannot skip a frame.
 *
 * @param recorder the view session to watch.
 * @param update triggers the update a frame is expected for; the caller owns any looper context.
 * @return the first frame recorded after the checkpoint.
 */
fun waitForNewFrame(recorder: ViewRecorder, update: () -> Unit): RemoteViews {
  val checkpoint = recorder.items.size
  update()
  return recorder.awaitFrame(1.seconds, after = checkpoint)
}
