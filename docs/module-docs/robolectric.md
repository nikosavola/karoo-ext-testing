# Module robolectric

Robolectric glue for the [testing](https://nikosavola.github.io/karoo-ext-testing/testing/index.html) fakes: bind the extension's `KarooSystemService` to a `FakeKarooSystem` and pump the main looper while a test waits. Use these on the JVM with the Robolectric runner.

- `FakeKarooBinding.install(application, system)` points the next bind of the Karoo system service at a fake, as if the Karoo system app were installed.
- `FakeKarooRule` is a JUnit 4 rule that installs the binding, starts the extension's service with `host<T>()`, and tears everything down after the test: hosts close, services are destroyed, the fake system closes.
- `RobolectricPump` idles the Robolectric main looper so main-thread work runs while a test thread blocks, and `advanceBy` moves that looper's clock.
- `FakeBle` and `FakeBlePeripheral` (package `ble`) fake a BLE device on top of Robolectric's Bluetooth shadows: advertisements, GATT services, notifications and a log of the extension's writes.
- `FakeAndroidKeyStore` registers an in-memory `AndroidKeyStore` provider so `EncryptedSharedPreferences` and `MasterKey` work under Robolectric.

The recorder waits use real elapsed time, not Robolectric's clock; see the [testing guide](https://nikosavola.github.io/karoo-ext-testing/index.html) for how the different time sources interact.
