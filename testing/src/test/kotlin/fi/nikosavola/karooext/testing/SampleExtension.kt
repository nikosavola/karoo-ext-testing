package fi.nikosavola.karooext.testing

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.ViewConfig

const val SAMPLE_EXTENSION_ID = "sample"
const val SAMPLE_STREAM_TYPE = "sample-value"
const val SAMPLE_VIEW_TYPE = "sample-view"

/**
 * A minimal extension exercising the stream, view and map paths against a fake Karoo system: a
 * numeric stream that asks for a value over bridged HTTP, a graphical view, and a map effect.
 */
class SampleExtension : KarooExtension(SAMPLE_EXTENSION_ID, "1.0") {
  private val system by lazy { KarooSystemService(this).apply { connect() } }

  override val types: List<DataTypeImpl>
    get() = listOf(SampleStreamType(system, extension), SampleViewType(system, extension))

  override fun startMap(emitter: Emitter<MapEffect>) {
    emitter.onNext(ShowSymbols(listOf(Symbol.Icon("start", 60.0, 24.0, 0, 0f))))
    emitter.setCancellable {}
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
