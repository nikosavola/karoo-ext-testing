package fi.nikosavola.karooext.testing.robolectric

import android.os.Bundle
import fi.nikosavola.karooext.testing.CapturingHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TeardownTest {
  @get:Rule val karoo = FakeKarooRule()

  @Before
  fun resetFixtures() {
    LifecycleExtension.reset()
    ThrowingCancellableExtension.reset()
    ThrowingDestroyExtension.reset()
    ThrowingBindCleanupService.reset()
    ThrowingCreateCleanupService.reset()
  }

  @Test
  fun `a throwing cancellable still tears down everything and rejects reuse`() {
    val cancelling = karoo.host<ThrowingCancellableExtension>()
    cancelling.startScan()
    cancelling.startMap()
    karoo.host<LifecycleExtension>().startScan()
    karoo.host<ThrowingDestroyExtension>()

    val thrown = assertThrows(IllegalStateException::class.java) { karoo.close() }

    assertSame(ThrowingCancellableExtension.cancelBoom, thrown)
    assertTrue(thrown.suppressed.toList().any { it === ThrowingDestroyExtension.destroyBoom })
    assertTrue(ThrowingCancellableExtension.cancelled.contains("map"))
    assertTrue(LifecycleExtension.cancelled.contains("scan"))
    assertEquals(1, ThrowingCancellableExtension.destroyed)
    assertEquals(1, LifecycleExtension.destroyed)
    assertEquals(1, ThrowingDestroyExtension.destroyed)
    assertTrue(ThrowingCancellableExtension.cleanupRan >= 1)
    assertThrows(IllegalStateException::class.java) {
      karoo.system.addEventConsumer("late", Bundle(), CapturingHandler())
    }
    assertThrows(IllegalStateException::class.java) { karoo.host<LifecycleExtension>() }

    karoo.close()
    assertEquals(1, ThrowingCancellableExtension.cancelled.size)
    assertEquals(1, ThrowingDestroyExtension.destroyed)
  }

  @Test
  fun `repeated destroy failures do not stop the rest of cleanup`() {
    karoo.host<ThrowingDestroyExtension>()
    karoo.host<ThrowingDestroyExtension>()
    karoo.host<LifecycleExtension>()

    val thrown = assertThrows(IllegalStateException::class.java) { karoo.close() }

    assertSame(ThrowingDestroyExtension.destroyBoom, thrown)
    assertTrue(thrown.suppressed.toList().none { it === ThrowingDestroyExtension.destroyBoom })
    assertEquals(2, ThrowingDestroyExtension.destroyed)
    assertEquals(1, LifecycleExtension.destroyed)
  }

  @Test
  fun `a bind failure keeps its throwable and suppresses the throwing destroy`() {
    val thrown =
      assertThrows(IllegalStateException::class.java) { karoo.host<ThrowingBindCleanupService>() }

    assertEquals("bind boom", thrown.message)
    assertTrue(thrown.suppressed.toList().any { it === ThrowingBindCleanupService.destroyBoom })
    assertEquals(1, ThrowingBindCleanupService.destroyed)

    karoo.close()
    assertEquals(1, ThrowingBindCleanupService.destroyed)
  }

  @Test
  fun `a create failure keeps its throwable and suppresses the throwing destroy`() {
    val thrown =
      assertThrows(IllegalStateException::class.java) { karoo.host<ThrowingCreateCleanupService>() }

    assertEquals("create boom", thrown.message)
    assertTrue(thrown.suppressed.toList().any { it === ThrowingCreateCleanupService.destroyBoom })
    assertEquals(1, ThrowingCreateCleanupService.destroyed)

    karoo.close()
    assertEquals(1, ThrowingCreateCleanupService.destroyed)
  }

  @Test
  fun `the same cleanup throwable is not suppressed onto itself`() {
    val thrown =
      assertThrows(IllegalStateException::class.java) { karoo.host<SelfSuppressService>() }

    assertSame(SelfSuppressService.sameBoom, thrown)
    assertFalse(thrown.suppressed.toList().any { it === SelfSuppressService.sameBoom })
  }

  @Test
  fun `a shared throwable from cancel and destroy is not self-suppressed`() {
    karoo.host<SelfSuppressExtension>().startScan()

    val thrown = assertThrows(IllegalStateException::class.java) { karoo.close() }

    assertSame(SelfSuppressExtension.sameBoom, thrown)
    assertFalse(thrown.suppressed.toList().any { it === SelfSuppressExtension.sameBoom })
  }
}
