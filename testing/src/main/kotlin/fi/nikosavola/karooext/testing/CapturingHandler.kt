package fi.nikosavola.karooext.testing

import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.serializableFromBundle
import java.util.concurrent.CopyOnWriteArrayList

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

/** Polls [probe] until it returns non-null, calling [idle] between tries (Robolectric loopers). */
fun <T : Any> awaitValue(
  timeoutMs: Long = 20_000,
  idle: () -> Unit = {},
  probe: () -> T?,
): T {
  val deadline = System.currentTimeMillis() + timeoutMs
  while (System.currentTimeMillis() < deadline) {
    idle()
    probe()?.let {
      return it
    }
    Thread.sleep(POLL_MS)
  }
  error("Timed out after $timeoutMs ms")
}

private const val POLL_MS = 50L
