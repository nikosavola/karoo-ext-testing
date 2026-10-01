package fi.nikosavola.karooext.testing.robolectric

import fi.nikosavola.karooext.testing.awaitValue
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HardwareType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FakeKarooRuleTest {
  @get:Rule val karoo = FakeKarooRule()

  @Test
  fun `binding points KarooSystemService at the fake`() {
    val service = KarooSystemService(karoo.app)
    service.connect()
    awaitValue(timeoutMs = 5_000) {
      RobolectricPump.pumpMainLooper()
      if (service.connected) true else null
    }
    assertEquals(HardwareType.KAROO, service.hardwareType)
    service.disconnect()
  }

  @Test
  fun `pump advances the main looper without throwing`() {
    RobolectricPump.pumpMainLooper()
  }
}
