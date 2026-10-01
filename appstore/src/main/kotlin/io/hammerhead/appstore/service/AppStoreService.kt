package io.hammerhead.appstore.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import fi.nikosavola.karooext.testing.appstore.FakeKaroo

/** Bound by karoo-ext's KarooSystemService, which addresses this exact class name. */
class AppStoreService : Service() {
  override fun onBind(intent: Intent): IBinder = FakeKaroo.system
}
