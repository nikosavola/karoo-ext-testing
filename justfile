# Run `just` to list recipes.
#
# max_workers caps Gradle parallelism; a workstation preference, so it lives here rather than in
# the committed gradle.properties. Override with JUST_MAX_WORKERS.

max_workers := env('JUST_MAX_WORKERS', '2')
gradle := './gradlew --max-workers=' + max_workers

# List available recipes
default:
    @just --list

# Assemble all modules and run their default checks (lintAll and docs are separate recipes)
[group('build')]
build:
    {{ gradle }} build

# Run the full CI verification: lintAll, build, test, selfTest and Dokka; host checks only, not a device run
[group('build')]
verify:
    {{ gradle }} lintAll build test selfTest :dokkaGeneratePublicationHtml

# Auto-format Kotlin sources with ktfmt and ktlint
[group('lint')]
format:
    {{ gradle }} formatAll

# Run ktfmt, ktlint, detekt and Android Lint, matching CI
[group('lint')]
lint:
    {{ gradle }} lintAll

# Install the prek git hooks
[group('lint')]
install-hooks:
    prek install

# Run every prek hook against repository files
[group('lint')]
precommit:
    #!/usr/bin/env bash
    set -euo pipefail
    # `--all-files` skips new jj files, which aren't in git's index yet.
    if [ -d .jj ]; then jj file list --no-pager | xargs prek run --files; else prek run --all-files; fi

# Build the Dokka API documentation site into build/dokka/html
[group('docs')]
docs:
    {{ gradle }} :dokkaGeneratePublicationHtml

# Build and preview docs on localhost (requires Python 3)
[group('docs')]
docs-serve port='8000': docs
    python3 -m http.server {{ quote(port) }} --bind 127.0.0.1 --directory build/dokka/html

# Run all host-JVM unit tests
[group('test')]
test:
    {{ gradle }} test

# Run the fakes' own test suites and write JaCoCo coverage reports
[group('test')]
self-test:
    {{ gradle }} selfTest

# Assemble the integration fixture and its instrumentation APKs without installing them
[group('test')]
integration-build:
    {{ gradle }} :integration-test-app:assembleDebug :integration-test-app:assembleDebugAndroidTest

# Run the Android smoke tests on an explicit dedicated emulator serial, e.g. emulator-5554
[group('test')]
integration-test serial:
    #!/usr/bin/env bash
    set -euo pipefail
    serial={{ quote(serial) }}
    if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
        echo "serial must look like emulator-5554, got $serial" >&2
        exit 1
    fi
    # Refuse a daily-driver AVD: the fixture claims the Karoo system app's package name.
    avd="$(adb -s "$serial" emu avd name | sed -n '1s/\r$//p')"
    if [[ "$avd" != karoo-library-smoke-* ]]; then
        echo "refusing to run on '$avd': expected a dedicated karoo-library-smoke-* AVD" >&2
        exit 1
    fi
    ANDROID_SERIAL="$serial" \
        {{ gradle }} :integration-test-app:connectedDebugAndroidTest \
        -Pandroid.injected.device.serial="$serial"

# Publish every module to the local Maven repository (~/.m2)
[group('publish')]
publish-local:
    {{ gradle }} publishToMavenLocal
