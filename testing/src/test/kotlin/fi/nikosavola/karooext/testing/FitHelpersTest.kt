package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.WriteToRecordMesg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FitHelpersTest {
  private fun field(name: String, number: Short = 0) = DeveloperField(number, 136, name, "count")

  private fun FitRecorder.send(effect: FitEffect) =
    handler.onNext(effect.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))

  private fun record(vararg values: Pair<String, Double>) =
    WriteToRecordMesg(
      listOf(FieldValue(7, 200.0)) + values.map { FieldValue(field(it.first), it.second) }
    )

  @Test
  fun `developer values are keyed by field name and leave native fields out`() {
    val mesg = record("total" to 3.0, "current" to 1.0)

    assertEquals(mapOf("total" to 3.0, "current" to 1.0), mesg.developerValues())
    assertEquals(3.0, mesg.developerValue("total")!!, 0.0)
    assertNull(mesg.developerValue("missing"))
  }

  @Test
  fun `awaitRecord returns the developer values of the first matching record`() {
    val fit = FitRecorder("fit") {}
    fit.send(record("total" to 1.0))
    fit.send(record("total" to 2.0))

    val values = fit.awaitRecord(1_000) { it["total"] == 2.0 }

    assertEquals(mapOf("total" to 2.0), values)
  }

  @Test
  fun `mark skips what was recorded before it`() {
    val fit = FitRecorder("fit") {}
    fit.send(record("total" to 1.0))
    val mark = fit.mark()

    assertThrows(IllegalStateException::class.java) {
      fit.awaitRecord(100, after = mark) { it["total"] == 1.0 }
    }
    fit.send(record("total" to 1.0))
    assertEquals(1.0, fit.awaitRecord(1_000, after = mark)["total"]!!, 0.0)
  }

  @Test
  fun `a long recording is summarized in the timeout message`() {
    val fit = FitRecorder("fit") {}
    repeat(20) { fit.send(record("total" to it.toDouble())) }

    val error =
      assertThrows(IllegalStateException::class.java) {
        fit.awaitRecord(100) { it["total"] == -1.0 }
      }

    assertTrue(error.message!!, error.message!!.contains("20 items, last 8"))
  }
}
