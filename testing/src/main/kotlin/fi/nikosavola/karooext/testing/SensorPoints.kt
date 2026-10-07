package fi.nikosavola.karooext.testing

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType

private val RADAR_RANGES =
  listOf(
    DataType.Field.RADAR_TARGET_1_RANGE,
    DataType.Field.RADAR_TARGET_2_RANGE,
    DataType.Field.RADAR_TARGET_3_RANGE,
    DataType.Field.RADAR_TARGET_4_RANGE,
    DataType.Field.RADAR_TARGET_5_RANGE,
    DataType.Field.RADAR_TARGET_6_RANGE,
    DataType.Field.RADAR_TARGET_7_RANGE,
    DataType.Field.RADAR_TARGET_8_RANGE,
  )

/**
 * Builders for the multi-field data points that the Karoo derives from sensors, so a test does not
 * have to remember which field keys go with which type. Each one returns a plain [DataPoint] for
 * [FakeKarooSystem.setDataPoint]. The values are not interpreted, so what a threat level or a range
 * means stays with the sensor and the extension.
 */
object SensorPoints {
  /**
   * A radar reading for `DataType.Type.RADAR`.
   *
   * @param threatLevel the sensor's threat level, passed through unchanged.
   * @param targetRanges the range of each tracked target in the order the radar reports them, at
   *   most eight.
   * @param error the radar's error code, set by name, for a reading that reports a fault.
   * @throws IllegalArgumentException if more than eight ranges are given.
   */
  fun radar(threatLevel: Int, vararg targetRanges: Double, error: Int? = null): DataPoint {
    require(targetRanges.size <= RADAR_RANGES.size) { "A radar reports at most 8 targets" }
    val values = buildMap {
      put(DataType.Field.RADAR_THREAT_LEVEL, threatLevel.toDouble())
      targetRanges.forEachIndexed { index, range -> put(RADAR_RANGES[index], range) }
      error?.let { put(DataType.Field.RADAR_ERROR, it.toDouble()) }
    }
    return DataPoint(DataType.Type.RADAR, values)
  }

  /**
   * Left-right balance for [type], `DataType.Type.PEDAL_POWER_BALANCE` unless a smoothed or average
   * balance type is wanted; [power] is included when given.
   */
  fun pedalPowerBalance(
    leftPercent: Double,
    power: Double? = null,
    type: String = DataType.Type.PEDAL_POWER_BALANCE,
  ): DataPoint =
    DataPoint(type, withPower(power, DataType.Field.PEDAL_POWER_BALANCE_LEFT to leftPercent))

  /** Per-leg torque effectiveness for `DataType.Type.TORQUE_EFFECTIVENESS`. */
  fun torqueEffectiveness(left: Double, right: Double, power: Double? = null): DataPoint =
    DataPoint(
      DataType.Type.TORQUE_EFFECTIVENESS,
      withPower(
        power,
        DataType.Field.TORQUE_EFFECTIVENESS_LEFT to left,
        DataType.Field.TORQUE_EFFECTIVENESS_RIGHT to right,
      ),
    )

  /** Per-leg pedal smoothness for `DataType.Type.PEDAL_SMOOTHNESS`. */
  fun pedalSmoothness(left: Double, right: Double, power: Double? = null): DataPoint =
    DataPoint(
      DataType.Type.PEDAL_SMOOTHNESS,
      withPower(
        power,
        DataType.Field.PEDAL_SMOOTHNESS_LEFT to left,
        DataType.Field.PEDAL_SMOOTHNESS_RIGHT to right,
      ),
    )

  /**
   * The current gears for `DataType.Type.SHIFTING_GEARS`. The tooth counts and the maximum gear
   * numbers are optional, as they are on the device.
   */
  @Suppress("LongParameterList")
  fun shiftingGears(
    frontGear: Int,
    rearGear: Int,
    frontMax: Int? = null,
    rearMax: Int? = null,
    frontTeeth: Int? = null,
    rearTeeth: Int? = null,
  ): DataPoint {
    val values = buildMap {
      put(DataType.Field.SHIFTING_FRONT_GEAR, frontGear.toDouble())
      put(DataType.Field.SHIFTING_REAR_GEAR, rearGear.toDouble())
      frontMax?.let { put(DataType.Field.SHIFTING_FRONT_GEAR_MAX, it.toDouble()) }
      rearMax?.let { put(DataType.Field.SHIFTING_REAR_GEAR_MAX, it.toDouble()) }
      frontTeeth?.let { put(DataType.Field.SHIFTING_FRONT_GEAR_TEETH, it.toDouble()) }
      rearTeeth?.let { put(DataType.Field.SHIFTING_REAR_GEAR_TEETH, it.toDouble()) }
    }
    return DataPoint(DataType.Type.SHIFTING_GEARS, values)
  }

  /**
   * One chainring for `DataType.Type.SHIFTING_FRONT_GEAR`, the separate type some apps read instead
   * of the combined `SHIFTING_GEARS`. [max] and [teeth] are optional.
   */
  fun shiftingFrontGear(gear: Int, max: Int? = null, teeth: Int? = null): DataPoint =
    DataPoint(
      DataType.Type.SHIFTING_FRONT_GEAR,
      buildMap {
        put(DataType.Field.SHIFTING_FRONT_GEAR, gear.toDouble())
        max?.let { put(DataType.Field.SHIFTING_FRONT_GEAR_MAX, it.toDouble()) }
        teeth?.let { put(DataType.Field.SHIFTING_FRONT_GEAR_TEETH, it.toDouble()) }
      },
    )

  /** One cog for `DataType.Type.SHIFTING_REAR_GEAR`, see [shiftingFrontGear]. */
  fun shiftingRearGear(gear: Int, max: Int? = null, teeth: Int? = null): DataPoint =
    DataPoint(
      DataType.Type.SHIFTING_REAR_GEAR,
      buildMap {
        put(DataType.Field.SHIFTING_REAR_GEAR, gear.toDouble())
        max?.let { put(DataType.Field.SHIFTING_REAR_GEAR_MAX, it.toDouble()) }
        teeth?.let { put(DataType.Field.SHIFTING_REAR_GEAR_TEETH, it.toDouble()) }
      },
    )

  private fun withPower(power: Double?, vararg fields: Pair<String, Double>): Map<String, Double> =
    buildMap {
      putAll(fields)
      power?.let { put(DataType.Field.POWER, it) }
    }
}
