package fi.nikosavola.karooext.testing.appstore

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.FakeKarooSystem
import fi.nikosavola.karooext.testing.KAROO_SYSTEM_PACKAGE
import fi.nikosavola.karooext.testing.KAROO_SYSTEM_SERVICE
import fi.nikosavola.karooext.testing.awaitValue
import io.hammerhead.appstore.service.AppStoreService
import io.hammerhead.karooext.EXT_LIB_VERSION
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.KarooInfo
import io.hammerhead.karooext.models.RideState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

private const val APPSTORE_AWAIT_MS = 10_000L

/**
 * Self-tests for the emulator stand-in: the bound service must hand back the process-wide
 * [FakeKaroo.system] itself, and reset must return that singleton to a clean baseline between
 * tests. The singleton is never closed, since instrumentation and the bound service share it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppStoreServiceTest {
  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var controller: ServiceController<AppStoreService>

  @Before
  fun setUp() {
    FakeKaroo.system.reset()
    controller = Robolectric.buildService(AppStoreService::class.java)
    controller.create()
  }

  @After
  fun tearDown() {
    controller.destroy()
    // Never close the process-global fake; just return it to a clean baseline.
    FakeKaroo.system.reset()
  }

  @Test
  fun `bind hands back the shared fake instance itself`() {
    assertSame(FakeKaroo.system, controller.get().onBind(Intent()))
  }

  @Test
  fun `sdk bind constants match the appstore stand-in`() {
    // Pinned independently of the manifest: these are what karoo-ext binds by name.
    assertEquals("io.hammerhead.appstore", KAROO_SYSTEM_PACKAGE)
    assertEquals("io.hammerhead.appstore.service.AppStoreService", KAROO_SYSTEM_SERVICE)
  }

  @Test
  fun `merged manifest declares the exported KarooSystem service`() {
    // The test application owns the merged library manifest, so the service is under its package.
    val component = ComponentName(app.packageName, KAROO_SYSTEM_SERVICE)
    val info = app.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
    assertEquals(KAROO_SYSTEM_SERVICE, info.name)
    assertTrue(info.exported)

    val matches =
      app.packageManager.queryIntentServices(
        Intent("KarooSystem").setPackage(app.packageName),
        PackageManager.ResolveInfoFlags.of(0),
      )
    assertTrue(matches.any { it.serviceInfo.name == KAROO_SYSTEM_SERVICE })
  }

  @Test
  fun `karoo-ext binding reaches the singleton with the fake defaults`() {
    shadowOf(app)
      .setComponentNameAndServiceForBindService(
        ComponentName(KAROO_SYSTEM_PACKAGE, KAROO_SYSTEM_SERVICE),
        FakeKaroo.system,
      )
    val service = KarooSystemService(app)
    service.connect()
    awaitValue(APPSTORE_AWAIT_MS) {
      shadowOf(Looper.getMainLooper()).idle()
      if (service.connected) true else null
    }

    assertEquals(EXT_LIB_VERSION, service.libVersion)
    assertEquals("fake", service.serial)
    assertEquals(HardwareType.KAROO, service.hardwareType)
    assertEquals(FakeKarooSystem.metricProfile(), FakeKaroo.system.userProfile)
    service.disconnect()
  }

  @Test
  fun `reset returns the singleton to defaults`() {
    FakeKaroo.system.setRideState(RideState.Recording)
    FakeKaroo.system.setLocation(1.0, 2.0)
    FakeKaroo.system.hardwareType = HardwareType.K2

    FakeKaroo.system.reset()

    assertEquals(RideState.Idle, FakeKaroo.system.rideState)
    assertNull(FakeKaroo.system.location)
    assertEquals(FakeKarooSystem.metricProfile(), FakeKaroo.system.userProfile)
    assertEquals(0, FakeKaroo.system.consumerCount)
    assertEquals(HardwareType.KAROO, FakeKaroo.system.hardwareType)
    assertEquals(
      HardwareType.KAROO,
      FakeKaroo.system.info().serializableFromBundle<KarooInfo>()!!.hardwareType,
    )
  }
}
