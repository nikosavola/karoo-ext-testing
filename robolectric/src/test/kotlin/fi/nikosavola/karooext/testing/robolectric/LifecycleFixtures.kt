package fi.nikosavola.karooext.testing.robolectric

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Device
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
