package fi.nikosavola.karooext.testing.integration

import android.content.Context
import android.os.Process
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig

const val SMOKE_EXTENSION_ID = "smoke"
const val SMOKE_LOCATION_TYPE = "smoke-location"
const val SMOKE_VIEW_TYPE = "smoke-view"
const val SMOKE_HTTP_TYPE = "smoke-http"
const val SMOKE_HTTP_URL = "https://example.test/smoke"

class SmokeExtension : KarooExtension(SMOKE_EXTENSION_ID, "1.0") {
  private val system by lazy { KarooSystemService(this) }

  override val types: List<DataTypeImpl> by lazy {
    listOf(
      SmokeLocationType(system, extension),
      SmokeViewType(extension),
      SmokeHttpType(system, extension),
    )
  }

  override fun onCreate() {
    super.onCreate()
    system.connect()
  }

  override fun onDestroy() {
    system.disconnect()
    super.onDestroy()
  }
}

private class SmokeLocationType(private val system: KarooSystemService, extension: String) :
  DataTypeImpl(extension, SMOKE_LOCATION_TYPE) {
  override fun startStream(emitter: Emitter<StreamState>) {
    emitter.onNext(StreamState.Searching)
    val consumerId =
      system.addConsumer(OnLocationChanged.Params) { location: OnLocationChanged ->
        emitter.onNext(
          StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to location.lat)))
        )
      }
    emitter.setCancellable { system.removeConsumer(consumerId) }
  }
}

private class SmokeViewType(extension: String) : DataTypeImpl(extension, SMOKE_VIEW_TYPE) {
  override fun startStream(emitter: Emitter<StreamState>) {
    emitter.onNext(
      StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0)))
    )
  }

  override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
    val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
    views.setTextViewText(
      android.R.id.text1,
      "pid=${Process.myPid()} view=${config.viewSize.first}x${config.viewSize.second} " +
        "text=${config.textSize}",
    )
    emitter.updateView(views)
    emitter.setCancellable {}
  }
}

private class SmokeHttpType(private val system: KarooSystemService, extension: String) :
  DataTypeImpl(extension, SMOKE_HTTP_TYPE) {
  override fun startStream(emitter: Emitter<StreamState>) {
    emitter.onNext(StreamState.Searching)
    val request =
      OnHttpResponse.MakeHttpRequest("GET", SMOKE_HTTP_URL, emptyMap(), "ping".toByteArray(), false)
    val consumerId =
      system.addConsumer(request) { response: OnHttpResponse ->
        val complete = response.state as? HttpResponseState.Complete
        if (complete != null) {
          val size = complete.body?.size?.toDouble() ?: 0.0
          emitter.onNext(
            StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to size)))
          )
        }
      }
    emitter.setCancellable { system.removeConsumer(consumerId) }
  }
}
