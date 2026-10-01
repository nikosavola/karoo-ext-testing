package fi.nikosavola.karooext.testing

import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooExtension
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.ViewEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val VIEW_KEY = "view"
private const val POLL_MS = 20L

/**
 * The ride-app end of the extension binder: starts streams, views and maps like a ride page does.
 * [pump] runs while a recorder waits; Robolectric tests pass a main-looper idle so work posted to
 * the main thread still runs while the test thread blocks.
 */
class FakeKarooHost(private val extension: IKarooExtension, private val pump: () -> Unit = {}) {
  fun startStream(typeId: String): StreamRecorder =
    StreamRecorder(UUID.randomUUID().toString(), pump).also {
      extension.startStream(it.id, typeId, it.handler)
    }

  fun stopStream(recorder: StreamRecorder) = extension.stopStream(recorder.id)

  fun startView(typeId: String, config: ViewConfig): ViewRecorder =
    ViewRecorder(UUID.randomUUID().toString(), pump).also {
      extension.startView(
        it.id,
        typeId,
        config.bundleWithSerializable(KAROO_SYSTEM_PACKAGE),
        it.handler,
      )
    }

  fun stopView(recorder: ViewRecorder) = extension.stopView(recorder.id)

  fun startMap(): MapRecorder =
    MapRecorder(UUID.randomUUID().toString(), pump).also { extension.startMap(it.id, it.handler) }

  fun stopMap(recorder: MapRecorder) = extension.stopMap(recorder.id)
}

/** Records everything sent to one handler and lets a test wait for a matching item. */
open class Recorder<T>(val id: String, private val pump: () -> Unit) {
  private val lock = ReentrantLock()
  private val arrived = lock.newCondition()
  val items: MutableList<T> = CopyOnWriteArrayList()

  @Volatile var error: String? = null

  protected fun record(item: T) {
    items.add(item)
    lock.withLock { arrived.signalAll() }
  }

  /** Waits for an item matching [predicate] among those that arrived after index [after]. */
  fun await(timeoutMs: Long, after: Int = 0, predicate: (T) -> Boolean): T {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (true) {
      items.drop(after).firstOrNull(predicate)?.let {
        return it
      }
      val left = deadline - System.nanoTime()
      check(left > 0) { "timed out after ${timeoutMs}ms; got $items error=$error" }
      pump()
      lock.withLock {
        arrived.await(minOf(left, TimeUnit.MILLISECONDS.toNanos(POLL_MS)), TimeUnit.NANOSECONDS)
      }
    }
  }
}

class StreamRecorder(id: String, pump: () -> Unit) : Recorder<StreamState>(id, pump) {
  val handler: IHandler =
    object : IHandler.Stub() {
      override fun onNext(bundle: Bundle) {
        bundle.serializableFromBundle<StreamState>()?.let(::record)
      }

      override fun onError(message: String?) {
        error = message
      }

      override fun onComplete() = Unit
    }
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

      override fun onError(message: String?) {
        error = message
      }

      override fun onComplete() = Unit
    }

  fun awaitFrame(timeoutMs: Long, after: Int = 0): RemoteViews =
    (await(timeoutMs, after) { it is ViewUpdate.Frame } as ViewUpdate.Frame).views
}

/** A map handler receives decoded [MapEffect]s, e.g. ShowSymbols or ShowPolyline. */
class MapRecorder(id: String, pump: () -> Unit) : Recorder<MapEffect>(id, pump) {
  val effects: List<MapEffect>
    get() = items

  val handler: IHandler =
    object : IHandler.Stub() {
      override fun onNext(bundle: Bundle) {
        bundle.serializableFromBundle<MapEffect>()?.let(::record)
      }

      override fun onError(message: String?) {
        error = message
      }

      override fun onComplete() = Unit
    }

  inline fun <reified T : MapEffect> effectsOf(): List<T> = items.filterIsInstance<T>()
}

private fun remoteViews(bundle: Bundle): RemoteViews? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    bundle.getParcelable(VIEW_KEY, RemoteViews::class.java)
  } else {
    @Suppress("DEPRECATION") bundle.getParcelable(VIEW_KEY)
  }
