package fi.nikosavola.karooext.testing

import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.serializableFromBundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Stands in for the ride app's end of a view, stream or map: records every RemoteViews and every
 * serialized event the extension sends, the same way the Karoo would receive them.
 *
 * It keeps every frame, raw bundle and error callback in arrival order, and [events] decodes those
 * raw bundles on demand. A typed `Recorder` additionally offers completion- and error-aware waits,
 * while still recording late callbacks; this handler is the raw log for hand-rolled assertions.
 */
class CapturingHandler : IHandler.Stub() {
  /** Every frame delivered under the view key, in arrival order. A live log. */
  val views = CopyOnWriteArrayList<RemoteViews>()

  /** Every non-frame bundle, undecoded, in arrival order. A live log. */
  val bundles = CopyOnWriteArrayList<Bundle>()

  /** Every error message, with a null message stored as an empty string. */
  val errors = CopyOnWriteArrayList<String>()

  /** True once `onComplete` has been called. */
  @Volatile
  var completed = false
    private set

  /** Routes a frame to [views] and any other bundle to [bundles]. */
  override fun onNext(bundle: Bundle) {
    val view = remoteViews(bundle)
    if (view != null) views += view else bundles += bundle
  }

  /** Appends [msg] to [errors], mapping null to an empty string. */
  override fun onError(msg: String?) {
    errors += msg.orEmpty()
  }

  /** Marks the handler [completed]. */
  override fun onComplete() {
    completed = true
  }

  /**
   * Every event so far that decodes as [T] (e.g. StreamState, ViewEvent, MapEffect). Bundles that
   * do not decode as [T] are skipped.
   */
  inline fun <reified T> events(): List<T> = bundles.mapNotNull { it.serializableFromBundle<T>() }

  private fun remoteViews(bundle: Bundle): RemoteViews? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      bundle.getParcelable(VIEW_KEY, RemoteViews::class.java)
    } else {
      @Suppress("DEPRECATION") bundle.getParcelable(VIEW_KEY)
    }

  private companion object {
    const val VIEW_KEY = "view"
  }
}

/**
 * Polls [probe] until it returns non-null, calling [idle] before each try (Robolectric loopers). A
 * lighter alternative to a typed `Recorder.await` for a raw handler, e.g. a [CapturingHandler]. The
 * deadline is [TimeSource.Monotonic], so a frozen or shifted wall clock cannot cut a wait short.
 * [idle] and [probe] run at least once even for a zero timeout; sleeping is bounded by the time
 * left.
 *
 * @param T the awaited value type.
 * @param timeoutMs how long to keep polling; 20 seconds by default.
 * @param idle runs before each probe, e.g. a looper idle; no-op by default.
 * @param probe returns the awaited value or null to keep waiting.
 * @throws IllegalArgumentException if [timeoutMs] is negative.
 * @throws IllegalStateException if [probe] is still null once the timeout elapses. Exceptions from
 *   [idle] or [probe] propagate unchanged.
 */
fun <T : Any> awaitValue(
  timeoutMs: Long = 20_000,
  idle: () -> Unit = {},
  probe: () -> T?,
): T {
  require(timeoutMs >= 0) { "timeout must be nonnegative, was ${timeoutMs}ms" }
  return awaitValueNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs), "${timeoutMs}ms", idle, probe)
}

/**
 * [kotlin.time.Duration] overload of [awaitValue]; positive sub-millisecond timeouts are kept.
 *
 * @param T the awaited value type.
 * @param timeout how long to keep polling.
 * @param idle runs before each probe, e.g. a looper idle; no-op by default.
 * @param probe returns the awaited value or null to keep waiting.
 * @throws IllegalArgumentException if [timeout] is not finite or is negative.
 * @throws IllegalStateException if [probe] is still null once the timeout elapses. Exceptions from
 *   [idle] or [probe] propagate unchanged.
 */
fun <T : Any> awaitValue(
  timeout: Duration,
  idle: () -> Unit = {},
  probe: () -> T?,
): T = awaitValueNanos(timeout.requireTimeoutNanos(), timeout.toString(), idle, probe)

private fun <T : Any> awaitValueNanos(
  timeoutNanos: Long,
  display: String,
  idle: () -> Unit,
  probe: () -> T?,
): T {
  val start = TimeSource.Monotonic.markNow()
  while (true) {
    idle()
    probe()?.let {
      return it
    }
    val elapsed = start.elapsedNow().inWholeNanoseconds
    if (elapsed >= timeoutNanos) {
      error("Timed out after $display")
    }
    val left = timeoutNanos - elapsed
    TimeUnit.NANOSECONDS.sleep(minOf(left, POLL_NANOS))
  }
}

private fun Duration.requireTimeoutNanos(): Long {
  require(isFinite()) { "timeout must be finite, was $this" }
  require(!isNegative()) { "timeout must be nonnegative, was $this" }
  return inWholeNanoseconds
}

private const val POLL_NANOS = 50_000_000L
