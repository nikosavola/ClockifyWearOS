# justfile for ClockifyWearOS. Run `just` or `just --list` to see all recipes.
#
# Every gradle recipe caps parallelism via max_workers (default 2, override with
# JUST_MAX_WORKERS): a workstation preference, not a project requirement, so it lives here rather
# than in gradle.properties, which is committed and shared across machines.

max_workers := env('JUST_MAX_WORKERS', '2')
sdk := env('ANDROID_HOME', env('ANDROID_SDK_ROOT', env('HOME') + '/Android/Sdk'))
# The "play" flavor: full-featured, has the phone companion pairing feature - what a developer
# wants installed day to day. See wear/build.gradle.kts for the "play"/"fdroid" flavor split.
apk := 'wear/build/outputs/apk/play/debug/wear-play-debug.apk'
mobile_apk := 'mobile/build/outputs/apk/debug/mobile-debug.apk'
gradle := './gradlew --max-workers=' + max_workers
package := 'fi.nikosavola.clockifywear'
# Deliberately the same as `package`, not a typo: the phone and watch apps must share one
# applicationId for the Wearable Data Layer API to let them find and message each other (see
# mobile/build.gradle.kts's comment) - they only ever coexist on different physical devices
# (phone vs watch), so the shared id never collides. Kept as its own variable so the mobile-*
# recipes below read the same as the watch ones despite resolving to the same package string.
mobile_package := package

# List available recipes
default:
    @just --list

# Run ktfmt and ktlint auto-format over the Kotlin sources (see landmines 3-4 before touching this)
[group('lint')]
format:
    {{ gradle }} formatAll

# Run ktfmt, ktlint and detekt checks, matching the CI "verify" job
[group('lint')]
lint:
    {{ gradle }} lintAll

# Install prek hooks so format/lint run on every commit
[group('lint')]
install-pre:
    prek install

# Run all pre-commit hooks against every tracked file
[group('lint')]
precommit:
    prek run --all-files

# Build the debug APK (watch app, "play" flavor)
[group('build')]
assemble:
    {{ gradle }} :wear:assemblePlayDebug

# Build the debug APK (phone companion app)
[group('build')]
assemble-mobile:
    {{ gradle }} :mobile:assembleDebug

# Remove build outputs
[group('build')]
clean:
    {{ gradle }} clean

# Run the host-JVM unit tests (watch app, "play" flavor - run `just verify` for both flavors)
[group('test')]
test:
    {{ gradle }} :wear:testPlayDebugUnitTest

# Run the host-JVM unit tests (phone companion app)
[group('test')]
test-mobile:
    {{ gradle }} :mobile:testDebugUnitTest

# Run the host-JVM unit tests (shared companion-protocol module)
[group('test')]
test-protocol:
    {{ gradle }} :companion-protocol:test

# Full local gate: lint, build and test all modules (both :wear flavors), matching CI exactly
[group('test')]
verify:
    {{ gradle }} lintAll :wear:assemblePlayDebug :wear:testPlayDebugUnitTest \
        :wear:assembleFdroidDebug :wear:testFdroidDebugUnitTest \
        :mobile:assembleDebug :mobile:testDebugUnitTest :companion-protocol:test --no-daemon

# List connected adb devices, including wireless ones
[group('device')]
devices:
    {{ sdk }}/platform-tools/adb devices -l

# Connect to a previously paired watch found via mDNS (run `just pair` first if this fails)
[group('device')]
connect:
    #!/usr/bin/env bash
    set -euo pipefail
    ADB="{{ sdk }}/platform-tools/adb"
    "$ADB" start-server
    target=$("$ADB" mdns services | grep '_adb-tls-connect._tcp' | awk '{print $3}')
    if [ -z "$target" ]; then
      echo "No paired watch found via mDNS. Enable Wireless debugging on the watch, then run: just pair <ip:port> <code>" >&2
      exit 1
    fi
    "$ADB" connect "$target"

# Pair a watch: get ip:port and code from its Wireless debugging > Pair new device screen
[group('device')]
pair ip_port code:
    {{ sdk }}/platform-tools/adb pair {{ ip_port }} {{ code }}

# Build and install the debug APK on the connected device (watch app)
[group('device')]
install: assemble
    {{ sdk }}/platform-tools/adb install -r {{ apk }}

# Build and install the debug APK on the connected device (phone companion app)
[group('device')]
install-mobile: assemble-mobile
    {{ sdk }}/platform-tools/adb install -r {{ mobile_apk }}

# Launch the watch app on the connected device
[group('device')]
launch:
    {{ sdk }}/platform-tools/adb shell am start -n {{ package }}/.ui.MainActivity

# Launch the phone companion app on the connected device
[group('device')]
launch-mobile:
    {{ sdk }}/platform-tools/adb shell am start -n {{ mobile_package }}/fi.nikosavola.clockifywear.mobile.MainActivity

# Stream logcat filtered to this app's process, crashes and stderr
[group('device')]
logcat:
    {{ sdk }}/platform-tools/adb logcat -s AndroidRuntime System.err {{ package }}

# Remove the app from the connected device (watch app)
[group('device')]
uninstall:
    {{ sdk }}/platform-tools/adb uninstall {{ package }}

# Remove the app from the connected device (phone companion app)
[group('device')]
uninstall-mobile:
    {{ sdk }}/platform-tools/adb uninstall {{ mobile_package }}

# Boot a Wear OS emulator; plain `-avd` alone segfaults on GPU/display init here, hence the flags
[group('emulator')]
emulator avd='wear5':
    {{ sdk }}/emulator/emulator -avd {{ avd }} -no-window -no-audio -no-boot-anim \
        -gpu swiftshader_indirect -no-snapshot

# List available AVDs
[group('emulator')]
avds:
    {{ sdk }}/emulator/emulator -list-avds
