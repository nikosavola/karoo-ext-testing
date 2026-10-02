package fi.nikosavola.karooext.testing

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.WriteToRecordMesg
import java.util.concurrent.CopyOnWriteArrayList

const val CONTRACT_EXTENSION_ID = "contract"
const val CONTRACT_STREAM_TYPE = "contract-stream"
const val CONTRACT_VIEW_TYPE = "contract-view"
const val CONTRACT_ERROR_STREAM_TYPE = "contract-error"
const val CONTRACT_COMPLETE_STREAM_TYPE = "contract-complete"

/** Full wire id of the contract stream, as the SDK builds it from extension and type id. */
const val CONTRACT_STREAM_DATA_TYPE = "TYPE_EXT::contract::contract-stream"

/**
 * A real [KarooExtension] fixture for pinning SDK-side session, wire-id and silent-failure
 * semantics. Behaviors are recorded in the companion so tests can assert them.
 */
class ContractExtension : KarooExtension(CONTRACT_EXTENSION_ID, "1.0") {
  override val types: List<DataTypeImpl> =
    listOf(
      ContractStreamType(extension, CONTRACT_STREAM_TYPE),
      ContractViewType(extension, CONTRACT_VIEW_TYPE),
      ContractErrorType(extension, CONTRACT_ERROR_STREAM_TYPE),
      ContractCompleteType(extension, CONTRACT_COMPLETE_STREAM_TYPE),
    )

  override fun startScan(emitter: Emitter<Device>) {
    emitter.onNext(
      Device(extension, "contract-sensor", listOf(DataType.Type.POWER), "Contract sensor")
    )
    emitter.setCancellable { recordCancel("scan") }
  }

  override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
    emitter.setCancellable { recordCancel("device") }
  }

  override fun startMap(emitter: Emitter<MapEffect>) {
    emitter.onNext(ShowSymbols(listOf(Symbol.Icon("contract", 60.0, 24.0, 0, 0f))))
    emitter.setCancellable { recordCancel("map") }
  }

  override fun startFit(emitter: Emitter<FitEffect>) {
    emitter.onNext(WriteToRecordMesg(FieldValue(7, 123.0)))
    emitter.setCancellable { recordCancel("fit") }
  }

  companion object {
    /** Cancellable labels that ran, e.g. after a stop or host close. */
    val cancels = CopyOnWriteArrayList<String>()

    /** Emitters handed to [ContractStreamType], so a test can push a later update. */
    val streamEmitters = CopyOnWriteArrayList<Emitter<StreamState>>()

    /** The config the view type last received, to check host serialization preserved it. */
    @Volatile
    var lastViewConfig: ViewConfig? = null
      private set

    fun recordCancel(kind: String) {
      cancels += kind
    }

    fun captureViewConfig(config: ViewConfig) {
      lastViewConfig = config
    }

    fun reset() {
      cancels.clear()
      streamEmitters.clear()
      lastViewConfig = null
    }
  }
}

private class ContractStreamType(extension: String, typeId: String) :
  DataTypeImpl(extension, typeId) {
  override fun startStream(emitter: Emitter<StreamState>) {
    val index = ContractExtension.streamEmitters.size
    ContractExtension.streamEmitters += emitter
    emitter.setCancellable { ContractExtension.recordCancel("stream") }
    emitter.onNext(
      StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to index.toDouble())))
    )
  }
}

private class ContractViewType(extension: String, typeId: String) :
  DataTypeImpl(extension, typeId) {
  override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
    ContractExtension.captureViewConfig(config)
    emitter.onNext(UpdateGraphicConfig(showHeader = false, formatDataTypeId = DataType.Type.POWER))
    emitter.onNext(ShowCustomStreamState("contract", null))
    val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
    views.setTextViewText(android.R.id.text1, "contract ${config.textSize}")
    emitter.updateView(views)
    emitter.setCancellable { ContractExtension.recordCancel("view") }
  }
}

private class ContractErrorType(extension: String, typeId: String) :
  DataTypeImpl(extension, typeId) {
  override fun startStream(emitter: Emitter<StreamState>) {
    emitter.onError(IllegalStateException("contract failed"))
  }
}

private class ContractCompleteType(extension: String, typeId: String) :
  DataTypeImpl(extension, typeId) {
  override fun startStream(emitter: Emitter<StreamState>) {
    emitter.onComplete()
  }
}
