package fi.nikosavola.karooext.testing.robolectric

import fi.nikosavola.karooext.testing.KAROO_SYSTEM_PACKAGE
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioRuleTest {
  private fun dispatch(karoo: FakeKarooRule, effect: KarooEffect) =
    karoo.system.dispatchEffect(effect.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))

  @Test
  fun `closing fails when a claim is still open`() {
    val karoo = FakeKarooRule(requireReleasedRadios = true)
    dispatch(karoo, RequestBluetooth("light"))

    val error = assertThrows(AssertionError::class.java) { karoo.close() }

    assertTrue(error.message!!.contains("light"))
  }

  @Test
  fun `closing passes when every claim was released`() {
    val karoo = FakeKarooRule(requireReleasedRadios = true)
    dispatch(karoo, RequestBluetooth("light"))
    dispatch(karoo, ReleaseBluetooth("light"))

    karoo.close()
  }

  @Test
  fun `the check is off unless asked for`() {
    val karoo = FakeKarooRule()
    dispatch(karoo, RequestBluetooth("light"))

    karoo.close()
  }
}
