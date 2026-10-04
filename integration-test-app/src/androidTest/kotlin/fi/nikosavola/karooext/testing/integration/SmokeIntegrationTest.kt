package fi.nikosavola.karooext.testing.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.appstore.FakeKaroo
import fi.nikosavola.karooext.testing.awaitValue
import fi.nikosavola.karooext.testing.inflate
import fi.nikosavola.karooext.testing.texts
import io.hammerhead.karooext.aidl.IKarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val AWAIT_MS = 15_000L

@RunWith(AndroidJUnit4::class)
class SmokeIntegrationTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private var connection: ServiceConnection? = null
  private var host: FakeKarooHost? = null

  @Before
  fun setUp() {
    FakeKaroo.system.reset()
  }

  @After
  fun tearDown() {
    try {
      host?.close()
    } finally {
      host = null
      try {
        connection?.let { context.unbindService(it) }
      } finally {
        connection = null
        FakeKaroo.system.reset()
      }
    }
  }

  private fun connect(): FakeKarooHost {
    val connected = CountDownLatch(1)
    val binderRef = AtomicReference<IBinder>()
    val conn =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
          binderRef.set(service)
          connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
          binderRef.set(null)
        }
      }
    val intent = Intent(context, SmokeExtension::class.java)
    assertTrue("bindService refused", context.bindService(intent, conn, Context.BIND_AUTO_CREATE))
    connection = conn
    assertTrue(
      "extension service did not connect",
      connected.await(AWAIT_MS, TimeUnit.MILLISECONDS),
    )
    val binder = binderRef.get()!!
    assertNull(
      "extension must run out of process",
      binder.queryLocalInterface(IKarooExtension.DESCRIPTOR),
    )
    return FakeKarooHost(IKarooExtension.Stub.asInterface(binder)).also { host = it }
  }

  @Test
  fun sdkBindingReachesAppstoreAndStreamsLocation() {
    val host = connect()
    val recorder = host.startStream(SMOKE_LOCATION_TYPE)
    awaitValue(AWAIT_MS) { FakeKaroo.system.consumerCount.takeIf { it > 0 } }

    FakeKaroo.system.setLocation(60.5, 24.5)

    val streaming =
      awaitValue(AWAIT_MS) { recorder.items.filterIsInstance<StreamState.Streaming>().lastOrNull() }
    assertEquals(60.5, streaming.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)
  }

  @Test
  fun stoppingTheStreamReleasesTheConsumer() {
    val host = connect()
    val recorder = host.startStream(SMOKE_LOCATION_TYPE)
    awaitValue(AWAIT_MS) { FakeKaroo.system.consumerCount.takeIf { it > 0 } }

    host.stopStream(recorder)

    awaitValue(AWAIT_MS) { if (FakeKaroo.system.consumerCount == 0) true else null }
  }

  @Test
  fun remoteViewsFrameCrossesBinderWithConfig() {
    val host = connect()
    val config =
      ViewConfig(
        gridSize = 60 to 60,
        viewSize = 60 to 90,
        textSize = 24,
        alignment = ViewConfig.Alignment.CENTER,
        boundariesEnabled = false,
        preview = false,
      )
    val recorder = host.startView(SMOKE_VIEW_TYPE, config)

    val frame = awaitValue(AWAIT_MS) { recorder.frames.lastOrNull() }
    val root = frame.inflate(context, config)
    assertEquals(60, root.measuredWidth)
    assertEquals(90, root.measuredHeight)
    assertEquals(60, root.width)
    assertEquals(90, root.height)
    val text = root.texts().single()
    assertTrue(text, text.contains("view=60x90"))
    assertTrue(text, text.contains("text=24"))
    val pid = text.substringAfter("pid=").substringBefore(' ').toInt()
    assertNotEquals(Process.myPid(), pid)
  }

  @Test
  fun scriptedHttpReturnsRecordedByteBody() {
    val host = connect()
    FakeKaroo.system.responder = HttpResponses.success(byteArrayOf(7, 8, 9))

    val recorder = host.startStream(SMOKE_HTTP_TYPE)

    val streaming =
      awaitValue(AWAIT_MS) { recorder.items.filterIsInstance<StreamState.Streaming>().lastOrNull() }
    assertEquals(3.0, streaming.dataPoint.singleValue!!, 0.0)
    val request = FakeKaroo.system.httpRequests.single()
    assertEquals(SMOKE_HTTP_URL, request.url)
    assertArrayEquals("ping".toByteArray(), request.body)
  }
}
