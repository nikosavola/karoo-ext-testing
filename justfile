# Run `just` to list recipes.
#
# max_workers caps Gradle parallelism; a workstation preference, so it lives here rather than in
# the committed gradle.properties. Override with JUST_MAX_WORKERS.

max_workers := env('JUST_MAX_WORKERS', '2')
gradle := './gradlew --max-workers=' + max_workers

# List available recipes
default:
    @just --list

# Build all modules and run every check
[group('build')]
build:
    {{ gradle }} build

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

# Run every prek hook against all tracked files. In a jj repo new files aren't in git's index yet,
# so `--all-files` would skip them.
[group('lint')]
precommit:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -d .jj ]; then jj file list --no-pager | xargs prek run --files; else prek run --all-files; fi

# Run all host-JVM unit tests
[group('test')]
test:
    {{ gradle }} test

# Publish every module to the local Maven repository (~/.m2)
[group('publish')]
publish-local:
    {{ gradle }} publishToMavenLocal
