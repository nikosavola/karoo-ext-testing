package fi.nikosavola.karooext.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProfileTest {
  @Test
  fun `the default profile has seven power zones and five heart rate zones`() {
    val profile = FakeKarooSystem.metricProfile()

    assertEquals(7, profile.powerZones.size)
    assertEquals(5, profile.heartRateZones.size)
    assertEquals(0, profile.powerZones.first().min)
  }

  @Test
  fun `zones follow each other without gaps and the last one is open`() {
    val profile = FakeKarooSystem.imperialProfile()

    listOf(profile.powerZones, profile.heartRateZones).forEach { zones ->
      zones.zipWithNext().forEach { (a, b) -> assertEquals(a.max + 1, b.min) }
      assertTrue(zones.last().max > 1_000)
    }
  }

  @Test
  fun `a reading finds its power zone`() {
    val zones = FakeKarooSystem.metricProfile().powerZones

    assertEquals(3, zones.indexOfFirst { 250 in it.min..it.max })
    assertEquals(6, zones.indexOfFirst { 600 in it.min..it.max })
  }
}
