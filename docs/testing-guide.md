# Module karoo-ext-testing

Test doubles for the Karoo system side of [karoo-ext](https://github.com/hammerheadnav/karoo-ext), published as three artifacts: [testing](testing/index.html) holds the fakes, [robolectric](robolectric/index.html) the binder glue for JVM tests, and [appstore](appstore/index.html) the system app stand-in for emulator tests. Test and debug tooling only: do not ship these artifacts in a release APK.

## Testing guide

Recipes for testing a Karoo extension against the fakes in this repo. The fakes stand in for the Karoo system side of the karoo-ext binder: the `KarooSystemService` an extension binds to. They are test and debug tooling only. Do not ship any of these artifacts in a release APK.

The examples below describe the current source API. The library is pre-1.0 and the API is still changing, and some of it may not be in a published tag yet, so if a symbol is missing in the tag you pinned, check that tag's published sources.

## Test strategy

Pick the cheapest layer that can catch the bug. The layers differ in scope and environment, not in what they claim:

| Layer | What it covers | How to run |
| --- | --- | --- |
| Pure JUnit logic | Data types, helpers and policy with no Android runtime | `just test` |
| Robolectric | SDK binder contract, `Bundle` and `RemoteViews` marshalling, fake lifecycle | `just test`, `just self-test` |
| Actual Android SDK, separate process | Binder, service binding and `RemoteViews` over a real emulator | `just integration-test emulator-5554` |
| Real Karoo hardware | Device and OEM behavior | Manual, not automated here |

- The fakes' own suites own the fake SDK contract and lifecycle. Your project owns its retry logic, layout and resource configuration.
- The synchronous in-process binder the fakes use is a Robolectric-tier convenience. On an emulator the SDK binder is the real one and its `oneway` callbacks are asynchronous across processes, so use bounded, eventual waits for cancellation instead of immediate cleanup assertions. Not every binder call is `oneway`: a value-returning method such as `libVersion()` blocks for its result.
- None of the automated layers prove hardware or vendor behavior; the same code path on a real Karoo can differ in timing and OEM implementation.

## Dependencies

Test doubles are for the test source set. Add the artifact and your own karoo-ext:

```kotlin
dependencies {
  testImplementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing:<tag>")
  testImplementation("com.github.hammerheadnav:karoo-ext:1.1.9") // your version
}
```

Most extensions already depend on karoo-ext from Hammerhead's GitHub Packages feed as `io.hammerhead:karoo-ext`; keep that line as it is and add only the karoo-ext-testing artifacts.

`karoo-ext` is `compileOnly` inside the library, so it is never pulled in transitively; bring your own, since some projects use different coordinates (`io.hammerhead:karoo-ext`). kotlinx-serialization-json is `compileOnly` too and comes at runtime with karoo-ext.

For the Robolectric binding rule, add the robolectric artifact and the test libraries it needs. Like karoo-ext, JUnit, Robolectric and androidx.test:core are `compileOnly` in the library modules, so they are never brought in transitively and nothing pulls them in for you. Add them explicitly (versions below are what this repo builds against):

```kotlin
dependencies {
  testImplementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing-robolectric:<tag>")
  // Required by the Robolectric runner; none of these come in transitively.
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.17")
  testImplementation("androidx.test:core:1.7.0")
}

android {
  testOptions { unitTests { isIncludeAndroidResources = true } }
}
```

The same coordinates are `libs.junit`, `libs.robolectric` and `libs.androidx.test.core` in this repo's version catalog (`gradle/libs.versions.toml`).

On JUnit 5, keep your Jupiter tests and add the vintage engine so the JUnit 4 Robolectric tests run on the same platform:

```kotlin
dependencies {
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine:<your junit 5 version>")
}
```

## Robolectric: bind your real extension service

Robolectric runs the real binder in-process, so the extension's actual `KarooSystemService`, `DataTypeImpl` and `Emitter` code execute; only the Karoo system end is replaced. Use JUnit 4 with the Robolectric runner and an explicit SDK. `FakeKarooRule` installs the binding before each test and, after the test, stops every session it started, destroys the services and closes the fake system.

`startStream` and `startView` take the short `DataTypeImpl.typeId` the type was declared with, such as `"my-type"`, not the full `DataType.dataTypeId(extensionId, typeId)` wire id a `DataPoint` carries. The SDK silently ignores an unknown type id, so a wrong id registers nothing and surfaces as a recorder timeout, not an error.

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

`karoo.host<MyExtension>()` starts the service the way the ride app does and returns a `FakeKarooHost`. Start the sessions your test needs; the recording handle doubles as the stop handle (`host.stopStream(recorder)`) and `host.close()` stops everything. Here `MyExtension` fetches JSON on each location update and emits the parsed value under `DataType.Field.SINGLE`; that wiring is the extension's own, not universal fake or SDK behavior.

If your project's own logic tests already run on the JUnit Platform (JUnit 5), you do not have to split them into a separate module. JUnit 4 tests can run on the JUnit Platform through the JUnit Vintage engine, so a single module with both engines on the test classpath works fine; that is a normal modern mixed setup. The Robolectric runner is a JUnit 4 runner, so whichever route you take, the binder tests have to run somewhere JUnit 4 can see them: Vintage on the platform, or a dedicated JUnit 4 task or source set.

## Apps that connect in Application.onCreate

Many extensions create `KarooSystemService` and call `connect` from `Application.onCreate`, often through a Koin or Hilt singleton. Robolectric creates the application before any JUnit rule runs, so that bind finds no fake and the SDK crashes on a null `ComponentName`. Install the fake from a test application instead, before `super.onCreate()`; the next `FakeKarooRule` adopts the same system:

```kotlin
class TestApplication : Application() {
  override fun onCreate() {
    FakeKarooBinding.installEarly(this)
    super.onCreate()
    startKoin { androidContext(this@TestApplication); modules(appModule) }
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApplication::class)
class MyDeviceTest {
  @get:Rule val karoo = FakeKarooRule()

  @After
  fun tearDown() {
    // Rules wrap @After: destroy the services before Koin goes away, then reset Koin, since
    // Robolectric creates a fresh application per test.
    karoo.close()
    stopKoin()
  }
}
```

If your `Application` class is `open`, subclass it instead of repeating its setup.

### Hilt

With `@HiltAndroidTest` and `@Config(application = HiltTestApplication::class)`, order the rules so Hilt builds its component first. A `@Singleton` that creates `KarooSystemService` binds when it is injected, after both rules ran, so it needs nothing else:

```kotlin
@get:Rule(order = 0) val hilt = HiltAndroidRule(this)
@get:Rule(order = 1) val karoo = FakeKarooRule()

@Before fun inject() = hilt.inject()
```

## Waiting on state outside a recorder

Recorders pump the main looper while they wait. When the thing to wait for is something else, such as a value the extension persisted to DataStore, use `karoo.awaitValue { ... }`: it pumps too. A bare `awaitValue` without an `idle` pump never runs the SDK's connect callback, so the extension never starts.

```kotlin
karoo.system.responder = HttpResponses.status(503)
karoo.host<MyExtension>()
val stats = karoo.awaitValue { runBlocking { karoo.app.streamStats().first() }.takeIf { it.failedRequestAt != null } }
```

Do not call suspending code that talks to the Karoo with `runBlocking` on the test thread: the SDK connects through main-looper callbacks, and nothing pumps the looper while the thread blocks. This includes a DataStore `edit` once the extension is running, which hangs without an error; write it before the extension starts or wait for it with `awaitValue`. Start it elsewhere and wait with the rule:

```kotlin
val pending = CoroutineScope(Dispatchers.Default).async { repository.currentLocation() }
val location = karoo.awaitValue { pending.takeIf { it.isCompleted }?.getCompleted() }
```

Effects the extension dispatches outside a session, such as alerts and beeps, have their own wait: `karoo.awaitEffect<InRideAlert> { it.title == "Drink" }`.

Only the latest point of a stream is replayed to a consumer that registers late. When the extension needs every point, for example to compute a delta between two readings, wait for it to listen first:

```kotlin
karoo.host<MyExtension>()
// The extension combines calories with %FTP, so wait for both inputs.
karoo.awaitStreamConsumer(DataType.Type.CALORIES, DataType.Type.PERCENT_MAX_FTP)
karoo.system.setDataPoint(DataType.Type.CALORIES, 100.0)
karoo.system.setDataPoint(DataType.Type.CALORIES, 140.0)
```

This only proves the consumers are registered. Flow operators in the extension can still drop points: `combine` emits nothing until every input has a value, and `stateIn`, `conflate` or `collectLatest` keep only the newest. Wait for every input of a `combine`, and where the extension conflates, assert on the end state rather than on each point.

Robolectric reuses its class loader across tests in a class, so static caches survive from one test to the next. `preferencesDataStore` is one: clear it in `@Before` (`context.dataStore.edit { it.clear() }`) or a value written by one test leaks into the next.

When a test fails, `FakeKarooRule` attaches a `FakeKarooState` suppressed exception listing the registered consumers, stream states, HTTP requests, effects and what each host's recorders received at the time of the failure, so a recorder timeout shows what the extension was actually waiting on. `karoo.system.describe()` gives the same text on demand.

Broadcasts an extension sends to other apps, such as one asking another extension to hide its overlay, do not go through karoo-ext, so the fake does not see them. Read them from Robolectric with `shadowOf(karoo.app).broadcastIntents`.

## Streams and built-in sensor data

The fake replays the latest `StreamState` to a consumer that registers late. Treat that as fake policy, not a device guarantee: the SDK documents sticky replay only for `RideState` and `UserProfile`, not for stream state. `setDataPoint` is the short form for a `Streaming` point.

Drive a built-in data type such as power, and prove the extension recovers from `Searching` or `NotAvailable` into `Streaming` when data arrives:

```kotlin
val host = karoo.host<MyExtension>()
val stream = host.startStream("my-power-type")
stream.await(10_000) { it is StreamState.Searching }

// A native power point uses DataType.Field.POWER, not SINGLE.
karoo.system.setDataPoint(DataPoint(DataType.Type.POWER, mapOf(DataType.Field.POWER to 250.0)))
val first = stream.await(10_000) { it is StreamState.Streaming } as StreamState.Streaming
assertEquals(250.0, first.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)

// Feed a recoverable state, then a fresh point, and assert the extension comes back.
karoo.system.setStreamState(DataType.Type.POWER, StreamState.NotAvailable)
karoo.system.setDataPoint(DataPoint(DataType.Type.POWER, mapOf(DataType.Field.POWER to 300.0)))
stream.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 300.0 }
```

`setDataPoint(id, value)` is also available as a short form; it wraps `value` under `DataType.Field.SINGLE`, which is fine for a custom data type but not the native power field. A `Streaming` point whose id does not match the data type id is rejected as a test bug.

`setLocation(lat, lng, orientation, accuracy)` publishes both `OnLocationChanged` and a `DataType.Type.LOCATION` stream point with the `LOC_*` fields, since extensions read either; accuracy defaults to a good 5 m fix because some extensions drop fixes above a threshold.

An extension that `combine`s several streams waits until every one of them has emitted. When the test only cares about some, set `karoo.system.initialStreamState = StreamState.NotAvailable` (or `Searching`) so a data type with no state yet answers with that instead of staying silent. It is off by default and fake policy: the device's first state for an idle type is not documented.

State-like events without a typed setter, such as `SavedDevices`, `Bikes` or `OnGlobalPOIs`, are stored and replayed with `setSticky(params, event)`. `setActiveRideProfile(profile)` and `setMapZoom(level)` are typed forms of the same thing; the first covers the common indoor check:

```kotlin
karoo.system.setActiveRideProfile(RideProfile("indoor", "Indoor", emptyList(), true, "indoor_cycling", "road"))
```

For one-shot, non-sticky events that the device does not replay, publish by params. `Lap` has no data type id and its params (`Lap.Params`) carry no keyed id either, so it is a plain params-keyed event:

```kotlin
karoo.system.publish(Lap.Params, Lap(number = 1, durationMs = 120_000, trigger = "manual"))
```

A consumer registered after the publish does not receive it, which is the behavior to assert for lap and other transient events.

## Bluetooth LE devices

An extension that talks to a BLE sensor or light itself, rather than through the Karoo's device streams, runs real `BluetoothLeScanner` and `BluetoothGatt` code. Robolectric shadows those classes, and `FakeBle` and `FakeBlePeripheral` (package `fi.nikosavola.karooext.testing.robolectric.ble`) remove the setup around them: advertisements, the GATT service table, descriptor writes and notifications. You need API 26 or newer; below that Robolectric does not shadow the GATT calls. Which Robolectric works depends on your `compileSdk`:

| Robolectric | `compileSdk` 36 or higher | `compileSdk` 35 or lower |
| --- | --- | --- |
| 4.17 | everything works | `NoClassDefFoundError: BluetoothDevice$BluetoothAddress` in `FakeBle.peripheral` |
| 4.16.1 | everything except reads: `readCharacteristic` gets no callback | everything except reads |

So pick 4.17 and `compileSdk` 36, or 4.16.1 if you cannot raise `compileSdk` and do not read characteristics.

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

light.advertise()                      // waits for the extension to start scanning; untilMatched = true repeats it until a scan's filters accept it
light.connect()                        // waits for connectGatt, then accepts it
light.awaitSubscribed(NUS_TX)          // the extension wrote the notification descriptor
light.notify(NUS_TX, "\$L12800".toByteArray())
val keepalive = light.awaitWrite(NUS_RX)

// A request and response device: answer every write on a separate thread, like hardware.
light.onWrite(NUS_RX) { request -> light.notify(NUS_TX, reply(request)) }
```

`FakeBle` also switches the adapter on and reports the BLE system features, since Robolectric starts with both off and many apps check them before they scan. A BLE library that initializes through `androidx.startup`, such as Kable, is not initialized by Robolectric either; call its initializer in your test setup (`KableInitializer().create(app)`).

What it checks for you, because a mock would not:

- An advertisement only reaches scans whose filters match it, and only while a scan is running.
- `notify` throws when the extension never wrote the notification or indication enable value to that characteristic's descriptor. It cannot see whether the extension also called `setCharacteristicNotification`; forgetting that silences a real phone, and Robolectric does not report it.
- Writes are logged in order (`writes`, `writesTo`, `awaitWrite`), so keepalive and command sequences can be asserted.
- `disconnect()` drops the link from the device side, and the extension has to ask again for a reconnect to happen. `setEnabled(false)` turns the adapter off and sends `ACTION_STATE_CHANGED`.

`onWrite(characteristic) { request -> ... }` plays a request and response device: the handler runs on its own thread after the write returned, typically calling `notify` with the reply, and an exception in it is rethrown by the next wait. `failConnection(status)` and `disconnect(status)` deliver a GATT error status such as 133 or 8. `mark()` and `awaitWrite(after = mark)` wait only for later writes, `setValue` sets what a read returns, `bonded = true` marks the device bonded, `assertNoWrite(characteristic) { predicate }` is the negative of `awaitWrite`, `ble.awaitScans(2)` waits for two scans at once, and switching the adapter off ends scans and drops links. A reconnect needs a fresh `connectGatt` from the extension, which `awaitConnectionRequest` then sees. It returns a request that is already pending at once, so count attempts with `connectionAttempts` to prove a new one was made. `failConnection(status)` fails a request that is still pending, while `disconnect(status)` drops a link that was established, so a retry-until-exhausted test alternates `awaitConnectionRequest()` and `failConnection(133)`. a client that reconnects through `BluetoothGatt.connect()` on a gatt it kept is connected by Robolectric at once. GATT callbacks reach the extension through the main looper after the call that caused them has returned, as from a device, so a test has to pump it: every `await*` does, and plain assertions right after `connect()` or `notify()` may not see the effect yet. After `light.connect()` and `light.notify(...)`, wait for the result with `karoo.awaitValue { extensionState.takeIf { it.ready } }` or a recorder wait before asserting.

The old and the API 33 callback variants (`onCharacteristicChanged` with and without the value) are both delivered as the framework would, whichever your callback overrides. Scan results are delivered on the calling thread. Scans started with a `PendingIntent`, the legacy `startLeScan`, GATT servers, MTU and PHY negotiation and the `autoConnect` flag (the shadow drops it) are not covered. `grantPermissions()` grants what the extension's own `checkSelfPermission` looks at; skip it to test the missing-permission path. Robolectric does not enforce the permissions inside the Bluetooth calls unless you ask it to, with `shadowOf(device).setShouldThrowSecurityExceptions(true)`.

An extension asks the Karoo for the radio with `RequestBluetooth(resourceId)` or `RequestAnt(resourceId)` and gives it back with the matching `Release`. A claim that is never released keeps the radio on for the whole ride. `karoo.system.bluetoothClaims` and `antClaims` list what is still held, and `FakeKarooRule(requireReleasedRadios = true)` fails the test at teardown, after the extension's services are destroyed, when anything is left open or a release had no request.

ANT+ cannot be faked like BLE: it goes through Dynastream's own radio service. Keep the ANT layer behind a small interface of your own and test what the extension does with the decoded values, and use the claim check above for the radio lifecycle.

## HTTP

`FakeKarooSystem.responder` answers proxied requests. `HttpResponses` covers the common shapes: `success(body, headers)`, `status(code)`, `failure(message)`, `notFound()`.

`LiveResponder` is the opt-in to real network access: it forwards each request's method, headers and body to the URL and returns the real status, headers and body, standing in for the Karoo's proxy. Response headers are a single-value map in the SDK, so repeated fields are joined with `", "`, which is lossy fake policy rather than a standards guarantee: a `Set-Cookie` pair cannot round-trip.

For retry and ordering tests use a strict sequence. `HttpResponses.sequence(vararg answers)` hands out one answer per request and, on exhaustion, throws instead of repeating the last one. Read that throw correctly: `SequenceResponder` throws when called directly, but through the fake, `FakeKarooSystem.serve()` catches an exception from any responder and turns it into a status-0 `Complete` whose error string is the exception class name. So an unexpected extra poll does not raise in the test thread; it reaches the extension as a transport failure. Assert on the extension's behavior and on how many requests it made, not on an exception.

`remainingResponses` reports how many answers are left, but it is only meaningful after the extension has actually polled. Set the sequence, drive the extension, then assert:

```kotlin
val host = karoo.host<MyExtension>()
val stream = host.startStream("my-type")
karoo.system.responder = HttpResponses.sequence(
  HttpResponses.failure("timeout"),
  HttpResponses.success("""{"value": 42}""".toByteArray()),
)
karoo.system.setLocation(60.17, 24.94) // the extension fetches on location
val state = stream.await(10_000) { it is StreamState.Streaming } as StreamState.Streaming
assertEquals(42.0, state.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)

// exactly two requests were made: the first failed, the second succeeded
assertEquals(0, (karoo.system.responder as SequenceResponder).remainingResponses)
assertEquals(2, karoo.system.httpRequests.size)
```

Adjust the assertion count to the extension's actual retry policy; the point is to pin the exact number of polls, so a change in retry behavior is caught.

Requests are recorded in `karoo.system.httpRequests`, including headers, so you can assert what the extension sent. `pendingHttpCount` is the number of requests registered but not yet answered or cancelled; use it to prove cancellation on ride end.

## Inspecting what the extension sent back

`FakeKarooHost` drives every `IKarooExtension` method, not just streams, views and maps. Each returns a recorder:

- `startScan()` -> `ScanRecorder` of `Device` the extension advertises.
- `connectDevice(uid)` -> `DeviceRecorder` of `DeviceEvent` (`OnConnectionStatus`, `OnBatteryStatus`, `OnManufacturerInfo`, `OnDataPoint`); `disconnectDevice(recorder)` runs the extension's cancellable.
- `startFit()` -> `FitRecorder` of `FitEffect`; filter with `fit.effectsOf<WriteToRecordMesg>()`.
- `bonusAction(actionId)` -> delivers `onBonusAction` to the extension, like a controller button.

One host can drive several sessions; reuse it rather than starting a new service per action:

```kotlin
val host = karoo.host<MyExtension>()

val scan = host.startScan()
scan.await(10_000) { it.uid == "sensor-1" }

val device = host.connectDevice("sensor-1")
val point = device.awaitOf<OnDataPoint>(10_000)
assertEquals(200.0, point.dataPoint.values.getValue(DataType.Field.POWER), 0.0)

val fit = host.startFit()
val mesg = fit.awaitOf<WriteToRecordMesg>(10_000)
assertEquals(200.0, mesg.values.single().value, 0.0)

host.bonusAction("my-action")
```

Maps usually draw in bursts of show and hide effects. `map.awaitQuiet(quietMs = 300, timeoutMs = 10_000)` waits until the burst stops, and `visiblePolylines()` and `visibleSymbols()` replay the log into what is on screen now, by id. `decodePolyline(effect.encodedPolyline)` turns a polyline back into points:

```kotlin
karoo.system.setMapZoom(14.0)
karoo.system.setLocation(60.17, 24.94)
val map = karoo.host<MyExtension>().startMap()
map.awaitQuiet(quietMs = 300, timeoutMs = 10_000)
val drawn = map.visiblePolylines().values.flatMap { decodePolyline(it.encodedPolyline) }
assertTrue(drawn.all { (lat, _) -> lat in 60.0..60.4 })
```

`Recorder` exposes `items` (read-only), `completed` and `error`. `awaitComplete` fails fast if the extension errored first; `awaitError` fails fast if it completed or timed out. Prefer these over a bare `await` when the contract is completion or failure rather than a specific item.

## Measured RemoteViews layouts

`inflate(context)` applies a frame but does not measure or lay it out, so it reads content, not geometry. To check layout under exact host bounds, use the measured overload with pixel dimensions:

```kotlin
val root = frame.inflate(context, config) // exact config.viewSize pixels
val label = root.descendants().filterIsInstance<TextView>().first()
assertEquals(2, label.layout.lineCount) // or inspect layout geometry
```

For text measurement and wrapping in Robolectric, annotate the test with `@GraphicsMode(GraphicsMode.Mode.NATIVE)`: legacy graphics can use placeholder text metrics, so a line-count assertion is not meaningful without it. Instrumentation tests run against the real Android framework renderer instead.

`config.viewSize` is in pixels, the caller sets the font on the `RemoteViews` (`setTextViewTextSize`), and the `textSize` field of `ViewConfig` is not interpreted by this helper. A small illustrative matrix, varying alignment, preview and boundaries as much as size:

| gridSize | viewSize (px) | textSize (sp) |
| --- | --- | --- |
| 30x15 | 120x90 | 16 |
| 60x15 | 480x90 | 24 |
| 30x60 | 240x360 | 40 |

These are examples, not Karoo profiles or thresholds: replace them with the dimensions your target actually reports, and set expectations from the measured view, not from device profiles. Vary font scale, density, locale/RTL and night with Robolectric qualifiers on configured contexts to assert the extension still renders correctly. This helper does not certify the Karoo's own renderer and makes no automatic clipping or accessibility guarantee.

The existing `descendants()` helper also reaches views for semantics checks; `texts()` reads strings only and does not check accessibility. For an icon, assert the extension's chosen content description:

```kotlin
val icon = root.descendants().filterIsInstance<ImageView>().first()
assertEquals("Distance", icon.contentDescription)
```

## Asserting cleanup after stop

Stopping a session must release the consumer the extension registered. Assert it directly instead of trusting the extension:

```kotlin
val host = karoo.host<MyExtension>()
val before = karoo.system.consumerCount
val stream = host.startStream("my-power-type")
karoo.system.setDataPoint(DataPoint(DataType.Type.POWER, mapOf(DataType.Field.POWER to 100.0)))
stream.await(10_000) { it is StreamState.Streaming }
assertTrue(karoo.system.consumerCount > before)

host.stopStream(stream)
assertEquals(before, karoo.system.consumerCount)
assertTrue(karoo.system.consumerParams.none { it == OnStreamState.StartStreaming(DataType.Type.POWER) })
```

`consumerParams` is a snapshot of the registrations the fake currently holds; it keeps no history, so on its own it cannot tell you that a handler was once present. The before/after `consumerCount` is what proves the consumer was registered and then released, with `consumerParams` confirming which one is gone. `pendingHttpCount` is the same kind of live count for in-flight HTTP. Closing the host (`host.close()`) stops every session it started, so a test can prove cancellables ran at teardown; the rule does this for you after each test.

## Driving terminal callbacks and version checks

`FakeKarooSystem.completeConsumer(id)` and `errorConsumer(id, message)` end an ordinary consumer's session the way the system would, so the SDK's own unregister path runs:

```kotlin
val host = karoo.host<MyExtension>()
host.startStream("my-type")
val id = karoo.system.consumerIds.first()

karoo.system.errorConsumer(id, "dropped") // or completeConsumer(id)
assertEquals(0, karoo.system.consumerCount) // the SDK wrapper unregistered it
```

Both return `false` for an id that is not an ordinary consumer, including a pending HTTP request id, so an unknown id is not mistaken for success. The callback runs first, then the consumer is removed by identity, so a reentrant replacement registered under the same id survives. This is a raw handler hook that drives the SDK's auto-unregister contract, not a claim that the real system ever completes a consumer.

`FakeKarooSystem.libVersion()` reports the karoo-ext `EXT_LIB_VERSION` the fake was compiled against. That is a build-time constant, not the version on the test runtime classpath; pass `libVersion = "..."` to the constructor to make `libVersion()` report another value and exercise version checks.

## Time

There are three time sources in play and they do not move together.

Recorder waits use real elapsed time. `Recorder.await`, `awaitComplete` and `awaitError` measure their timeout with `kotlin.time.TimeSource.Monotonic`, so `await(10_000)` really blocks for up to ten seconds. A bounded-timeout test still works with an empty `pump`, and the budget has to exceed one poll interval (`POLL_MS`, 20 ms) to be meaningful. Coroutine virtual time neither shortens nor drives these waits.

`RobolectricPump.advanceBy(duration)` advances the Robolectric main looper clock by a `kotlin.time.Duration`. It moves that clock only. It does not move the SDK's own `System.currentTimeMillis()` unless that code has been virtualized, and `FakeKarooRule` does not set that up for you.

The SDK view emitter throttles frames with real wall-clock time: `ViewEmitter.updateView` reads `System.currentTimeMillis()` and drops a frame within 900 ms of the last. Robolectric does not intercept that call by default, not even with a runner-wide `@Config(sdk = [35])`. Virtualize it per test class or method by naming the SDK's internal package in `instrumentedPackages`:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
class MyViewThrottleTest {
  @get:Rule val karoo = FakeKarooRule()

  @Test
  fun `a throttled frame arrives after the clock advances`() {
    // Without this the SDK clock starts at 0, inside its 900 ms window, and drops the first frame.
    RobolectricPump.advanceBy(1.seconds)
    val config = ViewConfig(gridSize = 60 to 15, viewSize = 480 to 200, textSize = 30)
    val view = karoo.host<MyExtension>().startView("my-view-type", config)
    view.awaitFrame(10_000)                    // first frame arrives

    val before = view.items.size
    updateViewFromExtension()                  // updateView within 900 ms: dropped by the SDK

    RobolectricPump.advanceBy(1.seconds)       // SDK clock now past the throttle
    updateViewFromExtension()                  // emits
    view.awaitFrame(10_000, after = before)    // the new frame, not the first one
  }
}
```

The `ViewConfig` dimensions above are illustrative; use values matching the layout your test exercises. `updateViewFromExtension()` stands in for whatever makes your extension call `updateView` (for a location-driven field, `karoo.system.setLocation(...)`). Advance the clock past 900 ms before `startView` so the very first frame is not dropped by a window that starts at zero. With the `instrumentedPackages` opt-in, two updates emitted back to back yield one frame, and an update after `advanceBy(1.seconds)` yields the second; without it, `advanceBy` does not affect this throttle because the SDK still reads the real clock. Capture `items.size` first and wait with `after = before` so the first frame cannot satisfy the second wait by accident.

Coroutine time is separate from all of the above. `Dispatchers.IO` is a real dispatcher running on actual background threads; `TestCoroutineScheduler` and `runTest` control a virtual coroutine scheduler. None of them advances the Robolectric main looper, the virtualized SDK clock, or the recorder's monotonic timeout. Keep pure-logic tests that use `runTest` and virtual time separate from binder tests that use blocking recorder waits. Pass `RobolectricPump.invoke()` as a recorder's `pump` (the rule's hosts do) so work the extension posts to the main thread runs while the test thread blocks.

`Duration` overloads exist alongside the `Long` millisecond timeouts: `await(1.seconds) { ... }`, `awaitComplete(5.seconds)`, `awaitError(5.seconds)`. They wait real monotonic time as well.

## Real SDK semantics versus fake policy

Know what the fake does and does not reproduce; several of these are the source of tests that pass in-process but would not hold on hardware.

The fake reproduces the SDK's JSON-in-Bundle wire format (it reuses the SDK's own encoders, so a wire change breaks both sides together), the full `IKarooSystem` AIDL surface, and `onError`/`onComplete` terminating a consumer. The SDK documents sticky replay only for `RideState` and `UserProfile`; the fake implements replay more broadly as fake policy, not as a claim about device behavior.

Fake policy, chosen to keep tests deterministic:

- In-process binder. Calls are synchronous where hardware is `oneway` and asynchronous. Sticky replay lands during `addConsumer` in tests; on hardware it arrives after the call returns. Do not build a test whose correctness depends on that ordering being guaranteed on device.
- No BLE radio. The fake never decodes BLE or ANT bytes. Extensions own their device protocol bytes; the system service owns ANT decode for paired sensors and exposes typed fields. Do not expect the fake to exercise a radio path.
- No exact hardware timing. The fake drives no device clock; cadence and jitter are the test's to script.
- No offline proxy mode. The SDK documents `waitForConnection` and `Queued`, but not the real queue timings or the phone-side implementation, so the fake does not model offline queuing and serves requests immediately. Do not read its parameter timing as a device guarantee.
- Sticky replay for location, navigation, the active page and stream state is fake policy. The SDK documents replay only for `RideState` and `UserProfile`; any other sticky behavior is a modeling choice, not a claim about the device.

Reset versus close. `reset()` clears state and stays reusable: it restores the constructor `hardwareType`, resets the HTTP responder and body limit to their library defaults, keeps the fixed `libVersion`, and invalidates in-flight HTTP, so an answer produced before the reset is never delivered into the next test. `close()` is terminal and idempotent: it drops consumers, cancels in-flight HTTP and shuts down the executor. After close, registering a new consumer is rejected; the setters and `info()` remain callable but publish to nothing. The rule closes the system after each test; call `reset()` yourself only when you want a clean system mid-test.

## Emulator tests

The appstore artifact exposes a process-wide `FakeKaroo.system`, and the bound `AppStoreService` returns that same instance for the whole test process. Reset it around each test instead of closing it:

```kotlin
class MyEmulatorTest {
  @Before fun resetFake() = FakeKaroo.system.reset()

  @After fun tearDown() {
    // Stop or disconnect any session you started, then reset.
    FakeKaroo.system.reset()
  }
}
```

`reset()` clears state and leaves the fake reusable. `close()` is terminal, so never close `FakeKaroo.system` between tests, or every later test in the process binds to a dead fake.

### Dedicated AVD

The fixture app uses the fixed package `io.hammerhead.appstore` and declares an exported stand-in service, so run it only on a dedicated AOSP AVD (no GMS), never a daily-driver or a device with a real Karoo. Create one with the SDK manager, picking the `default` system image for your host architecture (x86_64 on Intel/AMD, arm64-v8a on Apple silicon), or use the same image in Android Studio's Device Manager:

```bash
sdkmanager "system-images;android-31;default;x86_64"
```

Name the AVD `karoo-library-smoke-api31` so the smoke recipe accepts it, boot it, and confirm it shows in `adb devices`:

```bash
just integration-build                # assemble the fixture and instrumentation APKs, no install
just integration-test emulator-5554   # explicit serial, refused on any AVD not named karoo-library-smoke-*
```

The app's `release` variant is disabled and its manifest is `testOnly`. The bridged HTTP tests answer from the fake responder and make no external network calls.

## Toolchain and compatibility

The library is built with Kotlin 2.4.20 on JDK 21 but publishes Java 8 bytecode (class-file major 52) with Kotlin 2.1 metadata and a kotlin-stdlib 2.1.0 dependency, so a test source set targeting JVM 1.8 or 11 can still inline the reified helpers such as `karoo.host<T>()`, and a Kotlin 2.0 or newer compiler can read it. The artifacts declare minSdk 23 and minCompileSdk 33. It has been used from extensions on Kotlin 2.0 to 2.4 and AGP 8.5 to 9.4.

It is compiled against karoo-ext 1.1.9, and an older karoo-ext should work for the parts it already has. Connecting and HTTP were run on 1.1.3 and everything else on 1.1.7 to 1.1.9. FIT effects need 1.1.4, `setRoute` needs 1.1.6 and `bonusAction` needs 1.1.7. Calling one of those on an older version fails with `NoSuchMethodError`. Robolectric itself needs a JDK 17 or newer test runtime. Robolectric 4.17 pulls `bcprov` with newer class files than the Android jetifier reads, so a project with `android.enableJetifier=true` needs `android.jetifier.ignorelist=bcprov-jdk18on`.

Add the fakes as a test dependency; nothing here requires changing your app's release toolchain. If your project pins an older toolchain, keep the Robolectric setup in its own test configuration or module.

Official references: the Kotlin [Evolution and Compatibility](https://kotlinlang.org/docs/evolution-compatibility.html) guide and the Android [build-tools / D8](https://developer.android.com/build) documentation.

## Further reading

- [Fundamentals of testing Android apps](https://developer.android.com/training/testing/fundamentals)
- [Test on instrumented devices](https://developer.android.com/training/testing/instrumented-tests)
- [Architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Adaptive layouts](https://developer.android.com/develop/ui/views/layout/adaptive-layouts)
- [Core app quality](https://developer.android.com/docs/quality-guidelines/core-app-quality)
- [Android Compatibility Definition Document](https://source.android.com/docs/compatibility/cdd)

The stand-in models the Karoo system app's binder contract; it makes no compatibility or conformance claim about any CDD device or OEM build.
