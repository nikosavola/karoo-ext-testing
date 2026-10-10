# Maintaining

Notes for working on this repository: the local workflow, one-time service setup and releasing. Contributor basics are in [CONTRIBUTING](../.github/CONTRIBUTING.md).

## Local development

JDK 21 is required. The `justfile` at the repo root wraps the common commands; run `just` to list them.

- `just verify` runs `lintAll`, `build`, `test`, `selfTest` and `:dokkaGeneratePublicationHtml`, the same set the `Lint`, `Build` and `Test` jobs of the CI workflow check.
- `just self-test` runs only the fakes' own debug unit tests and writes JaCoCo coverage to `*/build/reports/coverage/test/debug`. There is no coverage threshold: the reports show which fake paths a test actually exercises.
- `just integration-build` assembles the separate-process fixture and its instrumentation APKs without installing them.
- `just integration-test emulator-5554` runs the smoke tests (the `Smoke` CI job) on an explicit serial, after checking it is a dedicated `karoo-library-smoke-*` AVD. See the testing guide's test strategy section for choosing a layer.
- `just docs` builds the Dokka API site into `build/dokka/html`, and `just docs-serve` serves it on [http://127.0.0.1:8000](http://127.0.0.1:8000) with Python 3's `http.server`; override the port with `just docs-serve 9000`.
- `just lint`, `just test` and `just build` run `lintAll`, `test` and `build` on their own.

Without `just`, the Gradle wrapper equivalents:

```bash
./gradlew lintAll build test selfTest :dokkaGeneratePublicationHtml  # full CI gate
./gradlew selfTest  # fakes' own tests + coverage
./gradlew :dokkaGeneratePublicationHtml  # API site into build/dokka/html
```

API styling lives in `docs/styles/alpine.css`, applied by Dokka through the root `customStyleSheets`. Public KDoc links to compiler-checked samples in `testing/src/test/kotlin/fi/nikosavola/karooext/testing/samples/ApiSamples.kt` with `@sample`. They are snippets, not tests, but they must still compile: `:dokkaGeneratePublicationHtml` depends on the testing unit-test compilation so a broken sample fails the docs build.

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

Substitute the other two modules the same way if you use them. `./gradlew publishToMavenLocal` puts `0.1.1` (from `gradle.properties`) into `mavenLocal()` for a quick local trial.

## One-time repository setup

- Pages: **Settings > Pages > Build and deployment > Source: GitHub Actions**. The `Docs` workflow builds the API site on pushes to `main` and publishes it; run it manually from the Actions tab to build the site without publishing a feature branch.
- Coverage and analysis: CI uploads JaCoCo coverage and JUnit results to [Codecov](https://codecov.io/gh/nikosavola/karoo-ext-testing), and the `Sonar` job of the CI workflow runs [SonarQube Cloud](https://sonarcloud.io/summary/new_code?id=nikosavola_karoo-ext-testing) analysis. Import the repository through both services' GitHub integrations, use project key `nikosavola_karoo-ext-testing` and organization `nikosavola`, add `CODECOV_TOKEN` and `SONAR_TOKEN` repository secrets, and disable SonarQube Cloud automatic analysis so Gradle CI handles it. The Codecov uploads tolerate failure, so an outage does not block a merge, and the Sonar analysis skips when `SONAR_TOKEN` is unset, as on fork pull requests. The branch ruleset requires the `Lint`, `Build`, `Test` and `Smoke` checks; rename them there when a job is renamed.
- Security: enable private vulnerability reporting under **Settings > Code security**, which the [security policy](../.github/SECURITY.md) points reporters to.

## Releasing

Tag a plain version, `git tag 0.1.0 && git push origin 0.1.0`. JitPack uses the tag name as the version, so no `v` prefix. The publish workflow lints, tests and pushes to GitHub Packages; open `https://jitpack.io/#nikosavola/karoo-ext-testing` once to start the JitPack build and check its log. `gradle.properties` only holds the version for local builds.
