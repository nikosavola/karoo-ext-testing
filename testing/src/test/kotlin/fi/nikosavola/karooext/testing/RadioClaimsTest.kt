package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.ReleaseAnt
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestAnt
import io.hammerhead.karooext.models.RequestBluetooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioClaimsTest {
  private val system = FakeKarooSystem()

  private fun dispatch(vararg effects: KarooEffect) = effects.forEach {
    system.dispatchEffect(it.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))
  }

  @Test
  fun `a request is held until its release`() {
    dispatch(RequestBluetooth("a"), RequestBluetooth("b"), RequestAnt("radar"))
    assertEquals(setOf("a", "b"), system.bluetoothClaims)
    assertEquals(setOf("radar"), system.antClaims)

    dispatch(ReleaseBluetooth("a"), ReleaseAnt("radar"))
    assertEquals(setOf("b"), system.bluetoothClaims)
    assertTrue(system.antClaims.isEmpty())
  }

  @Test
  fun `balanced requests pass the assertion`() {
    dispatch(RequestBluetooth("a"), ReleaseBluetooth("a"), RequestAnt("x"), ReleaseAnt("x"))
    system.assertRadiosReleased()
  }

  @Test
  fun `a leaked claim fails the assertion and names the resource`() {
    dispatch(RequestBluetooth("leak"))

    val error = assertThrows(AssertionError::class.java) { system.assertRadiosReleased() }

    assertTrue(error.message!!.contains("bluetooth:leak"))
  }

  @Test
  fun `a release without a request fails the assertion`() {
    dispatch(ReleaseAnt("ghost"))

    val error = assertThrows(AssertionError::class.java) { system.assertRadiosReleased() }

    assertTrue(error.message!!.contains("ant:ghost"))
  }

  @Test
  fun `the same id is tracked separately per radio`() {
    dispatch(RequestBluetooth("shared"), ReleaseAnt("shared"))
    assertEquals(setOf("shared"), system.bluetoothClaims)
  }

  @Test
  fun `describe lists open claims`() {
    dispatch(RequestBluetooth("a"))
    assertTrue(system.describe().contains("bluetooth=[a]"))
  }
}
