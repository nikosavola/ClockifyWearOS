# Contributing

Inside the project directory, you may run the following commands.

## Development Setup

```bash
just install-pre
```

Installs the pre-commit hooks so format/lint run on every commit.

## Running tests

```bash
just test
```

`ScreenScreenshotsTest` renders each screen to a PNG as it runs, asserting that what came out has
actual content in it rather than a blank frame. Those images land in `wear/build/screenshots`, and
`just screenshots` copies them into `docs/screenshots` for the README.

## Localization

The watch app's existing strings are translated into nine languages; those translations stay. New
user-facing strings ship in English only: an unreviewed machine translation is worse than no
translation, and the nine locale files are a real cost for every string added.

Add such a string to `wear/src/main/res/values/strings.xml` and to no other locale, and mark it so
lint's `MissingTranslation` check does not fail the build:

```xml
<resources xmlns:tools="http://schemas.android.com/tools">
  <string name="example" tools:ignore="MissingTranslation">Example</string>
</resources>
```

`strings.xml` does not declare `xmlns:tools` yet, so the first string to need this adds it. Prefer
an icon carrying a content description over a new label wherever one carries the meaning.

## Running linting & formatting

```bash
just format
just lint
```

Or run every pre-commit hook (formatting, linting, and the rest) against all tracked files:

```bash
just precommit
```

## Full local gate

```bash
just verify
```

Runs lint, build, and test together, matching what CI runs on every push.

## Device and emulator commands

See `just --list` for the full set of `device`/`emulator` recipes (connecting to a watch,
installing the debug APK, streaming logcat, booting an emulator, and so on).

## AI Usage Policy

The use of AI tools to accelerate your development workflow, whether for prototyping, writing tests, or improving
documentation, is **encouraged**.

However, as a contributor, you remain **fully responsible** for the code and content you submit. Please ensure the
following:

1. **No "AI Slop"**: Do not submit unreviewed, low-quality, or redundant AI-generated content.
1. **Verify & Test**: All AI-generated code must be reviewed, tested, and verified to work as intended.
1. **Maintainability**: The content must be clear, idiomatic, and maintainable by a human.
