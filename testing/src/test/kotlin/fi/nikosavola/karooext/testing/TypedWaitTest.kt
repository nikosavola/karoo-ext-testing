package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TypedWaitTest {
  private open class Shape

  private class Circle(val radius: Int) : Shape()

  private class Square : Shape()

  private class ShapeRecorder(pump: () -> Unit = {}) : Recorder<Shape>("shapes", pump) {
    fun emit(item: Shape) = record(item)
  }

  private fun line(id: String) = ShowPolyline(id, encodePolyline(listOf(1.0 to 2.0)), 0, 4)

  private fun MapRecorder.send(effect: MapEffect) =
    handler.onNext(effect.bundleWithSerializable(KAROO_SYSTEM_PACKAGE))

  @Test
  fun `awaitOf returns the first item of the subtype that matches`() {
    val recorder = ShapeRecorder()
    recorder.emit(Square())
    recorder.emit(Circle(1))
    recorder.emit(Circle(2))
    assertEquals(2, recorder.awaitOf<Circle>(1_000) { it.radius == 2 }.radius)
    assertEquals(1, recorder.awaitOf<Circle>(1_000).radius)
  }

  @Test
  fun `awaitQuiet returns once a burst stops`() {
    val left = AtomicInteger(5)
    lateinit var recorder: ShapeRecorder
    recorder = ShapeRecorder { if (left.getAndDecrement() > 0) recorder.emit(Square()) }
    assertEquals(5, recorder.awaitQuiet(quietMs = 100, timeoutMs = 5_000).size)
  }

  @Test
  fun `awaitQuiet times out while items keep coming`() {
    lateinit var recorder: ShapeRecorder
    recorder = ShapeRecorder { recorder.emit(Square()) }
    assertThrows(IllegalStateException::class.java) {
      recorder.awaitQuiet(quietMs = 500, timeoutMs = 200)
    }
  }

  @Test
  fun `visible map state replays shows and hides in order`() {
    val map = MapRecorder("map") {}
    val poi = Symbol.POI("p1", 1.0, 2.0)
    map.send(line("a"))
    map.send(line("b"))
    map.send(HidePolyline("a"))
    map.send(ShowSymbols(listOf(poi, Symbol.POI("p2", 3.0, 4.0))))
    map.send(HideSymbols(listOf("p2")))
    map.send(line("a"))

    assertEquals(setOf("b", "a"), map.visiblePolylines().keys)
    assertEquals(mapOf("p1" to poi), map.visibleSymbols())
  }
}
