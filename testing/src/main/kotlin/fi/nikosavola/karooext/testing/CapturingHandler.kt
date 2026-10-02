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
 */
class CapturingHandler : IHandler.Stub() {
  val views = CopyOnWriteArrayList<RemoteViews>()
  val bundles = CopyOnWriteArrayList<Bundle>()
  val errors = CopyOnWriteArrayList<String>()

  @Volatile
  var completed = false
    private set

  override fun onNext(bundle: Bundle) {
    val view = remoteViews(bundle)
    if (view != null) views += view else bundles += bundle
  }

  override fun onError(msg: String?) {
    errors += msg.orEmpty()
  }

  override fun onComplete() {
    completed = true
  }

  /** Every event so far that decodes as [T] (e.g. StreamState, ViewEvent, MapEffect). */
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
 * Polls [probe] until it returns non-null, calling [idle] before each try (Robolectric loopers).
 * The deadline is [TimeSource.Monotonic], so a frozen or shifted wall clock cannot cut a wait
 * short. [idle] and [probe] run at least once even for a zero timeout; sleeping is bounded by the
 * time left.
 */
fun <T : Any> awaitValue(
  timeoutMs: Long = 20_000,
  idle: () -> Unit = {},
  probe: () -> T?,
): T {
  require(timeoutMs >= 0) { "timeout must be nonnegative, was ${timeoutMs}ms" }
  return awaitValueNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs), "${timeoutMs}ms", idle, probe)
}

/** [kotlin.time.Duration] overload of [awaitValue]; positive sub-millisecond timeouts are kept. */
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
