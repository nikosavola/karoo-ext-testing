# Module karoo-ext-testing

Test doubles for the Karoo system side of [karoo-ext](https://github.com/hammerheadnav/karoo-ext), published as three artifacts: [testing](testing/index.html) holds the fakes, [robolectric](robolectric/index.html) the binder glue for JVM tests, and [appstore](appstore/index.html) the system app stand-in for emulator tests. Test and debug tooling only: do not ship these artifacts in a release APK.

## Testing guide

Recipes for testing a Karoo extension against the fakes in this repo. The fakes stand in for the Karoo system side of the karoo-ext binder: the `KarooSystemService` an extension binds to. They are test and debug tooling only. Do not ship any of these artifacts in a release APK.

The examples below describe the current source API. The library is pre-1.0 and the API is still changing, and some of it may not be in a published tag yet, so if a symbol is missing in the tag you pinned, check that tag's published sources.

## Dependencies

Test doubles are for the test source set. Add the artifact and your own karoo-ext:

```kotlin
dependencies {
  testImplementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing:<tag>")
  testImplementation("com.github.hammerheadnav:karoo-ext:1.1.9") // your version
}
```

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

## Robolectric: bind your real extension service

Robolectric runs the real binder in-process, so the extension's actual `KarooSystemService`, `DataTypeImpl` and `Emitter` code execute; only the Karoo system end is replaced. Use JUnit 4 with the Robolectric runner and an explicit SDK. `FakeKarooRule` installs the binding before each test and, after the test, stops every session it started, destroys the services and closes the fake system.

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MyFieldTest {
  @get:Rule val karoo = FakeKarooRule()

  @Test
  fun `location reaches the stream`() {
    val stream = karoo.host<MyExtension>().startStream("my-type")
    karoo.system.setLocation(60.17, 24.94)
    val state = stream.await(10_000) { it is StreamState.Streaming } as StreamState.Streaming
    assertEquals(60.17, state.dataPoint.values.getValue(DataType.Field.SINGLE), 0.0)
  }
}
```

`karoo.host<MyExtension>()` starts the service the way the ride app does and returns a `FakeKarooHost`. Start the sessions your test needs; the recording handle doubles as the stop handle (`host.stopStream(recorder)`) and `host.close()` stops everything.

If your project's own logic tests already run on the JUnit Platform (JUnit 5), you do not have to split them into a separate module. JUnit 4 tests can run on the JUnit Platform through the JUnit Vintage engine, so a single module with both engines on the test classpath works fine; that is a normal modern mixed setup. The Robolectric runner is a JUnit 4 runner, so whichever route you take, the binder tests have to run somewhere JUnit 4 can see them: Vintage on the platform, or a dedicated JUnit 4 task or source set.

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

For one-shot, non-sticky events that the device does not replay, publish by params. `Lap` has no data type id and its params (`Lap.Params`) carry no keyed id either, so it is a plain params-keyed event:

```kotlin
karoo.system.publish(Lap.Params, Lap(number = 1, durationMs = 120_000, trigger = "manual"))
```

A consumer registered after the publish does not receive it, which is the behavior to assert for lap and other transient events.

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
val point = device.await(10_000) { it is OnDataPoint } as OnDataPoint
assertEquals(200.0, point.dataPoint.values.getValue(DataType.Field.POWER), 0.0)

val fit = host.startFit()
val mesg = fit.await(10_000) { it is WriteToRecordMesg } as WriteToRecordMesg
assertEquals(200.0, mesg.values.single().value, 0.0)

host.bonusAction("my-action")
```

`Recorder` exposes `items` (read-only), `completed` and `error`. `awaitComplete` fails fast if the extension errored first; `awaitError` fails fast if it completed or timed out. Prefer these over a bare `await` when the contract is completion or failure rather than a specific item.

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

`updateViewFromExtension()` stands in for whatever makes your extension call `updateView` (for a location-driven field, `karoo.system.setLocation(...)`). Advance the clock past 900 ms before `startView` so the very first frame is not dropped by a window that starts at zero. With the `instrumentedPackages` opt-in, two updates emitted back to back yield one frame, and an update after `advanceBy(1.seconds)` yields the second; without it, `advanceBy` does not affect this throttle because the SDK still reads the real clock. Capture `items.size` first and wait with `after = before` so the first frame cannot satisfy the second wait by accident.

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

Reset versus close. `reset()` returns the fake to defaults and stays reusable (it also invalidates in-flight HTTP, so an answer produced before the reset is never delivered into the next test). `close()` is terminal and idempotent: it drops consumers, cancels in-flight HTTP and shuts down the executor. After close, registering a new consumer is rejected; the setters and `info()` remain callable but publish to nothing. The rule closes the system after each test; call `reset()` yourself only when you want a clean system mid-test.

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

## Toolchain and compatibility

The library is built with Kotlin 2.4.20 and publishes Java 21 bytecode (`jvmToolchain(21)`, class-file major 65) with Kotlin 2.4.0 metadata. Two requirements apply to whichever test configuration consumes it:

- The test source set needs a Kotlin compiler 2.3 or newer to read the metadata; older compilers fail with a metadata version error. That 2.3 floor is what was observed in testing, not a guarantee for every setup.
- The Android build needs a D8/R8 new enough to dex Java 21 class files (recent build-tools). Older build-tools fail with "Unsupported class file major version 65" even when the compiler is new enough.

Add the fakes as a test dependency; nothing here requires changing your app's release toolchain. If your project pins an older toolchain, keep the Robolectric setup in its own test configuration or module.

Official references: the Kotlin [Evolution and Compatibility](https://kotlinlang.org/docs/evolution-compatibility.html) guide and the Android [build-tools / D8](https://developer.android.com/build) documentation.
