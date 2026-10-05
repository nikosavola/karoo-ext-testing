package fi.nikosavola.karooext.testing.robolectric

import fi.nikosavola.karooext.testing.CapturingHandler
import fi.nikosavola.karooext.testing.KAROO_SYSTEM_PACKAGE
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FailureStateTest {
  @Test
  fun `a failing test carries the fake's state`() {
    val rule = FakeKarooRule()
    val boom = AssertionError("timed out")
    val statement =
      rule.apply(
        object : Statement() {
          override fun evaluate() {
            rule.system.setStreamState("my-type", StreamState.Searching)
            rule.system.addEventConsumer(
              "c",
              (RideState.Params as KarooEventParams).bundleWithSerializable(KAROO_SYSTEM_PACKAGE),
              CapturingHandler(),
            )
            throw boom
          }
        },
        Description.EMPTY,
      )
    val thrown = runCatching { statement.evaluate() }.exceptionOrNull()
    assertSame(boom, thrown)
    val state = boom.suppressed.single()
    assertTrue(state is FakeKarooRule.FakeKarooState)
    assertTrue(state.message!!, state.message!!.contains("my-type"))
    // Taken before teardown, so the consumer is still listed.
    assertTrue(state.message!!, state.message!!.contains("RideState.Params"))
  }

  @Test
  fun `a passing test is left alone`() {
    val rule = FakeKarooRule()
    var ran = false
    rule
      .apply(
        object : Statement() {
          override fun evaluate() {
            ran = true
          }
        },
        Description.EMPTY,
      )
      .evaluate()
    assertEquals(true, ran)
  }

  @Test
  fun `rule awaitValue pumps the main looper`() {
    val rule = FakeKarooRule()
    var ran = false
    android.os.Handler(android.os.Looper.getMainLooper()).post { ran = true }
    assertEquals(true, rule.awaitValue(timeoutMs = 1_000) { ran.takeIf { it } })
    rule.close()
  }
}
