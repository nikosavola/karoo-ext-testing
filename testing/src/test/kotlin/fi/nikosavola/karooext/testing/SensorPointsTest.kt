package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SensorPointsTest {
  private val system = FakeKarooSystem()

  @After fun close() = system.close()

  private fun add(id: String, params: KarooEventParams, handler: IHandler) =
    system.addEventConsumer(id, params.bundleWithSerializable(KAROO_SYSTEM_PACKAGE), handler)

  @Test
  fun `radar carries the threat level and the ranges in order`() {
    val point = SensorPoints.radar(2, 30.0, 55.5)

    assertEquals(DataType.Type.RADAR, point.dataTypeId)
    assertEquals(
      mapOf(
        DataType.Field.RADAR_THREAT_LEVEL to 2.0,
        DataType.Field.RADAR_TARGET_1_RANGE to 30.0,
        DataType.Field.RADAR_TARGET_2_RANGE to 55.5,
      ),
      point.values,
    )
  }

  @Test
  fun `radar can carry an error code`() {
    val point = SensorPoints.radar(0, error = 3)

    assertEquals(3.0, point.values.getValue(DataType.Field.RADAR_ERROR), 0.0)
  }

  @Test
  fun `radar rejects more than eight targets`() {
    assertThrows(IllegalArgumentException::class.java) {
      SensorPoints.radar(1, *DoubleArray(9) { it.toDouble() })
    }
  }

  @Test
  fun `pedal dynamics points carry their fields and optional power`() {
    assertEquals(
      mapOf(DataType.Field.PEDAL_POWER_BALANCE_LEFT to 48.0, DataType.Field.POWER to 200.0),
      SensorPoints.pedalPowerBalance(48.0, power = 200.0).values,
    )
    assertEquals(
      mapOf(
        DataType.Field.TORQUE_EFFECTIVENESS_LEFT to 70.0,
        DataType.Field.TORQUE_EFFECTIVENESS_RIGHT to 72.0,
      ),
      SensorPoints.torqueEffectiveness(70.0, 72.0).values,
    )
    assertEquals(
      mapOf(
        DataType.Field.PEDAL_SMOOTHNESS_LEFT to 20.0,
        DataType.Field.PEDAL_SMOOTHNESS_RIGHT to 21.0,
      ),
      SensorPoints.pedalSmoothness(20.0, 21.0).values,
    )
  }

  @Test
  fun `pedal power balance can be built for a smoothed type`() {
    val point =
      SensorPoints.pedalPowerBalance(
        51.0,
        type = DataType.Type.SMOOTHED_3S_AVERAGE_PEDAL_POWER_BALANCE,
      )

    assertEquals(DataType.Type.SMOOTHED_3S_AVERAGE_PEDAL_POWER_BALANCE, point.dataTypeId)
    assertEquals(mapOf(DataType.Field.PEDAL_POWER_BALANCE_LEFT to 51.0), point.values)
  }

  @Test
  fun `shifting gears include only the optional fields that were given`() {
    val point =
      SensorPoints.shiftingGears(frontGear = 2, rearGear = 7, rearMax = 12, rearTeeth = 17)

    assertEquals(DataType.Type.SHIFTING_GEARS, point.dataTypeId)
    assertEquals(
      mapOf(
        DataType.Field.SHIFTING_FRONT_GEAR to 2.0,
        DataType.Field.SHIFTING_REAR_GEAR to 7.0,
        DataType.Field.SHIFTING_REAR_GEAR_MAX to 12.0,
        DataType.Field.SHIFTING_REAR_GEAR_TEETH to 17.0,
      ),
      point.values,
    )
  }

  @Test
  fun `front and rear gears are separate types with their own fields`() {
    val front = SensorPoints.shiftingFrontGear(2, max = 2)
    val rear = SensorPoints.shiftingRearGear(7, max = 12, teeth = 17)

    assertEquals(DataType.Type.SHIFTING_FRONT_GEAR, front.dataTypeId)
    assertEquals(
      mapOf(
        DataType.Field.SHIFTING_FRONT_GEAR to 2.0,
        DataType.Field.SHIFTING_FRONT_GEAR_MAX to 2.0,
      ),
      front.values,
    )
    assertEquals(DataType.Type.SHIFTING_REAR_GEAR, rear.dataTypeId)
    assertEquals(
      mapOf(
        DataType.Field.SHIFTING_REAR_GEAR to 7.0,
        DataType.Field.SHIFTING_REAR_GEAR_MAX to 12.0,
        DataType.Field.SHIFTING_REAR_GEAR_TEETH to 17.0,
      ),
      rear.values,
    )
  }

  @Test
  fun `a built point streams to a consumer`() {
    val handler = CapturingHandler()
    add("radar", OnStreamState.StartStreaming(DataType.Type.RADAR), handler)

    system.setDataPoint(SensorPoints.radar(1, 12.0))

    val state = handler.events<OnStreamState>().single().state as StreamState.Streaming
    assertEquals(12.0, state.dataPoint.values.getValue(DataType.Field.RADAR_TARGET_1_RANGE), 0.0)
  }

  @Test
  fun `savedDevice fills in the parts a test rarely cares about`() {
    val device = FakeKarooSystem.savedDevice("ant-1", "Pedals", DataType.Type.PEDAL_POWER_BALANCE)

    assertEquals("ant-1", device.id)
    assertEquals("Pedals", device.name)
    assertEquals(listOf(DataType.Type.PEDAL_POWER_BALANCE), device.supportedDataTypes)
    assertTrue(device.enabled)
    assertEquals("BLE", device.connectionType)
    assertEquals(
      "ANT_PLUS",
      FakeKarooSystem.savedDevice("a", "b", connectionType = "ANT_PLUS").connectionType,
    )
  }

  @Test
  fun `typed setters replay saved devices, bikes and pois to late consumers`() {
    val device = FakeKarooSystem.savedDevice("ble-1", "Pedals", DataType.Type.PEDAL_POWER_BALANCE)
    system.setSavedDevices(listOf(device))
    system.setBikes(listOf(Bikes.Bike("b1", "Road", 1_000.0)))
    system.setGlobalPois(listOf(Symbol.POI("p1", 60.0, 24.0)))

    val devices = CapturingHandler()
    val bikes = CapturingHandler()
    val pois = CapturingHandler()
    add("d", SavedDevices.Params, devices)
    add("b", Bikes.Params, bikes)
    add("p", OnGlobalPOIs.Params, pois)

    assertEquals(listOf(device), devices.events<SavedDevices>().single().devices)
    assertEquals("Road", bikes.events<Bikes>().single().bikes.single().name)
    assertEquals("p1", pois.events<OnGlobalPOIs>().single().pois.single().id)
  }
}
