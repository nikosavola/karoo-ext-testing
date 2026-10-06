package fi.nikosavola.karooext.testing

import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooExtension
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.ViewEvent
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val VIEW_KEY = "view"
private const val POLL_MS = 20L
private val POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(POLL_MS)

/**
 * The ride-app end of the extension binder: starts streams, views, maps, scans, device connections
 * and FIT writing the way a ride page does.
 *
 * Recorded items are handed to [Recorder]s, whose waits measure real elapsed time. Under
 * Robolectric, `pump` runs a main-looper idle between polls so work the extension posts to the main
 * thread still runs while the test thread blocks; leaving it empty is fine when updates arrive
 * independently of the main looper. An Android runtime (Robolectric or a device) is still required:
 * the binder stubs and `Bundle` serialization do not work on a plain JVM.
 *
 * The host owns every session it starts and is [Closeable]: [close] stops all of them, even when
 * one stop fails, and refuses to start anything new afterwards. The explicit stop methods call the
 * binder directly for whatever id they are given, tracked or not, and do not promise idempotence;
 * [bonusAction] is delivered but not tracked as a session.
 *
 * @param extension the extension end handed back by the bound service.
 * @param pump runs between recorder polls, e.g. a main-looper idle; no-op by default.
 */
@Suppress("TooManyFunctions")
class FakeKarooHost(private val extension: IKarooExtension, private val pump: () -> Unit = {}) :
  Closeable {
  private val lock = ReentrantLock()
  private val sessions = LinkedHashMap<String, () -> Unit>()
  private val recorders = CopyOnWriteArrayList<Recorder<*>>()
  private var closed = false

  /**
   * Starts a stream for [typeId], the short `DataTypeImpl.typeId` the type was declared with (for
   * example `"my-type"`), not the full wire id from `DataType.dataTypeId(extensionId, typeId)` that
   * a `DataPoint` carries. The SDK silently ignores an unknown type id and registers no consumer,
   * so awaiting the recorder then times out.
   */
  fun startStream(typeId: String): StreamRecorder {
    val recorder = StreamRecorder(UUID.randomUUID().toString(), pump)
    return start(recorder, { extension.startStream(recorder.id, typeId, recorder.handler) }) {
      extension.stopStream(recorder.id)
    }
  }

  /** Stops the stream session and untracks it. Calls the binder even if the id is untracked. */
  fun stopStream(recorder: StreamRecorder) =
    stopSession(recorder.id) { extension.stopStream(recorder.id) }

  /**
   * Starts a view for [typeId], the short `DataTypeImpl.typeId` the type was declared with (for
   * example `"my-view-type"`), not the full wire id from `DataType.dataTypeId(extensionId,
   * typeId)`. The SDK silently ignores an unknown type id and registers no view, so awaiting the
   * recorder then times out.
   */
  fun startView(typeId: String, config: ViewConfig): ViewRecorder {
    val recorder = ViewRecorder(UUID.randomUUID().toString(), pump)
    return start(
      recorder,
      {
        extension.startView(
          recorder.id,
          typeId,
          config.bundleWithSerializable(KAROO_SYSTEM_PACKAGE),
          recorder.handler,
        )
      },
    ) {
      extension.stopView(recorder.id)
    }
  }

  /** Stops the view session and untracks it. Calls the binder even if the id is untracked. */
  fun stopView(recorder: ViewRecorder) =
    stopSession(recorder.id) { extension.stopView(recorder.id) }

  /** Starts a map session; the recorder collects decoded `MapEffect`s. */
  fun startMap(): MapRecorder {
    val recorder = MapRecorder(UUID.randomUUID().toString(), pump)
    return start(recorder, { extension.startMap(recorder.id, recorder.handler) }) {
      extension.stopMap(recorder.id)
    }
  }

  /** Stops the map session and untracks it. Calls the binder even if the id is untracked. */
  fun stopMap(recorder: MapRecorder) = stopSession(recorder.id) { extension.stopMap(recorder.id) }

  /** Starts a BLE scan; the recorder collects `Device`s the extension advertises. */
  fun startScan(): ScanRecorder {
    val recorder = ScanRecorder(UUID.randomUUID().toString(), pump)
    return start(recorder, { extension.startScan(recorder.id, recorder.handler) }) {
      extension.stopScan(recorder.id)
    }
  }

  /** Stops the scan session and untracks it. Calls the binder even if the id is untracked. */
  fun stopScan(recorder: ScanRecorder) =
    stopSession(recorder.id) { extension.stopScan(recorder.id) }

  /** Connects to the device with [uid]; the recorder collects decoded `DeviceEvent`s. */
  fun connectDevice(uid: String): DeviceRecorder {
    val recorder = DeviceRecorder(UUID.randomUUID().toString(), pump)
    return start(
      recorder,
      { extension.connectDevice(recorder.id, uid, recorder.handler) },
    ) {
      extension.disconnectDevice(recorder.id)
    }
  }

  /**
   * Disconnects the device session and untracks it. Calls the binder even if the id is untracked.
   */
  fun disconnectDevice(recorder: DeviceRecorder) =
    stopSession(recorder.id) { extension.disconnectDevice(recorder.id) }

  /** Starts a FIT-writing session; the recorder collects decoded `FitEffect`s. */
  fun startFit(): FitRecorder {
    val recorder = FitRecorder(UUID.randomUUID().toString(), pump)
    return start(recorder, { extension.startFit(recorder.id, recorder.handler) }) {
      extension.stopFit(recorder.id)
    }
  }

  /** Stops the FIT session and untracks it. Calls the binder even if the id is untracked. */
  fun stopFit(recorder: FitRecorder) = stopSession(recorder.id) { extension.stopFit(recorder.id) }

  /**
   * Sends a bonus action to the extension, like a controller button assigned to it. Delivered
   * immediately and not tracked as a session, so [close] does not undo it.
   */
  fun bonusAction(actionId: String) {
    lock.withLock {
      checkOpenLocked()
      extension.onBonusAction(actionId)
    }
  }

  /**
   * One line per recorder this host started, stopped ones included, with item counts by type, for
   * diagnostics. Not a stable format.
   */
  fun describe(): String =
    recorders.joinToString("\n") { recorder ->
      val counts =
        recorder.items
          .groupingBy { it?.javaClass?.simpleName ?: "null" }
          .eachCount()
          .entries
          .joinToString { (type, count) -> "$type x$count" }
      val terminal =
        listOfNotNull(
            "completed".takeIf { recorder.completed },
            recorder.error?.let { "error=$it" },
          )
          .joinToString(" ")
      "${recorder.javaClass.simpleName}: ${counts.ifEmpty { "no items" }} $terminal".trimEnd()
    }

  /** Stops every tracked session. Idempotent; attempts all stops and rethrows the first failure. */
  @Suppress("TooGenericExceptionCaught")
  override fun close() {
    val pending =
      lock.withLock {
        if (closed) {
          null
        } else {
          closed = true
          val copy = sessions.values.toList()
          sessions.clear()
          copy
        }
      } ?: return
    var failure: Throwable? = null
    pending.forEach { stop ->
      try {
        stop()
      } catch (t: Throwable) {
        failure = failure.suppressed(t)
      }
    }
    failure?.let { throw it }
  }

  @Suppress("RethrowCaughtException", "TooGenericExceptionCaught")
  private fun <R : Recorder<*>> start(recorder: R, call: () -> Unit, stop: () -> Unit): R =
    lock.withLock {
      checkOpenLocked()
      try {
        call()
      } catch (t: Throwable) {
        // The SDK may have installed resources before throwing.
        try {
          stop()
        } catch (cleanup: Throwable) {
          t.suppress(cleanup)
        }
        throw t
      }
      if (closed) {
        // A reentrant close could not see this session; stop it now that the SDK is done.
        stop()
      } else {
        sessions[recorder.id] = stop
      }
      recorders += recorder
      recorder
    }

  private fun stopSession(id: String, call: () -> Unit) {
    lock.withLock { sessions.remove(id) }
    call()
  }

  private fun checkOpenLocked() =
    check(!closed) { "FakeKarooHost is closed; no new sessions or actions" }

  private fun Throwable?.suppressed(other: Throwable): Throwable =
    if (this == null) {
      other
    } else if (this === other) {
      this
    } else {
      addSuppressed(other)
      this
    }

  private fun Throwable.suppress(other: Throwable) {
    if (this !== other) addSuppressed(other)
  }
}

/**
 * Records everything sent to one handler and lets a test wait for a matching item. Generic over the
 * decoded update type; [StreamRecorder] and friends fix that type and add typed accessors.
 *
 * This is a passive log, not a state machine: [record], [fail] and [complete] never suppress each
 * other. Items keep arriving after an error or a completion, [error] holds the last message seen,
 * and [completed] is tracked independently. Whatever terminal guard the real contract has lives in
 * the SDK's emitters, not here.
 *
 * @param T the decoded update type.
 * @property id session id shared with the extension, e.g. for [FakeKarooHost.stopStream].
 * @param pump runs between polls while waiting, never before the first buffered check; e.g. a
 *   Robolectric looper idle.
 */
@Suppress("TooManyFunctions")
open class Recorder<T>(val id: String, private val pump: () -> Unit) {
  private val lock = ReentrantLock()
  private val arrived = lock.newCondition()
  private val recorded = CopyOnWriteArrayList<T>()

  @Volatile private var finished = false

  /** Snapshot of everything recorded so far, in arrival order. */
  val items: List<T>
    get() = recorded.toList()

  /**
   * The last error message recorded, or null if none. A null message is stored as empty; a later
   * [fail] overwrites it.
   */
  @Volatile
  var error: String? = null
    private set

  /** True once [complete] has run. Independent of [error], so both can be set. */
  val completed: Boolean
    get() = finished

  /**
   * Appends [item] and wakes waiters; subclasses call it for each decoded update. Never suppressed,
   * so items keep accumulating after [fail] or [complete], and [await] still returns a buffered
   * match.
   */
  protected fun record(item: T) {
    recorded += item
    signal()
  }

  /**
   * Records the latest error message and wakes waiters; a null message is stored as empty. Does not
   * stop [record] or [complete], and the last [fail] wins.
   */
  protected fun fail(message: String?) {
    error = message.orEmpty()
    signal()
  }

  /**
   * Marks the session completed and wakes waiters. Independent of [error]: a later [fail] still
   * records, and both flags can be true.
   */
  protected fun complete() {
    finished = true
    signal()
  }

  /**
   * Waits for an item matching [predicate] among the recorded items from index [after] on: the
   * first [after] items are skipped, so [after] is the first acceptable index, not an exclusive
   * bound. Buffered items are checked before the first pump, so a match returns without waiting and
   * a zero timeout still probes once. If no buffered match exists and [error] or [completed] is
   * already set, it throws without waiting. The timeout is real elapsed time, not Robolectric or
   * coroutine virtual time, and `pump` runs between polls.
   *
   * @throws IllegalArgumentException if [timeoutMs] or [after] is negative.
   * @throws IllegalStateException on timeout, or when an error or completion arrives before a
   *   matching item.
   */
  fun await(timeoutMs: Long, after: Int = 0, predicate: (T) -> Boolean): T =
    awaitNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs), timeoutLabel(timeoutMs), after, predicate)

  /**
   * [kotlin.time.Duration] overload of [await]; positive sub-millisecond timeouts are kept.
   *
   * @throws IllegalArgumentException if [timeout] is not finite or negative, or [after] is
   *   negative.
   * @throws IllegalStateException on timeout, or when an error or completion arrives before a
   *   matching item.
   */
  fun await(timeout: Duration, after: Int = 0, predicate: (T) -> Boolean): T =
    awaitNanos(timeout.requireTimeoutNanos(), timeout.toString(), after, predicate)

  /**
   * [await] for the first item of type [E] matching [predicate], returned as [E].
   *
   * @throws IllegalStateException as [await] does.
   */
  inline fun <reified E : T> awaitOf(
    timeoutMs: Long = 20_000,
    after: Int = 0,
    crossinline predicate: (E) -> Boolean = { true },
  ): E = await(timeoutMs, after) { it is E && predicate(it) } as E

  /**
   * Waits until no new item has arrived for [quietMs], for extensions that send a burst of updates
   * with no end marker, then returns everything recorded. Real elapsed time.
   *
   * @throws IllegalArgumentException if [quietMs] is not positive or [timeoutMs] is negative.
   * @throws IllegalStateException if items keep arriving for [timeoutMs].
   */
  fun awaitQuiet(quietMs: Long, timeoutMs: Long): List<T> {
    require(quietMs > 0) { "quietMs must be positive, was $quietMs" }
    timeoutLabel(timeoutMs)
    val quietNanos = TimeUnit.MILLISECONDS.toNanos(quietMs)
    val deadline = TimeSource.Monotonic.markNow() + timeoutMs.milliseconds
    var size = recorded.size
    var since = TimeSource.Monotonic.markNow()
    while (since.elapsedNow().inWholeNanoseconds < quietNanos) {
      if (deadline.hasPassedNow()) {
        throw IllegalStateException(timeoutMessage("${timeoutMs}ms", "$quietMs ms without items"))
      }
      pump()
      lock.withLock { arrived.awaitNanos(POLL_NANOS) }
      if (recorded.size != size) {
        size = recorded.size
        since = TimeSource.Monotonic.markNow()
      }
    }
    return items
  }

  /**
   * Waits until the session completes or an error arrives, then returns when completed with no
   * error. Throws if an error is present when the wait ends, even when completion also became true.
   * The error is checked once as the wait returns, so it does not promise to catch an error that
   * lands after completion during a live wait. Real elapsed time.
   *
   * @throws IllegalStateException on timeout, or when an error is present at observation.
   */
  fun awaitComplete(timeoutMs: Long) =
    awaitCompleteNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs), timeoutLabel(timeoutMs))

  /**
   * [kotlin.time.Duration] overload of [awaitComplete]; positive sub-millisecond timeouts kept.
   *
   * @throws IllegalArgumentException if [timeout] is not finite or negative.
   * @throws IllegalStateException on timeout, or when an error is present at observation.
   */
  fun awaitComplete(timeout: Duration) =
    awaitCompleteNanos(timeout.requireTimeoutNanos(), timeout.toString())

  /**
   * Waits until an error or completion arrives and returns the last error message. Throws if
   * completion arrives with no error, and the last [fail] wins if several arrived.
   *
   * @throws IllegalStateException on timeout, or when completion arrives with no error.
   */
  fun awaitError(timeoutMs: Long): String =
    awaitErrorNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs), timeoutLabel(timeoutMs))

  /**
   * [kotlin.time.Duration] overload of [awaitError]; positive sub-millisecond timeouts kept.
   *
   * @throws IllegalArgumentException if [timeout] is not finite or negative.
   * @throws IllegalStateException on timeout, or when completion arrives with no error.
   */
  fun awaitError(timeout: Duration): String =
    awaitErrorNanos(timeout.requireTimeoutNanos(), timeout.toString())

  private fun timeoutLabel(timeoutMs: Long): String {
    require(timeoutMs >= 0) { "timeout must be nonnegative, was ${timeoutMs}ms" }
    return "${timeoutMs}ms"
  }

  private fun awaitNanos(
    timeoutNanos: Long,
    display: String,
    after: Int,
    predicate: (T) -> Boolean,
  ): T {
    require(after >= 0) { "after must be nonnegative, was $after" }
    val start = TimeSource.Monotonic.markNow()
    while (true) {
      // Read the volatile terminal flag first, so a match appended before the terminal still wins.
      val terminal = terminalFailure()
      recorded.drop(after).firstOrNull(predicate)?.let {
        return it
      }
      terminal?.let { throw IllegalStateException(it) }
      val elapsed = start.elapsedNow().inWholeNanoseconds
      if (elapsed >= timeoutNanos) {
        throw IllegalStateException(timeoutMessage(display, "a matching item"))
      }
      val left = timeoutNanos - elapsed
      pump()
      lock.withLock { arrived.awaitNanos(minOf(left, POLL_NANOS)) }
    }
  }

  private fun awaitCompleteNanos(timeoutNanos: Long, display: String) {
    if (!waitUntil(timeoutNanos) { completed || error != null }) {
      throw IllegalStateException(timeoutMessage(display, "completion"))
    }
    error?.let { throw IllegalStateException("error before completion: $it; got $recorded") }
  }

  private fun awaitErrorNanos(timeoutNanos: Long, display: String): String {
    if (!waitUntil(timeoutNanos) { error != null || completed }) {
      throw IllegalStateException(timeoutMessage(display, "an error"))
    }
    return error ?: throw IllegalStateException("completed without an error; got $recorded")
  }

  private fun waitUntil(timeoutNanos: Long, probe: () -> Boolean): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (true) {
      if (probe()) return true
      val elapsed = start.elapsedNow().inWholeNanoseconds
      if (elapsed >= timeoutNanos) return false
      val left = timeoutNanos - elapsed
      pump()
      lock.withLock { arrived.awaitNanos(minOf(left, POLL_NANOS)) }
    }
  }

  private fun terminalFailure(): String? =
    error?.let { "terminal error before a matching item arrived: $it; got $recorded" }
      ?: if (finished) "completed before a matching item arrived; got $recorded" else null

  private fun timeoutMessage(display: String, wanted: String): String =
    "timed out after $display waiting for $wanted; got $recorded error=$error completed=$completed"

  private fun signal() = lock.withLock { arrived.signalAll() }
}

private inline fun <reified T> typedHandler(
  crossinline onItem: (T) -> Unit,
  crossinline onFailure: (String?) -> Unit,
  crossinline onDone: () -> Unit,
): IHandler =
  object : IHandler.Stub() {
    override fun onNext(bundle: Bundle) {
      bundle.serializableFromBundle<T>()?.let { onItem(it) }
    }

    override fun onError(message: String?) = onFailure(message)

    override fun onComplete() = onDone()
  }

private fun Duration.requireTimeoutNanos(): Long {
  require(isFinite()) { "timeout must be finite, was $this" }
  require(!isNegative()) { "timeout must be nonnegative, was $this" }
  return inWholeNanoseconds
}

/** A stream handler receives decoded [StreamState]s. */
class StreamRecorder(id: String, pump: () -> Unit) : Recorder<StreamState>(id, pump) {
  /** Binder handler passed to the extension. */
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)
}

/** A view handler receives two kinds of bundles: RemoteViews frames and serialized ViewEvents. */
sealed interface ViewUpdate {
  /**
   * A rendered frame delivered under the view key.
   *
   * @property views the frame the extension sent, snapshotted at receipt so later sender mutation
   *   does not rewrite it.
   */
  data class Frame(val views: RemoteViews) : ViewUpdate

  /**
   * A serialized [ViewEvent], e.g. a custom-stream-state change.
   *
   * @property event the decoded event.
   */
  data class Event(val event: ViewEvent) : ViewUpdate
}

/**
 * A view handler records both kinds of [ViewUpdate] in arrival order. [Recorder.await]'s `after`
 * index counts across frames and events together, so checkpoint with [Recorder.items].size before
 * an expected update, not [frames].size, or an interleaved [ViewUpdate.Event] would skip the frame.
 */
class ViewRecorder(id: String, pump: () -> Unit) : Recorder<ViewUpdate>(id, pump) {
  /** Frames recorded so far, in arrival order. */
  val frames: List<RemoteViews>
    get() = items.filterIsInstance<ViewUpdate.Frame>().map { it.views }

  /** Events recorded so far, in arrival order. */
  val events: List<ViewEvent>
    get() = items.filterIsInstance<ViewUpdate.Event>().map { it.event }

  /** Binder handler passed to the extension. */
  val handler: IHandler =
    object : IHandler.Stub() {
      override fun onNext(bundle: Bundle) {
        bundle.classLoader = RemoteViews::class.java.classLoader
        val views = remoteViews(bundle)
        if (views != null) {
          record(ViewUpdate.Frame(views.snapshot()))
        } else {
          bundle.serializableFromBundle<ViewEvent>()?.let { record(ViewUpdate.Event(it)) }
        }
      }

      override fun onError(message: String?) = fail(message)

      override fun onComplete() = complete()
    }

  /**
   * Waits for the frame whose item index is at least [after], using [Recorder.await]'s timeout,
   * skip and error semantics.
   *
   * @sample fi.nikosavola.karooext.testing.samples.waitForNewFrame
   * @throws IllegalArgumentException if [timeoutMs] or [after] is negative.
   * @throws IllegalStateException on timeout, or when an error or completion arrives first.
   */
  fun awaitFrame(timeoutMs: Long, after: Int = 0): RemoteViews =
    (await(timeoutMs, after) { it is ViewUpdate.Frame } as ViewUpdate.Frame).views

  /**
   * [kotlin.time.Duration] overload of [awaitFrame].
   *
   * @sample fi.nikosavola.karooext.testing.samples.waitForNewFrame
   * @throws IllegalArgumentException if [timeout] is not finite or negative, or [after] is
   *   negative.
   * @throws IllegalStateException on timeout, or when an error or completion arrives first.
   */
  fun awaitFrame(timeout: Duration, after: Int = 0): RemoteViews =
    (await(timeout, after) { it is ViewUpdate.Frame } as ViewUpdate.Frame).views
}

/** A map handler receives decoded [MapEffect]s, e.g. ShowSymbols or ShowPolyline. */
class MapRecorder(id: String, pump: () -> Unit) : Recorder<MapEffect>(id, pump) {
  /** Effects recorded so far, in arrival order; the same log as [Recorder.items]. */
  val effects: List<MapEffect>
    get() = items

  /** Binder handler passed to the extension. */
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)

  /** Effects of [T] recorded so far, backed by [effects]. */
  inline fun <reified T : MapEffect> effectsOf(): List<T> = items.filterIsInstance<T>()

  /** Polylines still shown after replaying every show and hide in order, by id. */
  fun visiblePolylines(): Map<String, ShowPolyline> = buildMap {
    for (effect in items) {
      when (effect) {
        is ShowPolyline -> put(effect.id, effect)
        is HidePolyline -> remove(effect.id)
        else -> Unit
      }
    }
  }

  /** Symbols still shown after replaying every show and hide in order, by id. */
  fun visibleSymbols(): Map<String, Symbol> = buildMap {
    for (effect in items) {
      when (effect) {
        is ShowSymbols -> effect.symbols.forEach { put(it.id, it) }
        is HideSymbols -> effect.symbolIds.forEach { remove(it) }
        else -> Unit
      }
    }
  }
}

/** A scan handler receives decoded [Device]s advertised by the extension. */
class ScanRecorder(id: String, pump: () -> Unit) : Recorder<Device>(id, pump) {
  /** Binder handler passed to the extension. */
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)
}

/** A device handler receives decoded [DeviceEvent]s for one connection. */
class DeviceRecorder(id: String, pump: () -> Unit) : Recorder<DeviceEvent>(id, pump) {
  /** Binder handler passed to the extension. */
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)
}

/** A FIT handler receives decoded [FitEffect]s the extension wants written to the ride file. */
class FitRecorder(id: String, pump: () -> Unit) : Recorder<FitEffect>(id, pump) {
  /** Binder handler passed to the extension. */
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)

  /** Effects of [T] recorded so far, backed by [Recorder.items]. */
  inline fun <reified T : FitEffect> effectsOf(): List<T> = items.filterIsInstance<T>()
}

private fun remoteViews(bundle: Bundle): RemoteViews? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    bundle.getParcelable(VIEW_KEY, RemoteViews::class.java)
  } else {
    @Suppress("DEPRECATION") bundle.getParcelable(VIEW_KEY)
  }
