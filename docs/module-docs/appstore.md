# Module appstore

The Karoo system app stand-in for emulator and instrumentation tests. A consumer app module whose `applicationId` is `io.hammerhead.appstore` depends on this artifact, so karoo-ext's bind by class name reaches the fake instead of a real Karoo.

- `AppStoreService` is the exported service karoo-ext addresses as `io.hammerhead.appstore.service.AppStoreService`.
- `FakeKaroo.system` is the process-wide `FakeKarooSystem` that service returns, so the bound service and the test, running in the same process, drive the same instance.

Never run these tests with a real Karoo or phone attached: the fake claims the Karoo system app's package name. See the [testing guide](https://nikosavola.github.io/karoo-ext-testing/index.html) for the module setup.
