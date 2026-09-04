# Releasing

How to cut a signed release of the Wear OS app and get it to the Play Store, and the one-time
setup that has to happen before any of it works. Read this fully before the first tag push;
several steps here cannot be done through `release.yml` and have to happen by hand in the Play
Console first.

## 1. One-time keystore generation

Generate a release upload keystore once, on a machine you control, and never commit it:

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` will prompt for a keystore password, a key password, and identity fields (name, org,
etc). Pick a real alias if you don't want `upload`; whatever you pick here is the value for
`RELEASE_KEY_ALIAS` below.

**Losing this keystore, or forgetting its passwords, means losing the ability to ship updates to
an already-published app**, unless you enable Play App Signing (see below) before that happens.
There is no recovery path for a lost upload key otherwise; treat the `.jks` file and both
passwords as seriously as you'd treat production database credentials. Keep an offline backup
somewhere durable, not just on the machine that generated it.

When you create the app in Play Console (section 5), accept the default of enrolling in **Play
App Signing**. Under that model Google holds the actual signing key that ends up on end-user
devices and re-signs your upload with it; the keystore you generate here is only an "upload key"
used to prove uploads come from you. If you ever lose it, Google has a documented process to
reset the upload key precisely because Play App Signing makes it replaceable. Without Play App
Signing (the old model), losing the keystore means losing the ability to update the app,
permanently. Play App Signing is Google's current default recommendation and there is no reason
to opt out here.

`.gitignore` already excludes `*.jks`, `*.keystore`, `key.properties`, and `keystore.properties`,
so none of this can be accidentally committed.

## 2. Encoding the keystore for CI

The `RELEASE_KEYSTORE_BASE64` secret (section 3) holds the keystore file itself, base64-encoded:

```bash
base64 -w0 upload-keystore.jks
```

Copy the single line of output; that's the exact value to paste into the GitHub secret.

## 3. GitHub repository secrets

Settings -> Secrets and variables -> Actions -> New repository secret. Add all four:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Output of the `base64 -w0` command above |
| `RELEASE_KEYSTORE_PASSWORD` | The keystore password from `keytool` |
| `RELEASE_KEY_ALIAS` | The `-alias` value from `keytool` (`upload` in the example above) |
| `RELEASE_KEY_PASSWORD` | The key password from `keytool` |

These are consumed by `.github/workflows/release.yml`'s build job and by `wear/build.gradle.kts`'s
signing config (as `RELEASE_KEYSTORE_PATH` + the other three env vars - the workflow decodes the
base64 secret to a temp file and exports that path itself, so there's no fifth secret for the
path). Setting all four is required for `assemblePlayRelease`/`bundlePlayRelease` to produce a
signed artifact (signing is wired to the `play` flavor only - see wear/build.gradle.kts); setting
none of them leaves `playRelease` unsigned (this is also what a local `./gradlew
:wear:assemblePlayDebug` sees, without any of this configured - the build stays unaffected for
day-to-day development).

**Local signed builds without exporting env vars**: create a gitignored `keystore.properties` in
the repo root with

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

`storeFile` must be an absolute path - it's read from inside `wear/build.gradle.kts`, which
resolves relative paths against `wear/`, not the repo root. `keystore.properties` takes priority
over the env vars per key, so either mechanism (or a mix) works.

## 4. Google Cloud service account for Play publishing

This is what backs the `PLAY_SERVICE_ACCOUNT_JSON` secret, used by the `publish-play-store` job.
Publishing itself is done by the [Gradle Play Publisher](https://github.com/Triple-T/gradle-play-publisher)
plugin (`com.github.triplet.play`, configured in `wear/build.gradle.kts`), not a separate GitHub
Action - `release.yml` passes the secret to `./gradlew :wear:publishPlayReleaseBundle` via the
`ANDROID_PUBLISHER_CREDENTIALS` env var, which the plugin reads directly. That's the CI path; the
`serviceAccountCredentials` property in the `play {}` block is deliberately left unset since that's
the local-dev, file-on-disk alternative, not the CI one.

Gradle Play Publisher doesn't sign anything - it only uploads an artifact that's already signed. It
requires the release `signingConfig` from section 1-3 above to already be in place; publishing an
unsigned build will fail on the Play side regardless of how the credentials are wired up.

1. In Google Cloud Console, pick or create a project, then enable the **Google Play Android
   Developer API** (API Library -> search for it -> Enable).
2. In that project, go to IAM & Admin -> Service Accounts -> Create Service Account. Give it a
   name; do **not** grant it any IAM roles in this step, none are needed here.
3. Open the new service account -> Keys -> Add Key -> Create new key -> JSON. This downloads a
   JSON key file - its full contents (not a path) are the value of the `PLAY_SERVICE_ACCOUNT_JSON`
   secret.
4. In Play Console (https://play.google.com/console), go to Users and permissions -> Invite new
   user, and paste the service account's email (visible on its Cloud Console page, looks like
   `name@project.iam.gserviceaccount.com`).
5. Grant it **app-level** permissions (not account-level), scoped to just this app:
   - **View app information (read-only)**
   - **Release apps to testing tracks**

   That pair is the minimum for what `release.yml` does (publish to the `internal` track). Do not
   grant "Release to production, exclude devices, and use Play App Signing" unless you later
   change the pipeline to target production directly - it doesn't need it for `tracks: internal`.

## 5. The manual bootstrap step (cannot be skipped, cannot be automated)

**The Google Play Developer API cannot create the very first release of a brand-new app.** It can
only manage subsequent releases of an app that already exists in Play Console with at least one
release already uploaded through the UI. This is a hard constraint on Google's side, not a gap in
`release.yml` - the `publish-play-store` job will fail (well, is designed to skip - see below -
until this is done, then fail if attempted anyway) against an app that doesn't exist yet.

Before the first tag push that's meant to reach the Play Store:

1. Create the app listing in Play Console (All apps -> Create app), fill in the required store
   listing fields - name, description, category, contact details, privacy policy, content rating
   questionnaire, target audience, data safety form. Screenshots already exist at
   `docs/screenshots/*.png`; use those for the listing's screenshot requirement.
2. Manually upload one release to the **internal testing** track through the Play Console UI -
   either a `bundlePlayRelease` AAB built locally (not the flavor-less `bundleRelease`, which
   would also build the GMS-free `fdroid` flavor - see wear/build.gradle.kts), or the AAB attached
   to a `release.yml` run's GitHub Release. This satisfies the "at least one release already
   exists" requirement.

Only after this has happened once does the `publish-play-store` job have an existing release to
attach subsequent uploads to.

Until `PLAY_SERVICE_ACCOUNT_JSON` is configured (section 4) - true by default before any of this
is done - `publish-play-store` skips itself rather than failing: it's gated on a `check-play-secret`
job that checks the secret's presence via a job output (secrets can't be referenced directly in an
`if:`), so the release/build side of the pipeline (GitHub Release with the APK/AAB attached) still
works standalone before Play publishing is wired up at all.

## 6. Cutting a release

Once sections 1-5 are done once, each subsequent release is:

1. Bump `versionCode` (must be strictly higher than the last version on any track it's released
   to) and `versionName` in `wear/build.gradle.kts`.
2. Commit that change.
3. Tag it `vX.Y.Z` (matching the `push: tags: 'v*'` trigger) and push the tag:

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

4. `release.yml` runs: builds the signed APK/AAB, creates a GitHub Release for the tag with both
   attached, and (once section 4/5 are done) publishes the AAB to the Play Store `internal` track.
   Promote internal -> production from the Play Console UI once you're confident in a release;
   nothing here does that automatically.

## 7. Known submission risk this pipeline does not address

Signing in on-watch requires typing a ~48-character Clockify API key with the watch's own IME.
The phone companion app (`mobile/`, see below) mitigates this for real users, but a Play reviewer
testing the watch app in isolation - without also installing and pairing the phone app - will
still hit that same friction with no demo account provided. That's a submission-review risk, not
something `release.yml` or this doc's setup steps fix.

## 8. The phone companion app must ship under the *same* Play listing as the watch app

`mobile/` is a second Gradle module and a second APK, but **not** a second, independently
publishable app: Google Play services requires the phone and watch apps that talk to each other
over the Wearable Data Layer API (`CapabilityClient`/`MessageClient`, used for the companion
sign-in flow) to share both the exact same package name and the exact same signing certificate -
see the "Wearable Data Layer API Security Requirements" note in
[Access the wearable data layer](https://developer.android.com/training/wearables/data/overview).
That's why `mobile/build.gradle.kts`'s `applicationId` is `fi.nikosavola.clockifywear`, identical
to `wear/build.gradle.kts`'s - not a coincidence, and not safe to change independently in either
module.

Practically, this means:

- **One Play Console app listing**, not two. Play distributes the correct APK to each device type
  from a single listing when the wear module declares
  `<uses-feature android:name="android.hardware.type.watch" />` (`wear/src/main/AndroidManifest.xml`
  already does), so a phone gets `mobile`'s APK and a watch gets `wear`'s. There is no "associate a
  Wear OS app to a phone app's listing" step for two *separate* listings, because that isn't the
  shape this ships as.
- **One signing identity for both APKs.** Whatever signing config section 1 sets up for `:wear`
  must also sign `:mobile`'s release build - `mobile/build.gradle.kts` has no signing config of its
  own yet (see below), and when one is added it needs to produce output signed with the same
  certificate as `:wear`'s, not an independent one.
- Sections 1-6 above, written before the `mobile/` module existed, still describe `:wear`'s own
  signing/publishing pipeline accurately; extending `release.yml` to also build and publish
  `:mobile`'s APK under the same listing is a deliberately deferred follow-up, not done yet. Until
  then, `mobile/` is development-only: `./gradlew :mobile:assembleDebug`, or `just install-mobile`
  with a device connected. Locally both modules' `assembleDebug` output happens to already share
  the same Android debug keystore, so the signing requirement above doesn't surface as a local
  development problem - only once real release signing exists for `:mobile` does it matter that it
  isn't accidentally given its own independent identity.

**Verified end to end on real hardware** (a paired Pixel phone and Galaxy Watch): `CapabilityClient`
discovery found the watch, a real `MessageClient` request/reply round trip completed, the
already-signed-in guard correctly refused a second sign-in attempt without disturbing the existing
session, a forced cold start of the watch's listener service still worked, and a real sign-in with
a real Clockify API key succeeded end to end. What that verification does **not** cover is release
signing: it was done with both modules' `assembleDebug` output sharing the same Android debug
keystore (see above), so re-run this same manual pairing check once real release signing exists for
`:mobile` and confirm it still works signed with `:wear`'s certificate, not before assuming the
signed release build behaves the same way.

This can't be automated in CI - Wear OS device pairing is an interactive, Google-account-backed
flow with no scriptable ADB/CLI equivalent, so neither a two-emulator rig nor a device farm can
stand in for it hermetically. See [docs/COMPANION_QA.md](COMPANION_QA.md) for the checklist to
re-run before any release that touches the companion sign-in flow.

## 9. Submitting the `fdroid` flavor to F-Droid

`:wear`'s `fdroid` product flavor (see `wear/build.gradle.kts`) has zero Google Play Services in
its dependency tree and a distinct `applicationId` (`fi.nikosavola.clockifywear.fdroid`), making it
policy-eligible for F-Droid's official repo. `mobile/` and the `play` flavor are never submitted -
F-Droid only ever builds one variant per metadata entry, and `mobile`'s entire purpose is GMS
sync, so there's nothing GMS-free to offer there.

**Submission is a merge request against a separate GitLab-hosted repo, not something that happens
in this GitHub repo.** F-Droid's build recipes live in `gitlab.com/fdroid/fdroiddata`, one YAML
file per app at `metadata/<applicationId>.yml`. There's no lighter "point F-Droid at this GitHub
repo" alternative for the actual build recipe - only store-listing text/screenshots can optionally
live closer to the app's own repo. Submitting requires a GitLab account and a fork of
`fdroiddata`, both outside this repo's tooling.

**Blocker as of this writing: no tagged release contains the `fdroid` flavor yet.** F-Droid builds
from a specific git tag (the recipe's `commit:` field, see below), and the current latest tag
(`v0.1.1`) predates the `play`/`fdroid` flavor split - building `fdroid` at that tag would fail
outright.
Cutting a new release (section 6 above) is a prerequisite, not optional - and note that
`release.yml`'s `push: tags: v*` trigger always builds and creates a GitHub Release from that tag,
plus publishes the `play` flavor to the Play Store's internal track *if* `PLAY_SERVICE_ACCOUNT_JSON`
is configured (section 4/5 - `publish-play-store` skips gracefully otherwise). Assume that secret
is set in this repo unless you've deliberately unset it, and time the tag deliberately either way,
not as an F-Droid-only side effect.

**The non-free-code scanner cannot see this repo's flavor-scoped dependency syntax at all -
confirmed with a real control-line test, not just reasoned about from the scanner source.**
`scanner.py` matches dependency-declaration lines with a regex anchored at the start of the line
(after whitespace); `wear/build.gradle.kts`'s `"playImplementation"(...)`/`"fdroidImplementation"(...)`
Kotlin string-invoke syntax (used because a typed accessor doesn't exist for a newly introduced
flavor - see that file's own comment) starts with a `"`, which the anchored regex can never match.
Proved this by adding a throwaway unquoted `implementation(libs.play.services.wearable)` line next
to the real quoted one and re-scanning: the scanner caught the unquoted control line and missed
the quoted one right beside it, in the same file, same scan. So the scanner reporting zero
problems for `wear/build.gradle.kts` reflects "this syntax is invisible to it", not "flavor-based
exclusion correctly ran" - those would look identical from the scanner's output alone, and only
the control-line test tells them apart.

Practical upshot: this check cannot verify that flavor-based GMS exclusion keeps working as
`wear/build.gradle.kts` changes - a real GMS-scanning APK build on F-Droid's own infrastructure is
the actual backstop for that. It still reliably catches a genuinely unconditional, unquoted
non-free dependency added anywhere, which is exactly what makes `mobile/`'s `scandelete` entry
necessary (that module's GMS dependencies are ordinary unquoted `implementation(...)` calls, not
flavor-gated at all, so the scanner sees and flags them just fine).

**The actual recipe is a real, CI-tested file, not a hand-copied block in this doc - added in
PR #13 (a separate, not-yet-merged PR; merge it alongside or before this one).** It lives at
`metadata/fi.nikosavola.clockifywear.fdroid.yml`, with `.github/workflows/fdroid.yml` running
`fdroid lint` + `fdroid scanner` against it on every push/PR (informationally - see that
workflow's own comments). Keeping the recipe in one real, checked place instead of also
duplicating it here as prose stops the two from drifting apart - this doc previously carried its
own inline draft that separately went out of sync more than once (an invalid `Categories: [Time]`
value, a `versionCode: TODO` placeholder that `fdroid lint` actually rejects since that field must
be an integer, not a string).

That tracked file's `commit: __CI_COMMIT_SHA__` and current version fields are CI-testing
placeholders (see its own header comment) - it tracks this repo's current unreleased state for
continuous checking, not the literal content to paste into the real submission. Building the
actual `fdroiddata` MR means copying its content with those placeholders replaced by the real
release tag's values, per the checklist below.

Notes on fields that aren't self-explanatory:

- `UpdateCheckMode: Tags` + `AutoUpdateMode: Version`: F-Droid's own tooling scans tagged
  revisions for the highest `versionCode` and proposes new `Builds:` entries automatically on
  future releases - no need to hand-edit the metadata for every subsequent tag once the first
  entry exists.
- `novcheck` is deliberately absent: F-Droid verifies the built APK's actual `versionCode`/
  `versionName` against what the metadata declares by default, which is the right default here.
- Signing is fully independent of this project's own Play upload keystore - F-Droid builds from
  source on its own infrastructure and signs with its own key. No conflict with the `play` flavor's
  Play Store signing, and none possible: `fdroid`'s distinct `applicationId` was never Play-signed.

**Timeline**: F-Droid's own docs cite 24-48 hours from a merged metadata change to appearing in
the repo - but getting a new-app merge request *reviewed and merged* in the first place has a much
less predictable queue (recent community reports range from about a month to several months).
Budget for that, not the post-merge number.

**Test locally with the real commit before opening the MR, not after.** With `Repo:` pointed at
the real GitHub URL (as the tracked file has it), `fdroid build`/`fdroid scanner` fetch that
remote before checking out `commit:` - so a prospective, not-yet-pushed commit's SHA can't
resolve there at all. `.github/workflows/fdroid.yml` sidesteps this for its own continuous
checking by substituting `Repo:` to the local checkout path at run time; do the same for a manual
local preflight against a commit that isn't pushed yet, or just push the commit first and use the
real URL once it's reachable.

Concrete remaining steps, in order - test before tagging, not after, since cutting the tag can
also publish a real Play release (see above):

1. Bump `versionName`/`versionCode` in `wear/build.gradle.kts` (section 6) and commit, but don't
   tag yet.
2. Update `metadata/fi.nikosavola.clockifywear.fdroid.yml`'s `Builds[0].versionName`/`versionCode`
   to match, and run `fdroid build`/`fdroid lint` locally against that commit (see the note above
   on `Repo:` if it isn't pushed yet) - this is the actual preflight, and it needs to pass before
   anything below happens.
3. Only once that passes: tag the commit (section 6) and push the tag. Update the metadata file's
   `commit:` from its CI-testing placeholder to that real tag, and `CurrentVersion`/
   `CurrentVersionCode` to match.
4. Fork `gitlab.com/fdroid/fdroiddata`, add `metadata/fi.nikosavola.clockifywear.fdroid.yml` with
   that verified content, and open the merge request.
