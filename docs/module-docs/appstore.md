# Module appstore

The Karoo system app stand-in for emulator and instrumentation tests. A consumer app module whose `applicationId` is `io.hammerhead.appstore` depends on this artifact, so karoo-ext's bind by class name reaches the fake instead of a real Karoo.

- `AppStoreService` is the exported service karoo-ext addresses as `io.hammerhead.appstore.service.AppStoreService`. Because it is exported, any app installed on the device can bind it: run these tests only on a dedicated test AVD, never a daily-driver or a device with a real Karoo.
- `FakeKaroo.system` is the process-wide `FakeKarooSystem` returned by the service. It lives in the app's main process. In this repo's fixture the extension runs in its own `:extension` process and reaches the same fake only through the binder; a consuming library is not forced to use a separate process, but instrumentation must share the fake's process to control the singleton directly, so drive it from the appstore process as the fixture does.

This is a test stand-in and makes no claim about the real Karoo app's behavior. See the [testing guide](https://nikosavola.github.io/karoo-ext-testing/index.html) for the dedicated-AVD setup.
