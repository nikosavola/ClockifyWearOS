# Companion sign-in: manual QA checklist

The phone companion app (`mobile/`) talks to the watch over the real Google Play services
Wearable Data Layer API (`CapabilityClient`/`MessageClient`). That cross-device round trip can't
be automated in this repo's CI - see [docs/RELEASING.md](RELEASING.md) section 8 for why: Wear OS
device pairing is an interactive, Google-account-backed flow with no scriptable equivalent, so
neither a two-emulator rig nor a device farm can stand in for it hermetically. Everything else
about this flow (protocol encoding, sign-in logic, UI state) has unit/Compose test coverage
instead; this checklist covers only the parts that genuinely need two real, paired devices.

Run this before any release that touches `mobile/`, `wear/src/play/java/.../companion/`, or
`companion-protocol/`, and after upgrading `play-services-wearable`.

## Setup

You need a real phone and a real watch, already paired via the Wear OS companion app, both
reachable over `adb` (USB, or wireless debugging - see `just --list`'s `device` recipes for
`connect`/`pair`).

```bash
just install         # watch app
just install-mobile  # phone companion app
```

## Checklist

- [ ] **Fresh sign-in.** With the watch signed out, paste a real Clockify API key on the phone and
      tap "Sign in on watch". The phone finds the watch, shows "Signed in as
      &lt;email&gt;", and the watch's own Settings screen (revisit it if it was already open - see
      the note in `docs/RELEASING.md` section 8) shows the same account.
- [ ] **API key is visually masked.** While typing or right after pasting, the field shows dots/
      bullets, not the plaintext key - this is the one thing the automated Compose tests can't
      verify (semantics still expose the raw text for automation, but the rendered glyphs are what
      a real reviewer or user actually sees).
- [ ] **Already signed in.** With the watch already signed in, send another key from the phone.
      The phone shows the "already signed in, sign out on the watch first" message, and the
      watch's existing session is untouched.
- [ ] **Bad key.** With the watch signed out, send a deliberately invalid key. The phone shows the
      "rejected" message, and the watch stays signed out afterward.
- [ ] **Cold start.** Force-stop the watch app (`adb shell am force-stop fi.nikosavola.clockifywear`
      on the watch, or reboot it) so Play services has to start `ApiKeyMessageListenerService`
      fresh, then send a key from the phone. Same correct behavior as a warm start - this is the
      cold-start `settingsPrimed` race the code specifically guards against.
- [ ] **No watch found.** Turn off Bluetooth on the watch (or take it out of range) before sending.
      The phone shows "No watch found" within a few seconds, not a long hang.
- [ ] **Watch goes out of range mid-flow.** Send a key, then immediately disable Bluetooth on the
      watch. The phone eventually shows the timeout message; confirm the watch itself is in a sane
      state afterward (not left signed in with a half-processed key).
- [ ] **Tile/complication update.** After a successful companion sign-in, check the watch's tile
      and complication reflect the signed-in state without needing to open the app - see
      `ApiKeyMessageListenerService`'s `tileUpdater.refresh()` call.

## Recording the result

Note the date, the devices used (model + Android/Wear OS version), and the git commit/tag tested
in the PR or release notes - this checklist has no automated record of its own.
