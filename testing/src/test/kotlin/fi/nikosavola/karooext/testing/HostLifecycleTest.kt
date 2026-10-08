package fi.nikosavola.karooext.testing

import android.os.Bundle
import android.os.RemoteException
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooExtension
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val THROWING_STREAM_TYPE = "throwing-value"
private const val START_STREAM_PREFIX = "startStream:"
private const val STOP_STREAM_PREFIX = "stopStream:"

/** Records the calls the host makes, and can be told which calls fail like a dead binder. */
private class RecordingExtension : IKarooExtension.Stub() {
  val calls = CopyOnWriteArrayList<String>()
  val failingPrefixes = CopyOnWriteArrayList<String>()
  val mapHandlers = CopyOnWriteArrayList<IHandler>()

  /** When true, failed calls throw one shared instance, to exercise self-suppression guards. */
  var sharedFailure = false

  /** Runs inside startStream before it returns, e.g. to hold the host lock in a test. */
  @Volatile var onStartStream: (() -> Unit)? = null

  private fun record(call: String) {
    calls += call
    if (failingPrefixes.any { call.startsWith(it) }) {
      throw if (sharedFailure) SHARED_FAILURE else RemoteException("boom: $call")
    }
  }

  override fun libVersion(): String = "test"

  override fun startScan(id: String, handler: IHandler) = record("startScan:$id")

  override fun stopScan(id: String) = record("stopScan:$id")

  override fun connectDevice(id: String, uid: String, handler: IHandler) =
    record("connectDevice:$id:$uid")

  override fun disconnectDevice(id: String) = record("disconnectDevice:$id")

  override fun startStream(id: String, typeId: String, handler: IHandler) {
    record("startStream:$id:$typeId")
    onStartStream?.invoke()
  }

  override fun stopStream(id: String) = record("stopStream:$id")

  override fun startView(id: String, typeId: String, config: Bundle, handler: IHandler) =
    record("startView:$id:$typeId")

  override fun stopView(id: String) = record("stopView:$id")

  override fun startMap(id: String, handler: IHandler) {
    mapHandlers += handler
    record("startMap:$id")
  }

  override fun stopMap(id: String) = record("stopMap:$id")

  override fun startFit(id: String, handler: IHandler) = record("startFit:$id")

  override fun stopFit(id: String) = record("stopFit:$id")

  override fun onBonusAction(actionid: String) = record("onBonusAction:$actionid")

  companion object {
    val SHARED_FAILURE = RemoteException("shared failure")
  }
}

private fun stopStreams(extension: RecordingExtension): Int =
  extension.calls.count { it.startsWith(STOP_STREAM_PREFIX) }

/** A real KarooExtension whose scan and stream starts throw after the SDK stored an emitter. */
class ThrowingStartExtension : KarooExtension("throwing", "1.0") {
  override val types: List<DataTypeImpl> = listOf(ThrowingType(extension))

  override fun startScan(emitter: Emitter<Device>) {
    emitter.setCancellable {
      cancelled += "scan"
      if (stopThrows) throw RemoteException("stop boom")
    }
    throw IllegalStateException("scan boom")
  }

  companion object {
    val cancelled = CopyOnWriteArrayList<String>()

    @Volatile var stopThrows = false

    fun reset() {
      cancelled.clear()
      stopThrows = false
    }
  }
}

private class ThrowingType(extension: String) : DataTypeImpl(extension, THROWING_STREAM_TYPE) {
  override fun startStream(emitter: Emitter<StreamState>) {
    emitter.setCancellable {
      ThrowingStartExtension.cancelled += "stream"
      if (ThrowingStartExtension.stopThrows) throw RemoteException("stop boom")
    }
    throw IllegalStateException("stream boom")
  }
}

/** Installs its scan cancellable only after its first emit, the way real extensions do. */
class ReentrantCloseExtension : KarooExtension("reentrant", "1.0") {
  override fun startScan(emitter: Emitter<Device>) {
    emitter.onNext(Device(extension, "reentrant-sensor", emptyList(), "Reentrant sensor"))
    onEmit?.invoke()
    emitter.setCancellable { cancelled += "scan" }
  }

  companion object {
    val cancelled = CopyOnWriteArrayList<String>()

    @Volatile var onEmit: (() -> Unit)? = null

    fun reset() {
      cancelled.clear()
      onEmit = null
    }
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostLifecycleTest {
  @get:Rule val karoo = FakeKarooRule()

  @Before
  fun resetFixtures() {
    ThrowingStartExtension.reset()
    ReentrantCloseExtension.reset()
  }

  @Test
  fun `layers drawn in one map session stay visible in the next unless hidden`() {
    val extension = RecordingExtension()
    val host = FakeKarooHost(extension)
    host.startMap()
    host.startMap()

    fun send(session: Int, effect: MapEffect) =
      extension.mapHandlers[session].onNext(effect.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))
    val line = { id: String -> ShowPolyline(id, encodePolyline(listOf(1.0 to 2.0)), 0, 4) }

    send(0, line("route"))
    send(0, line("rejoin"))
    send(1, HidePolyline("rejoin"))
    send(1, ShowSymbols(listOf(Symbol.POI("p", 1.0, 2.0))))

    assertEquals(setOf("route"), host.visiblePolylines().keys)
    assertEquals(setOf("p"), host.visibleSymbols().keys)
    host.close()
  }

  @Test
  fun `close stops every session and is idempotent`() {
    val extension = RecordingExtension()
    val host = FakeKarooHost(extension)
    val stream = host.startStream(SAMPLE_STREAM_TYPE)
    val map = host.startMap()
    host.close()
    assertTrue(extension.calls.contains("stopStream:${stream.id}"))
    assertTrue(extension.calls.contains("stopMap:${map.id}"))
    host.close()
    assertEquals(1, extension.calls.count { it == "stopStream:${stream.id}" })
  }

  @Test
  fun `an individually stopped session is not stopped again by close`() {
    val extension = RecordingExtension()
    val host = FakeKarooHost(extension)
    val scan = host.startScan()
    host.stopScan(scan)
    host.close()
    assertEquals(1, extension.calls.count { it == "stopScan:${scan.id}" })
  }

  @Test
  fun `close attempts every stop and suppresses later failures`() {
    val extension = RecordingExtension()
    val host = FakeKarooHost(extension)
    val first = host.startStream("a")
    val second = host.startStream("b")
    val map = host.startMap()
    extension.failingPrefixes += "stopStream:${first.id}"
    extension.failingPrefixes += "stopStream:${second.id}"

    val failure = assertThrows(RemoteException::class.java) { host.close() }

    assertTrue(extension.calls.contains("stopStream:${first.id}"))
    assertTrue(extension.calls.contains("stopStream:${second.id}"))
    assertTrue(extension.calls.contains("stopMap:${map.id}"))
    assertEquals(1, failure.suppressed.size)
  }

  @Test
  fun `a stop failure repeated as the same throwable is not self-suppressed`() {
    val extension = RecordingExtension()
    val host = FakeKarooHost(extension)
    val first = host.startStream("a")
    val second = host.startStream("b")
    extension.failingPrefixes += "stopStream:${first.id}"
    extension.failingPrefixes += "stopStream:${second.id}"
    extension.sharedFailure = true

    val failure = assertThrows(RemoteException::class.java) { host.close() }

    assertEquals(0, failure.suppressed.size)
    assertTrue(extension.calls.contains("stopStream:${second.id}"))
  }

  @Test
  fun `a failed start is stopped once but not tracked for close`() {
    val extension = RecordingExtension()
    val host = FakeKarooHost(extension)
    extension.failingPrefixes += START_STREAM_PREFIX
    assertThrows(RemoteException::class.java) { host.startStream(SAMPLE_STREAM_TYPE) }
    // The failed start runs its matching stop once; close must not stop it again.
    host.close()
    assertEquals(1, stopStreams(extension))
  }

  @Test
  fun `no new sessions or bonus actions after close`() {
    val extension = RecordingExtension()
    val host = FakeKarooHost(extension)
    host.close()
    assertThrows(IllegalStateException::class.java) { host.startStream(SAMPLE_STREAM_TYPE) }
    assertThrows(IllegalStateException::class.java) { host.bonusAction(SAMPLE_BONUS_ACTION) }
    assertTrue(extension.calls.none { it.startsWith(START_STREAM_PREFIX) })
    assertTrue(extension.calls.none { it.startsWith("onBonusAction") })
  }

  @Test
  fun `close serializes with an admitted start and still stops it`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val extension = RecordingExtension()
    extension.onStartStream = {
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
    }
    val host = FakeKarooHost(extension)
    val starter = thread { host.startStream(SAMPLE_STREAM_TYPE) }
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    val closer = thread { host.close() }
    release.countDown()
    starter.join(5_000)
    closer.join(5_000)
    assertFalse(starter.isAlive)
    assertFalse(closer.isAlive)
    assertEquals(1, stopStreams(extension))
  }

  @Test
  fun `a reentrant close during a start stops the installed session and retains nothing`() {
    val extension = RecordingExtension()
    val host = FakeKarooHost(extension)
    extension.onStartStream = { host.close() }
    host.startStream(SAMPLE_STREAM_TYPE)
    val id =
      extension.calls
        .first { it.startsWith(START_STREAM_PREFIX) }
        .substringAfter(START_STREAM_PREFIX)
        .substringBefore(':')
    assertTrue(extension.calls.contains("stopStream:$id"))
    host.close()
    assertEquals(1, stopStreams(extension))
  }

  @Test
  fun `a reentrant close before the extension installs its callback still cancels it`() {
    val host = karoo.host<ReentrantCloseExtension>()
    ReentrantCloseExtension.onEmit = { host.close() }
    host.startScan()
    assertTrue(ReentrantCloseExtension.cancelled.contains("scan"))
    host.close()
    assertEquals(1, ReentrantCloseExtension.cancelled.size)
  }

  @Test
  fun `a failed real start cancels the emitter the SDK already stored`() {
    val host = karoo.host<ThrowingStartExtension>()
    val failure = assertThrows(IllegalStateException::class.java) { host.startScan() }
    assertEquals("scan boom", failure.message)
    assertEquals(0, failure.suppressed.size)
    assertTrue(ThrowingStartExtension.cancelled.contains("scan"))
  }

  @Test
  fun `a failed real start keeps the original error and suppresses the cleanup error`() {
    ThrowingStartExtension.stopThrows = true
    val host = karoo.host<ThrowingStartExtension>()
    val failure =
      assertThrows(IllegalStateException::class.java) { host.startStream(THROWING_STREAM_TYPE) }
    assertEquals("stream boom", failure.message)
    assertEquals(1, failure.suppressed.size)
    assertEquals("stop boom", failure.suppressed.single().message)
    assertTrue(ThrowingStartExtension.cancelled.contains("stream"))
  }

  @Test
  fun `describe counts each recorder's items by type`() {
    val host = FakeKarooHost(RecordingExtension())
    val stream = host.startStream("a")
    repeat(2) {
      stream.handler.onNext(
        (StreamState.Searching as StreamState).bundleWithSerializable(KAROO_SYSTEM_PACKAGE)
      )
    }
    host.startMap()
    host.close()

    val text = host.describe()
    assertTrue(text, text.contains("StreamRecorder: Searching x2"))
    assertTrue(text, text.contains("MapRecorder: no items"))
  }
}
