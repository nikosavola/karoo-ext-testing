package fi.nikosavola.karooext.testing

import android.os.Parcel
import android.widget.RemoteViews

// The in-process SDK passes frames through by reference, so isolate them like parceling would.
internal fun RemoteViews.snapshot(): RemoteViews {
  val parcel = Parcel.obtain()
  try {
    writeToParcel(parcel, 0)
    parcel.setDataPosition(0)
    return RemoteViews.CREATOR.createFromParcel(parcel)
  } finally {
    parcel.recycle()
  }
}
