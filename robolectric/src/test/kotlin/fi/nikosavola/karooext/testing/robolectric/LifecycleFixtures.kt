package fi.nikosavola.karooext.testing.robolectric

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.MapEffect
import java.util.concurrent.CopyOnWriteArrayList

/** A minimal extension that records its own teardown and scan cancellation. */
class LifecycleExtension : KarooExtension("lifecycle", "1.0") {
  override fun startScan(emitter: Emitter<Device>) {
    emitter.onNext(Device(extension, "lifecycle-sensor", emptyList(), "Lifecycle sensor"))
    emitter.setCancellable { cancelled += "scan" }
  }

  override fun onDestroy() {
    destroyed += 1
    super.onDestroy()
  }

  companion object {
    val cancelled = CopyOnWriteArrayList<String>()

    @Volatile
    var destroyed = 0
      private set

    fun reset() {
      cancelled.clear()
      destroyed = 0
    }
  }
}

/** A service whose onBind throws, to exercise host() cleanup when a service cannot be bound. */
class ThrowingBindService : Service() {
  override fun onBind(intent: Intent): IBinder = error("broken bind")

  override fun onDestroy() {
    destroyed += 1
    super.onDestroy()
  }

  companion object {
    @Volatile
    var destroyed = 0
      private set

    fun reset() {
      destroyed = 0
    }
  }
}

/** A service whose onCreate throws, to exercise host() cleanup when creation fails partway. */
class ThrowingCreateService : Service() {
  override fun onCreate() {
    throw IllegalStateException("create boom")
  }

  override fun onBind(intent: Intent): IBinder = error("no bind")

  override fun onDestroy() {
    destroyed += 1
    super.onDestroy()
  }

  companion object {
    @Volatile
    var destroyed = 0
      private set

    fun reset() {
      destroyed = 0
    }
  }
}

/** An extension whose scan cancellable throws, while its map cancellable still cancels cleanly. */
class ThrowingCancellableExtension : KarooExtension("throwing-cancel", "1.0") {
  override fun startScan(emitter: Emitter<Device>) {
    emitter.setCancellable { throw cancelBoom }
  }

  override fun startMap(emitter: Emitter<MapEffect>) {
    emitter.setCancellable { cancelled += "map" }
  }

  override fun onDestroy() {
    destroyed += 1
    // Posted work must still run from close()'s pump, even after a host failure.
    Handler(Looper.getMainLooper()).post { cleanupRan += 1 }
    super.onDestroy()
  }

  companion object {
    val cancelBoom = IllegalStateException("cancel boom")
    val cancelled = CopyOnWriteArrayList<String>()

    @Volatile
    var destroyed = 0
      private set

    @Volatile
    var cleanupRan = 0
      private set

    fun reset() {
      cancelled.clear()
      destroyed = 0
      cleanupRan = 0
    }
  }
}

/** A service whose destroy throws, to exercise rule.close() suppression of a later failure. */
class ThrowingDestroyExtension : KarooExtension("throwing-destroy", "1.0") {
  override fun onDestroy() {
    destroyed += 1
    super.onDestroy()
    throw destroyBoom
  }

  companion object {
    val destroyBoom = IllegalStateException("destroy boom")

    @Volatile
    var destroyed = 0
      private set

    fun reset() {
      destroyed = 0
    }
  }
}

/** A plain service whose bind fails and whose destroy also throws during host() cleanup. */
class ThrowingBindCleanupService : Service() {
  override fun onBind(intent: Intent): IBinder = error("bind boom")

  override fun onDestroy() {
    destroyed += 1
    super.onDestroy()
    throw destroyBoom
  }

  companion object {
    val destroyBoom = IllegalStateException("destroy boom")

    @Volatile
    var destroyed = 0
      private set

    fun reset() {
      destroyed = 0
    }
  }
}

/** A plain service whose create fails and whose destroy also throws during host() cleanup. */
class ThrowingCreateCleanupService : Service() {
  override fun onCreate() {
    throw IllegalStateException("create boom")
  }

  override fun onBind(intent: Intent): IBinder = error("no bind")

  override fun onDestroy() {
    destroyed += 1
    super.onDestroy()
    throw destroyBoom
  }

  companion object {
    val destroyBoom = IllegalStateException("destroy boom")

    @Volatile
    var destroyed = 0
      private set

    fun reset() {
      destroyed = 0
    }
  }
}

/** A service whose bind and destroy throw the same instance, so cleanup must not self-suppress. */
class SelfSuppressService : Service() {
  override fun onBind(intent: Intent): IBinder = throw sameBoom

  override fun onDestroy() {
    super.onDestroy()
    throw sameBoom
  }

  companion object {
    val sameBoom = IllegalStateException("same boom")
  }
}

/** Extension whose cancel and destroy throw one instance, for rule.close self-suppression. */
class SelfSuppressExtension : KarooExtension("self-suppress", "1.0") {
  override fun startScan(emitter: Emitter<Device>) {
    emitter.setCancellable { throw sameBoom }
  }

  override fun onDestroy() {
    super.onDestroy()
    throw sameBoom
  }

  companion object {
    val sameBoom = IllegalStateException("same boom")
  }
}
