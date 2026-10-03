package io.hammerhead.appstore.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import fi.nikosavola.karooext.testing.appstore.FakeKaroo

/**
 * Bound by karoo-ext's KarooSystemService, which addresses this exact package and class name
 * (`fi.nikosavola.karooext.testing.KAROO_SYSTEM_SERVICE`). It exists so a consumer emulator build
 * with application id `io.hammerhead.appstore` binds the in-process [FakeKaroo] instead of the real
 * app. It reports the fake's values and makes no claim about real hardware.
 */
class AppStoreService : Service() {
  /** Returns the process-wide [FakeKaroo.system] binder. */
  override fun onBind(intent: Intent): IBinder = FakeKaroo.system
}
