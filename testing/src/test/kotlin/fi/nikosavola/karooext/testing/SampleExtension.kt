package fi.nikosavola.karooext.testing

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.ManufacturerInfo
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnManufacturerInfo
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.WriteToRecordMesg
import java.util.concurrent.CopyOnWriteArrayList

const val SAMPLE_EXTENSION_ID = "sample"
const val SAMPLE_STREAM_TYPE = "sample-value"
const val SAMPLE_VIEW_TYPE = "sample-view"
const val SAMPLE_POWER_TYPE = "sample-power"
const val SAMPLE_DEVICE_UID = "sample-sensor"
const val SAMPLE_BONUS_ACTION = "sample-action"

private const val SCAN_CANCEL = "scan"
private const val CONNECT_CANCEL = "connect"
private const val FIT_CANCEL = "fit"

/**
 * A minimal extension exercising the stream, view, map, scan, device, FIT and bonus-action paths
 * against a fake Karoo system: a numeric stream that asks for a value over bridged HTTP, a
 * graphical view, a map effect, a sensor that reports a device, a device event stream, a FIT
 * effect, and a data type that reads the built-in power stream.
 */
class SampleExtension : KarooExtension(SAMPLE_EXTENSION_ID, "1.0") {
  private val system by lazy { KarooSystemService(this).apply { connect() } }

  override val types: List<DataTypeImpl>
    get() =
      listOf(
        SampleStreamType(system, extension),
        SampleViewType(system, extension),
        SamplePowerType(system, extension),
      )

  override fun startMap(emitter: Emitter<MapEffect>) {
    emitter.onNext(ShowSymbols(listOf(Symbol.Icon("start", 60.0, 24.0, 0, 0f))))
    emitter.setCancellable {}
  }

  override fun startScan(emitter: Emitter<Device>) {
    emitter.onNext(
      Device(
        extension = extension,
        uid = SAMPLE_DEVICE_UID,
        dataTypes = listOf(DataType.Type.POWER),
        displayName = "Sample power meter",
      )
    )
    emitter.setCancellable { cancelled += SCAN_CANCEL }
  }

  override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
    emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
    emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
    emitter.onNext(
      OnManufacturerInfo(ManufacturerInfo(manufacturer = "Sample", serialNumber = uid))
    )
    emitter.onNext(
      OnDataPoint(
        DataPoint(DataType.Type.POWER, mapOf(DataType.Field.POWER to 200.0), sourceId = uid)
      )
    )
    emitter.setCancellable { cancelled += CONNECT_CANCEL }
  }

  override fun startFit(emitter: Emitter<FitEffect>) {
    // FIT RecordMesg power is field 7, in watts.
    emitter.onNext(WriteToRecordMesg(FieldValue(7, 200.0)))
    emitter.setCancellable { cancelled += FIT_CANCEL }
  }

  override fun onBonusAction(actionId: String) {
    bonusActions += actionId
  }

  companion object {
    /** Labels of the sessions whose cancellable ran, e.g. after a stop or [FakeKarooHost.close]. */
    val cancelled = CopyOnWriteArrayList<String>()

    /** Bonus action ids the ride app sent, in arrival order. */
    val bonusActions = CopyOnWriteArrayList<String>()

    fun reset() {
      cancelled.clear()
      bonusActions.clear()
    }
  }
}

private class SampleStreamType(private val system: KarooSystemService, extension: String) :
  DataTypeImpl(extension, SAMPLE_STREAM_TYPE) {
  override fun startStream(emitter: Emitter<StreamState>) {
    emitter.onNext(StreamState.Searching)
    val locationId =
      system.addConsumer(OnLocationChanged.Params) { location: OnLocationChanged ->
        val request =
          OnHttpResponse.MakeHttpRequest(
            "GET",
            "https://example.test/value?lat=${location.lat}&lng=${location.lng}",
            mapOf("Authorization" to "Bearer sample-token"),
            null,
            false,
          )
        system.addConsumer(request) { response: OnHttpResponse ->
          val complete = response.state as? HttpResponseState.Complete
          if (complete != null) {
            val value = complete.body?.decodeToString()?.toDoubleOrNull()
            if (value != null) {
              emitter.onNext(
                StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))
              )
            } else {
              emitter.onError(IllegalStateException(complete.error ?: "no value"))
            }
          }
        }
      }
    emitter.setCancellable { system.removeConsumer(locationId) }
  }
}

private class SampleViewType(private val system: KarooSystemService, extension: String) :
  DataTypeImpl(extension, SAMPLE_VIEW_TYPE) {
  override fun startStream(emitter: Emitter<StreamState>) {
    // Graphical fields still need a stream, even though the value is meaningless here.
    emitter.onNext(
      StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0)))
    )
  }

  override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
    val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
    views.setTextViewText(android.R.id.text1, "sample ${config.textSize}")
    emitter.updateView(views)
    emitter.setCancellable {}
  }
}

/** Re-emits the built-in power stream as this extension's own single-value data type. */
private class SamplePowerType(private val system: KarooSystemService, extension: String) :
  DataTypeImpl(extension, SAMPLE_POWER_TYPE) {
  override fun startStream(emitter: Emitter<StreamState>) {
    emitter.onNext(StreamState.Searching)
    val consumerId =
      system.addConsumer<OnStreamState>(OnStreamState.StartStreaming(DataType.Type.POWER)) { event
        ->
        when (val state = event.state) {
          is StreamState.Streaming -> {
            val power = state.dataPoint.values[DataType.Field.POWER]
            if (power != null) {
              emitter.onNext(
                StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to power)))
              )
            } else {
              emitter.onNext(StreamState.Searching)
            }
          }
          // Searching and NotAvailable are recoverable: stay in Searching so a later data point
          // still starts a stream.
          StreamState.Searching,
          StreamState.NotAvailable,
          StreamState.Idle -> {
            emitter.onNext(StreamState.Searching)
          }
        }
      }
    emitter.setCancellable { system.removeConsumer(consumerId) }
  }
}
