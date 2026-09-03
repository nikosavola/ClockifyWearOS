# ![ClockifyWearOS logo](docs/logo.png) ClockifyWearOS

[![CI](https://github.com/nikosavola/ClockifyWearOS/actions/workflows/ci.yml/badge.svg)](https://github.com/nikosavola/ClockifyWearOS/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/nikosavola/ClockifyWearOS/graph/badge.svg)](https://codecov.io/gh/nikosavola/ClockifyWearOS)
[![Quality gate status](https://sonarcloud.io/api/project_badges/measure?project=nikosavola_ClockifyWearOS&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=nikosavola_ClockifyWearOS)
[![License: Apache 2.0](https://img.shields.io/github/license/nikosavola/ClockifyWearOS)](LICENSE)

---

A [Clockify](https://clockify.me/) time-tracking client for Wear OS. Start and stop timers,
switch projects and tasks, and see the running entry on your wrist, without reaching for your
phone.

## Screenshots

|                 | Idle                                                  | Running                                                     |
| --------------- | ------------------------------------------------------ | ------------------------------------------------------------ |
| **Main screen** | ![Idle main screen](docs/screenshots/timer_idle.png) | ![Running main screen](docs/screenshots/timer_running.png) |
| **Tile**        | ![Idle tile](docs/screenshots/tile_idle.png)         | ![Running tile](docs/screenshots/tile_running.png)          |

Project names and elapsed times shown above are placeholders.

## Requirements

- A Wear OS 3+ watch (Android API 30 or newer)
- A [Clockify](https://clockify.me/) account and its API key, from **Profile settings** on
  clockify.me

Clockify has no official OAuth flow for third-party apps, so signing in means entering that API
key once. See [Configuration](#configuration) below and [Known limitations](#known-limitations).

## Installation

Not yet published to the Play Store (see [docs/RELEASING.md](docs/RELEASING.md) for the release
pipeline). The phone companion app (`mobile/`) isn't published or released through that pipeline
yet at all - it currently only exists as a debug build. Until then:

- **Prebuilt APK** (watch app): grab the latest APK from
  [Releases](https://github.com/nikosavola/ClockifyWearOS/releases) and sideload it with
  `adb install`.
- **Build from source** (either app): see [Contributing](#contributing) below.

## Configuration

**On the watch itself.** Open **Settings** (swipe left from the main screen) and paste your
Clockify API key. Typing a ~48-character key with the watch's own keyboard is painful, so the field
also accepts a paste from your phone's clipboard, which syncs to the watch automatically on
Wear OS.

**Alternative: the phone companion app** (`mobile/`). Install it on the phone paired with your
watch, paste your Clockify API key there, and tap "Sign in on watch" - it's sent to the watch over
Bluetooth and validated there, with the result shown back on the phone. Not yet published to the
Play Store, and not yet verified end to end on real paired hardware (see
[docs/RELEASING.md](docs/RELEASING.md) section 8) - build and side-load it via
`just install-mobile` (see [Contributing](#contributing)) if you want to try it.

## Features

- **Start/stop timers** for any project and task, with the running entry's elapsed time shown
  live on the watch face
- **Project and task pickers**, plus a recents list for quickly restarting a previous entry
- **Tile** with a Play/Pause button and a **complication** so the running timer is visible
  without opening the app
- **Ongoing notification** while a timer runs, matching Wear OS's guidelines for
  long-running activities
- **Dynamic color** theming, following the system's Wear OS theme
- Translated into English, Finnish, Swedish, German, French, Russian, Japanese, Chinese
  (Simplified and Traditional), and Latin

## Known limitations

- **No OAuth.** Clockify has no OAuth flow this app can use without becoming an official
  Marketplace partner with a backend of its own, so signing in always means an API key rather
  than a typical account sign-in flow, whether entered on the watch or the phone companion app.
- **Phone companion app has no timer UI.** It exists solely to get an API key onto the watch
  more comfortably; starting/stopping timers and picking projects stays watch-only.

## Privacy

See [PRIVACY.md](PRIVACY.md): your API key stays on the watch, encrypted, and every request goes
straight to Clockify's own API. The phone companion app never talks to Clockify itself; it only
relays the key to the watch over the device-local Bluetooth Data Layer channel. Nothing is
collected by this app's developer.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup (via [`just`](https://just.systems/))
and guidelines, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations. Found a
security issue? See [SECURITY.md](SECURITY.md) instead of opening a public issue.

## Versioning

Version numbers follow [ZeroVer](https://0ver.org/): the major version stays at 0 indefinitely,
so a 0.y bump can carry breaking changes.

## License

[Apache License 2.0](LICENSE).
