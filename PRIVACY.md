# Privacy Policy

**Last updated: 2026-09-03**

ClockifyWearOS is a Wear OS client for [Clockify](https://clockify.me/). This page describes what
data the app handles and where it goes. There is no company or server behind this app beyond the
open-source code itself; it is a personal project.

## What the app stores

The app stores the following on your watch only, encrypted at rest using an Android
Keystore-backed key:

- Your Clockify API key
- Your Clockify user ID, workspace ID, and email address
- Your default project/task selection, if set
- A short-lived token used to avoid double-processing a Tile tap

This data never leaves your watch except as described below, is excluded from Android's
automatic backup, and is deleted immediately when you sign out or uninstall the app.

## What the app sends over the network

The app talks directly to Clockify's own API (`api.clockify.me`) using your API key, exactly as
if you were using Clockify's own website or official apps. Every request the app makes -
starting/stopping a timer, listing projects, checking who you are - goes straight to Clockify's
servers. The developer of this app never receives, sees, or stores any of it.

Clockify's own handling of your data is covered by
[CAKE.com's privacy policy](https://cake.com/privacy) (CAKE.com is Clockify's parent company),
not this one.

## The phone companion app

If you use the phone companion app to enter your API key instead of typing it on the watch, that
app sends the key to your paired watch over the Wearable Data Layer API - a device-local channel
over Bluetooth, with no cloud relay or server in between. The phone app itself never talks to
Clockify's API, stores nothing, and requests no internet permission; the watch does the actual
sign-in exactly as described above.

## What the app does not do

- No analytics, crash reporting, or advertising SDKs are included in the app.
- No account is created with, or data sent to, the developer of this app.
- No data is shared with any third party other than Clockify itself.

## Permissions

The watch app requests:

- **Internet access**, to talk to Clockify's API.
- **Notifications**, to show an ongoing notification while a timer is running, per Wear OS
  guidelines.

Neither permission is used for anything beyond that. The phone companion app requests no
permissions at all.

## Source code

This app is open source. You can read exactly what it does, including how it stores and encrypts
your API key, at <https://github.com/nikosavola/ClockifyWearOS>.

## Contact

Questions about this policy can be sent to nikomsavola@gmail.com, or opened as a
[GitHub issue](https://github.com/nikosavola/ClockifyWearOS/issues).
