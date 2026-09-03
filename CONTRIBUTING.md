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

## Manual QA for the phone companion app

Changes to the companion sign-in flow (`mobile/`, `wear/src/main/java/.../companion/`,
`companion-protocol/`) need a real paired phone and watch to verify - see
[docs/COMPANION_QA.md](docs/COMPANION_QA.md) for the checklist.

## AI Usage Policy

The use of AI tools to accelerate your development workflow, whether for prototyping, writing tests, or improving
documentation, is **encouraged**.

However, as a contributor, you remain **fully responsible** for the code and content you submit. Please ensure the
following:

1. **No "AI Slop"**: Do not submit unreviewed, low-quality, or redundant AI-generated content.
1. **Verify & Test**: All AI-generated code must be reviewed, tested, and verified to work as intended.
1. **Maintainability**: The content must be clear, idiomatic, and maintainable by a human.
