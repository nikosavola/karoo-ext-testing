# karoo-ext-testing

[![CI](https://github.com/nikosavola/karoo-ext-testing/actions/workflows/ci.yml/badge.svg)](https://github.com/nikosavola/karoo-ext-testing/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Test doubles for the Karoo system side of [karoo-ext](https://github.com/hammerheadnav/karoo-ext). They let a Karoo extension be tested on the JVM with Robolectric, and on an emulator without a Karoo, by standing in for the `KarooSystemService` the extension binds to.

Test and debug tooling only: do not ship these artifacts in a release APK. The API is pre-1.0 and may still change.

## What you can test

Your real extension service runs unchanged against the fakes, so a test can:

- drive data streams, location, ride state, navigation, routes, the user profile and ride profiles, and see what the extension streams back
- script HTTP responses, including retries, failures and request ordering
- record views, map effects, FIT writes, alerts, device scans and device connections
- fake a Bluetooth LE sensor or light that the extension scans for and connects to, down to the GATT writes it sends
- assert that Bluetooth and ANT resource claims are released, that consumers are cleaned up and that a restart mid-ride recovers
- render `RemoteViews` under field bounds, control time, and run code that needs `AndroidKeyStore`

ANT+ cannot be faked: the Dynastream service has no Robolectric shadow, so test the extension's own logic around it and its resource claims. Nothing here proves behavior on real Karoo hardware.

## Install

Three artifacts are published together:

- `karoo-ext-testing`: `FakeKarooSystem` (an in-process `IKarooSystem`), `FakeKarooHost` and its recorders for streams, views, maps, scans, device connections and FIT, HTTP responders (`HttpResponses`, `SequenceResponder`, `RoutingResponder`, `LiveResponder`), `SensorPoints` builders, RemoteViews inspection helpers and `encodePolyline`/`decodePolyline`.
- `karoo-ext-testing-robolectric`: `FakeKarooBinding`, `FakeKarooRule`, `RobolectricPump`, the fake BLE peripheral (`FakeBle`, `FakeBlePeripheral`) and `FakeAndroidKeyStore`.
- `karoo-ext-testing-appstore`: an Android library that declares the `io.hammerhead.appstore.service.AppStoreService` stand-in, for emulator tests.

Add them to the test source set, with the JitPack repository restricted to this group:

```kotlin
// settings.gradle.kts, dependencyResolutionManagement.repositories
exclusiveContent {
  forRepository { maven("https://jitpack.io") }
  filter { includeGroup("com.github.nikosavola.karoo-ext-testing") }
}

// build.gradle.kts
testImplementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing:<tag>")
testImplementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing-robolectric:<tag>")
```

Use the latest [release tag](https://github.com/nikosavola/karoo-ext-testing/tags) for `<tag>`. The same build is also published to GitHub Packages, whose Maven feed needs a token with `read:packages` even for public packages:

```kotlin
exclusiveContent {
  forRepository {
    maven("https://maven.pkg.github.com/nikosavola/karoo-ext-testing") {
      credentials {
        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
      }
    }
  }
  filter { includeGroup("com.github.nikosavola.karoo-ext-testing") }
}
```

Every artifact depends on karoo-ext with `compileOnly`, so it is never brought in transitively and some consumers use different coordinates. Add yours, matching what your extension already uses:

```kotlin
testImplementation("com.github.hammerheadnav:karoo-ext:1.1.9")
```

| | Supported |
| --- | --- |
| Consumer toolchain | Kotlin 2.0 or newer, JVM target 1.8 or newer |
| Artifacts | minSdk 23, minCompileSdk 33 |
| karoo-ext | built against 1.1.9; FIT needs 1.1.4, `setRoute` 1.1.6, `bonusAction` 1.1.7 |
| Robolectric | any version for the core fakes; the BLE fake needs API 26 or newer and Robolectric 4.17 with compileSdk 36, or 4.16.1 |
| Test JDK | 17 or newer |

The [testing guide](docs/testing-guide.md) has the details, including Robolectric 4.17 and compileSdk.

## Quick start with Robolectric

Bind the extension's `KarooSystemService` to the fake, start your real extension service the way the Karoo ride app does, and drive it:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MyFieldTest {
  @get:Rule val karoo = FakeKarooRule()

  @Test
  fun `location reaches the stream`() {
    karoo.system.responder = HttpResponses.success("""{"value": 42}""".toByteArray())
    val host = karoo.host<MyExtension>()
    val stream = host.startStream("my-type")
    karoo.system.setLocation(60.17, 24.94)
    val state = stream.await(10_000) { it is StreamState.Streaming } as StreamState.Streaming
    assertEquals(42.0, state.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)
  }
}
```

`FakeKarooRule` installs the binding before each test and tears everything down after: it closes every host, destroys their services, then closes the fake system. Without the rule, call `FakeKarooBinding.install(application, system)` yourself and pass `RobolectricPump.invoke()` as a recorder's `pump` so main-thread work runs while a test blocks. An app that connects in `Application.onCreate` (a Koin or Hilt singleton, say) binds before any rule runs; see `FakeKarooBinding.installEarly` in the guide.

A BLE device is a few lines on top of that:

```kotlin
val ble = FakeBle(karoo.app)
val light = ble.peripheral("AA:BB:CC:DD:EE:01", name = "B54 light") {
  service(NUS_SERVICE, advertised = true) {
    characteristic(NUS_RX, write = true)
    characteristic(NUS_TX, notify = true)
  }
}
ble.grantPermissions()
val events = karoo.host<MyExtension>().connectDevice("my-light-AA:BB:CC:DD:EE:01")

light.advertise()                 // waits for the extension to scan
light.connect()                   // waits for connectGatt, then accepts it
light.awaitSubscribed(NUS_TX)
light.notify(NUS_TX, "\$L12800".toByteArray())
val keepalive = light.awaitWrite(NUS_RX)
```

## How the fake system behaves

- It replays the current value to a consumer that registers late: location, navigation, ride state, user profile, active page, active ride profile and stream state. The SDK documents this replay only for ride state and user profile, so the rest is fake policy chosen for deterministic tests.
- State is set with `setLocation`, `setNavigation`, `setRoute`, `setRideState`, `setUserProfile`, `setActiveRideProfile`, `setMapZoom`, `showPage`, `setSavedDevices` and `setSticky(params, event)`, and one-shot events go out with `publish(params, event)`. Built-in data types are driven with `setStreamState(id, state)` or `setDataPoint(id, value)`.
- Bridged HTTP requests go through `responder` and are recorded in `httpRequests`. Effects the extension dispatches land in `effects`.
- `consumerCount`, `consumerParams`, `pendingHttpCount`, `bluetoothClaims` and `antClaims` let a test assert cleanup. `reset()` returns everything to defaults and stays reusable; `close()` is terminal.

## Emulator tests

Create a tiny app module with `applicationId io.hammerhead.appstore` that depends on the appstore artifact, and run your instrumentation tests there. karoo-ext's `KarooSystemService` binds `io.hammerhead.appstore.service.AppStoreService` by name, so the fake answers in place of the real system app:

```kotlin
// app/build.gradle.kts
android {
  namespace = "com.example.extensionappstore"
  defaultConfig { applicationId = "io.hammerhead.appstore"; minSdk = 26 }
}

dependencies {
  // Debug-only: the fixture disables its release variant and sets android:testOnly in its manifest.
  debugImplementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing-appstore:<tag>")
  // compileOnly in the library, so the fake app brings it.
  debugImplementation("com.github.hammerheadnav:karoo-ext:1.1.9")
}
```

Scope the stand-in to the debug variant and keep the release variant out of the fixture; a single `implementation` line is not a guard on its own. Your extension app's debug manifest also needs to see that package, or its bind is filtered out:

```xml
<queries>
  <package android:name="io.hammerhead.appstore" />
</queries>
```

Connected tests install on every attached device. Never run them with a real Karoo or phone attached: the fake claims the Karoo system app's package name, and the tests would install and run there too. Pin the target emulator with both `ANDROID_SERIAL` and `-Pandroid.injected.device.serial=<serial>`: the AGP device filter is what selects the device for the test task, and the environment variable alone is not enough when several devices are attached.

`FakeKaroo.system` is process-wide: call `FakeKaroo.system.reset()` before and after each test and never `close()` it, or later tests in the same process bind to a dead fake. The [testing guide](docs/testing-guide.md) has a template.

## Documentation

- [Testing guide](docs/testing-guide.md): recipes for binding a real extension service, driving sensor streams, HTTP sequences and failure paths, BLE devices, FIT, device and bonus outputs, cleanup assertions and time.
- [API reference](https://nikosavola.github.io/karoo-ext-testing/): Dokka HTML for all three modules.
- [Maintaining](docs/maintaining.md): local workflow, one-time repository setup and releasing.

## Contributing

Issues and pull requests are welcome; start with [CONTRIBUTING](.github/CONTRIBUTING.md). Please follow the [code of conduct](.github/CODE_OF_CONDUCT.md), and report vulnerabilities as described in the [security policy](.github/SECURITY.md). The project can be supported through [Liberapay](https://liberapay.com/nikosavola).

## License

[Apache License 2.0](LICENSE).
