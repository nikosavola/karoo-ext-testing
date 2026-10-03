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
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.ViewEvent
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val VIEW_KEY = "view"
private const val POLL_MS = 20L
private val POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(POLL_MS)

/**
 * The ride-app end of the extension binder: starts streams, views, maps, scans, device connections
 * and FIT writing the way a ride page does.
 *
 * Recorded items are handed to [Recorder]s, whose waits measure real elapsed time; `pump` is
 * invoked between polls so work the extension posts to the main thread still runs while the test
 * thread blocks. Robolectric tests pass a main-looper idle as the pump; a plain JVM test can leave
 * it empty.
 *
 * The host owns every session it starts and is [Closeable]: closing stops all of them, even when
 * one stop fails, and refuses to start anything new afterwards.
 *
 * @param extension the extension end handed back by the bound service.
 * @param pump runs before each recorder poll to drain the main thread; no-op by default.
 */
@Suppress("TooManyFunctions")
class FakeKarooHost(private val extension: IKarooExtension, private val pump: () -> Unit = {}) :
  Closeable {
  private val lock = ReentrantLock()
  private val sessions = LinkedHashMap<String, () -> Unit>()
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

  fun stopView(recorder: ViewRecorder) =
    stopSession(recorder.id) { extension.stopView(recorder.id) }

  fun startMap(): MapRecorder {
    val recorder = MapRecorder(UUID.randomUUID().toString(), pump)
    return start(recorder, { extension.startMap(recorder.id, recorder.handler) }) {
      extension.stopMap(recorder.id)
    }
  }

  fun stopMap(recorder: MapRecorder) = stopSession(recorder.id) { extension.stopMap(recorder.id) }

  fun startScan(): ScanRecorder {
    val recorder = ScanRecorder(UUID.randomUUID().toString(), pump)
    return start(recorder, { extension.startScan(recorder.id, recorder.handler) }) {
      extension.stopScan(recorder.id)
    }
  }

  fun stopScan(recorder: ScanRecorder) =
    stopSession(recorder.id) { extension.stopScan(recorder.id) }

  fun connectDevice(uid: String): DeviceRecorder {
    val recorder = DeviceRecorder(UUID.randomUUID().toString(), pump)
    return start(
      recorder,
      { extension.connectDevice(recorder.id, uid, recorder.handler) },
    ) {
      extension.disconnectDevice(recorder.id)
    }
  }

  fun disconnectDevice(recorder: DeviceRecorder) =
    stopSession(recorder.id) { extension.disconnectDevice(recorder.id) }

  fun startFit(): FitRecorder {
    val recorder = FitRecorder(UUID.randomUUID().toString(), pump)
    return start(recorder, { extension.startFit(recorder.id, recorder.handler) }) {
      extension.stopFit(recorder.id)
    }
  }

  fun stopFit(recorder: FitRecorder) = stopSession(recorder.id) { extension.stopFit(recorder.id) }

  /** Sends a bonus action to the extension, like a controller button assigned to it. */
  fun bonusAction(actionId: String) {
    lock.withLock {
      checkOpenLocked()
      extension.onBonusAction(actionId)
    }
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

/** Records everything sent to one handler and lets a test wait for a matching item. */
@Suppress("TooManyFunctions")
open class Recorder<T>(val id: String, private val pump: () -> Unit) {
  private val lock = ReentrantLock()
  private val arrived = lock.newCondition()
  private val recorded = CopyOnWriteArrayList<T>()

  @Volatile private var finished = false

  /** Snapshot of everything recorded so far, in arrival order. */
  val items: List<T>
    get() = recorded.toList()

  @Volatile
  var error: String? = null
    private set

  /** True once the extension completed this session. */
  val completed: Boolean
    get() = finished

  protected fun record(item: T) {
    recorded += item
    signal()
  }

  /**
   * Records a terminal error. A null message is stored as an empty string so the error still counts
   * as terminal and wakes waiters.
   */
  protected fun fail(message: String?) {
    error = message.orEmpty()
    signal()
  }

  protected fun complete() {
    finished = true
    signal()
  }

  /**
   * Waits for an item matching [predicate] among those that arrived after index [after]. The
   * timeout is real elapsed time, not Robolectric or coroutine virtual time, and the pump runs
   * between polls.
   */
  fun await(timeoutMs: Long, after: Int = 0, predicate: (T) -> Boolean): T =
    awaitNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs), timeoutLabel(timeoutMs), after, predicate)

  /** [kotlin.time.Duration] overload of [await]; positive sub-millisecond timeouts are kept. */
  fun await(timeout: Duration, after: Int = 0, predicate: (T) -> Boolean): T =
    awaitNanos(timeout.requireTimeoutNanos(), timeout.toString(), after, predicate)

  /** Waits until the extension completes, failing early on a terminal error. Real elapsed time. */
  fun awaitComplete(timeoutMs: Long) =
    awaitCompleteNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs), timeoutLabel(timeoutMs))

  fun awaitComplete(timeout: Duration) =
    awaitCompleteNanos(timeout.requireTimeoutNanos(), timeout.toString())

  /** Waits until the extension errors, returning its message; fails on completion or timeout. */
  fun awaitError(timeoutMs: Long): String =
    awaitErrorNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs), timeoutLabel(timeoutMs))

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
      recorded.drop(after).firstOrNull(predicate)?.let {
        return it
      }
      terminalFailure()?.let { throw IllegalStateException(it) }
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

class StreamRecorder(id: String, pump: () -> Unit) : Recorder<StreamState>(id, pump) {
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)
}

/** A view handler receives two kinds of bundles: RemoteViews frames and serialized ViewEvents. */
sealed interface ViewUpdate {
  data class Frame(val views: RemoteViews) : ViewUpdate

  data class Event(val event: ViewEvent) : ViewUpdate
}

class ViewRecorder(id: String, pump: () -> Unit) : Recorder<ViewUpdate>(id, pump) {
  val frames: List<RemoteViews>
    get() = items.filterIsInstance<ViewUpdate.Frame>().map { it.views }

  val events: List<ViewEvent>
    get() = items.filterIsInstance<ViewUpdate.Event>().map { it.event }

  val handler: IHandler =
    object : IHandler.Stub() {
      override fun onNext(bundle: Bundle) {
        bundle.classLoader = RemoteViews::class.java.classLoader
        val views = remoteViews(bundle)
        if (views != null) {
          record(ViewUpdate.Frame(views))
        } else {
          bundle.serializableFromBundle<ViewEvent>()?.let { record(ViewUpdate.Event(it)) }
        }
      }

      override fun onError(message: String?) = fail(message)

      override fun onComplete() = complete()
    }

  fun awaitFrame(timeoutMs: Long, after: Int = 0): RemoteViews =
    (await(timeoutMs, after) { it is ViewUpdate.Frame } as ViewUpdate.Frame).views

  fun awaitFrame(timeout: Duration, after: Int = 0): RemoteViews =
    (await(timeout, after) { it is ViewUpdate.Frame } as ViewUpdate.Frame).views
}

/** A map handler receives decoded [MapEffect]s, e.g. ShowSymbols or ShowPolyline. */
class MapRecorder(id: String, pump: () -> Unit) : Recorder<MapEffect>(id, pump) {
  val effects: List<MapEffect>
    get() = items

  val handler: IHandler = typedHandler(::record, ::fail, ::complete)

  inline fun <reified T : MapEffect> effectsOf(): List<T> = items.filterIsInstance<T>()
}

/** A scan handler receives decoded [Device]s advertised by the extension. */
class ScanRecorder(id: String, pump: () -> Unit) : Recorder<Device>(id, pump) {
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)
}

/** A device handler receives decoded [DeviceEvent]s for one connection. */
class DeviceRecorder(id: String, pump: () -> Unit) : Recorder<DeviceEvent>(id, pump) {
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)
}

/** A FIT handler receives decoded [FitEffect]s the extension wants written to the ride file. */
class FitRecorder(id: String, pump: () -> Unit) : Recorder<FitEffect>(id, pump) {
  val handler: IHandler = typedHandler(::record, ::fail, ::complete)

  inline fun <reified T : FitEffect> effectsOf(): List<T> = items.filterIsInstance<T>()
}

private fun remoteViews(bundle: Bundle): RemoteViews? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    bundle.getParcelable(VIEW_KEY, RemoteViews::class.java)
  } else {
    @Suppress("DEPRECATION") bundle.getParcelable(VIEW_KEY)
  }
