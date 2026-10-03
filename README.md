# karoo-ext-testing

Test doubles for the Karoo system side of [karoo-ext](https://github.com/hammerheadnav/karoo-ext). They let a
Karoo extension be tested on the JVM with Robolectric, and on an emulator without a Karoo, by standing in for the
`KarooSystemService` the extension binds to.

Test and debug tooling only: do not ship these artifacts in a release APK.

## Docs

- [Testing guide](docs/testing-guide.md): recipes for binding a real extension service, driving sensor streams, HTTP sequence and failure paths, FIT/device/bonus outputs, cleanup assertions, and time.
- [API reference](https://nikosavola.github.io/karoo-ext-testing/): Dokka HTML for all three modules, with source links pinned to the commit it was built from. The `Docs` workflow builds it on pushes to `main` and publishes through GitHub Pages, which is not live until a one-time repository setup: **Settings > Pages > Build and deployment > Source: GitHub Actions**. Run the workflow manually from the Actions tab to build the site without publishing a feature branch.

## Artifacts

Three artifacts, all published together:

- `karoo-ext-testing`: `FakeKarooSystem` (an in-process `IKarooSystem`, `Closeable`), `FakeKarooHost` and its
  recorders for streams, views, maps, scans, device connections and FIT, HTTP responders (`HttpResponder`,
  `HttpResponses`, `SequenceResponder`, `RoutingResponder`, `LiveResponder`), `CapturingHandler` and `awaitValue`,
  RemoteViews inspection helpers, and `encodePolyline`.
- `karoo-ext-testing-robolectric`: `FakeKarooBinding.install` to point KarooSystemService's bind at a
  `FakeKarooSystem`, `RobolectricPump`, and a generic `FakeKarooRule`.
- `karoo-ext-testing-appstore`: an Android library whose manifest declares the exported
  `io.hammerhead.appstore.service.AppStoreService` returning a process-wide `FakeKaroo.system`, for emulator tests.

## karoo-ext is compileOnly

Every artifact depends on karoo-ext with `compileOnly`. It is never brought in transitively: consumers bring their
own karoo-ext, and some use different coordinates. Add it yourself, matching whatever your extension already uses:

```kotlin
testImplementation("com.github.hammerheadnav:karoo-ext:1.1.9")
```

`FakeKarooSystem` uses karoo-ext's own JSON-in-Bundle encoders, so a wire-format change breaks the fakes too.
kotlinx-serialization-json is compileOnly as well; karoo-ext brings it at runtime.

## Coordinates

Each tag is published twice under the same coordinates, so a consumer only picks the repository:

```kotlin
testImplementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing:<tag>")
testImplementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing-robolectric:<tag>")
implementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing-appstore:<tag>")
```

JitPack builds the tag on first request (`jitpack.yml`) and needs no credentials:

```kotlin
// settings.gradle.kts, dependencyResolutionManagement.repositories
exclusiveContent {
  forRepository { maven("https://jitpack.io") }
  filter { includeGroup("com.github.nikosavola.karoo-ext-testing") }
}
```

GitHub Packages gets the same build from the publish workflow when a tag is pushed. Its Maven feed needs a token with `read:packages` even for public packages, from `~/.gradle/gradle.properties` locally or `GITHUB_TOKEN` in Actions:

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

## Releasing

Tag a plain version, `git tag 0.1.0 && git push origin 0.1.0`. JitPack uses the tag name as the version, so no `v` prefix. The publish workflow lints, tests and pushes to GitHub Packages; open `https://jitpack.io/#nikosavola/karoo-ext-testing` once to start the JitPack build and check its log. `gradle.properties` only holds the version for local builds.

## Robolectric

Bind the extension's `KarooSystemService` to the fake, start your real extension service the way the Karoo ride
app does, and drive it:

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

`FakeKarooRule` installs the binding before each test (via `FakeKarooBinding.install`) and tears everything down
after: it closes every host (stopping its sessions), destroys their services, then closes the fake system. If you do
not want the rule, call `FakeKarooBinding.install(application, system)` yourself and pass
`RobolectricPump.invoke()` as a recorder's `pump` so main-thread work runs while a test blocks.

`FakeKarooSystem` replays the current value to a consumer that registers late: location, navigation, ride state,
user profile, active page and stream state. The SDK documents this replay only for ride state and user profile; the
rest is fake policy chosen for deterministic tests. Set them with `setLocation`, `setNavigation`, `setRoute` (a
polyline the ride app would follow, with its length measured along it), `setRideState`, `setUserProfile` and
`showPage`; publish one-shot events keyed by params with `publish(params, event)`, and drive built-in data types with
`setStreamState(id, state)` or `setDataPoint(id, value)`. `reset()` clears everything back to defaults and stays
reusable; `close()` is terminal. Bridged HTTP requests go through `responder` and are recorded in `httpRequests`;
effects the extension dispatches land in `effects` (filter with `effectsOf<T>()`). Recorders expose `items` read-only
plus `completed`/`error`, with `await` (Long and `Duration`), `awaitComplete` and `awaitError`; `consumerCount`,
`consumerParams`, `pendingHttpCount` and `streams` let a test assert cleanup. `HttpResponses.sequence(...)` answers
requests in order, one per request; a request past the end becomes a status 0 error carrying the exception class name
through the fake, so assert the request count or `remainingResponses` rather than relying on a loud failure.
`completeConsumer(id)` and `errorConsumer(id, message)` drive a consumer's
terminal callbacks as a raw handler hook, and `libVersion` reports the SDK the fake was built against unless the
constructor overrides it. `RobolectricPump.advanceBy(Duration)` advances the Robolectric main looper
clock; virtualizing the SDK's own wall clock for the view frame throttle is a per-test opt-in described in the
[testing guide](docs/testing-guide.md).

## Emulator

Create a tiny app module with `applicationId io.hammerhead.appstore` that depends on the appstore artifact, and run
your instrumentation tests there. karoo-ext's `KarooSystemService` binds `io.hammerhead.appstore.service.AppStoreService`
by name, so the fake answers in place of the real system app:

```kotlin
// app/build.gradle.kts
android {
  namespace = "com.example.extensionappstore"
  defaultConfig { applicationId = "io.hammerhead.appstore"; minSdk = 26 }
}

dependencies {
  implementation("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing-appstore:<tag>")
  // compileOnly in the library, so the fake app brings it.
  implementation("com.github.hammerheadnav:karoo-ext:1.1.9")
}
```

Your extension app's debug manifest also needs to see that package, or its bind to
`ComponentName("io.hammerhead.appstore", "io.hammerhead.appstore.service.AppStoreService")` is filtered out:

```xml
<queries>
  <package android:name="io.hammerhead.appstore" />
</queries>
```

Connected tests install on every attached device. Never run them with a real Karoo or phone attached: the fake
claims the Karoo system app's package name, and the tests would install and run there too. Pin the emulator with
`ANDROID_SERIAL`.

`FakeKaroo.system` is process-wide: call `FakeKaroo.system.reset()` before and after each test and never `close()` it, or later tests in the same process bind to a dead fake. The [testing guide](docs/testing-guide.md) has a template.

## Coverage and static analysis

CI uploads JaCoCo coverage and JUnit results to [Codecov](https://codecov.io/gh/nikosavola/karoo-ext-testing). The `SonarQube` workflow runs [SonarQube Cloud](https://sonarcloud.io/summary/new_code?id=nikosavola_karoo-ext-testing) analysis. Both need one-time setup:

- Import the repository through the services' GitHub integrations, granting the SonarQube Cloud GitHub App access for PR decoration. Use project key `nikosavola_karoo-ext-testing` and organization `nikosavola`.
- Add `CODECOV_TOKEN` and `SONAR_TOKEN` repository secrets, and disable SonarQube Cloud automatic analysis so Gradle CI handles it.
- Once Codecov is active, set `fail_ci_if_error: true` on the upload steps to catch upload failures.

During setup, Codecov uploads use `fail_ci_if_error: false`, and SonarQube analysis skips when `SONAR_TOKEN` is unset.

## Local development

JDK 21 is required. The `justfile` at the repo root wraps the common contributor commands; run `just` to list them.

- `just verify` runs `lintAll`, `build`, `test`, `selfTest` and `:dokkaGeneratePublicationHtml`, the same set the CI workflow checks.
- `just self-test` runs only the fakes' own debug unit tests and writes JaCoCo coverage to `*/build/reports/coverage/test/debug`. There is no coverage threshold: the reports show which fake paths a test actually exercises.
- `just docs` builds the Dokka API site into `build/dokka/html`.
- `just docs-serve` builds the site and serves it on [http://127.0.0.1:8000](http://127.0.0.1:8000) from `build/dokka/html` using Python 3's `http.server`; override the port with `just docs-serve 9000` and stop it with Ctrl-C.
- API styling lives in `docs/styles/alpine.css`, applied by Dokka through the root `customStyleSheets`, so `just docs` and the Pages build pick it up with no extra step.
- Public KDoc links to compiler-checked samples in `testing/src/test/kotlin/fi/nikosavola/karooext/testing/samples/ApiSamples.kt` with `@sample`. They are snippets, not tests, but they must still compile: `:dokkaGeneratePublicationHtml` depends on the testing unit-test compilation so a broken sample fails the docs build.
- `just lint`, `just test` and `just build` run `lintAll`, `test` and `build` on their own.

Without `just`, the Gradle wrapper equivalents:

```bash
./gradlew lintAll build test selfTest :dokkaGeneratePublicationHtml  # full CI gate
./gradlew selfTest  # fakes' own tests + coverage
./gradlew :dokkaGeneratePublicationHtml  # API site into build/dokka/html
```

Test the library against your extension before releasing, without publishing:

```kotlin
// settings.gradle.kts
includeBuild("../karoo-ext-testing") {
  dependencySubstitution {
    substitute(module("com.github.nikosavola.karoo-ext-testing:karoo-ext-testing"))
      .using(project(":testing"))
  }
}
```

Substitute the other two modules the same way if you use them.
