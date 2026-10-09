# Contributing

Bug reports, fixes and new fakes for behavior your extension needs are welcome. Open an issue first for anything larger
than a small fix, so the API stays coherent.

## Development setup

JDK 21 and the Android SDK (platform 37) are required. Point Gradle at the SDK with `sdk.dir` in `local.properties`, then
run the recipes from the repository root; `just` lists them.

```bash
just install-hooks  # prek git hooks
just verify         # lintAll, build, test, selfTest and the Dokka site, the same gate as CI
```

## Running tests

```bash
just test       # host-JVM unit tests of all modules
just self-test  # the fakes' own suites with JaCoCo coverage
```

The [testing guide](../docs/testing-guide.md) explains the test layers and when the emulator smoke tests apply.

## Linting and formatting

```bash
just format      # ktfmt and ktlint
just lint        # ktfmt, ktlint, detekt and Android Lint
just precommit   # every prek hook over the repository
```

## Changes

- A fake should behave like the part of the Karoo or Android it replaces, and say so in its KDoc where it does not.
  Prefer failing loudly on a test bug over accepting it quietly.
- Cover new behavior with a self-test next to the code, and extend the testing guide when a recipe changes. Public
  examples in the KDoc must compile.
- Keep commits focused, with a short imperative summary line and no wrapped body text.

## AI usage policy

The use of AI tools to accelerate your development workflow, whether for prototyping, writing tests, or improving
documentation, is **encouraged**.

However, as a contributor, you remain **fully responsible** for the code and content you submit. Please ensure the
following:

1. **No "AI Slop"**: Do not submit unreviewed, low-quality, or redundant AI-generated content.
1. **Verify & Test**: All AI-generated code must be reviewed, tested, and verified to work as intended.
1. **Maintainability**: The content must be clear, idiomatic, and maintainable by a human.
