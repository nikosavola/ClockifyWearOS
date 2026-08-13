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
path). Setting all four is required for `assembleRelease`/`bundleRelease` to produce a signed
artifact; setting none of them leaves `release` unsigned (this is also what a local
`./gradlew :wear:assembleDebug` sees, without any of this configured - the build stays unaffected
for day-to-day development).

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
Action - `release.yml` passes the secret to `./gradlew :wear:publishReleaseBundle` via the
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
   either a `bundleRelease` AAB built locally, or the AAB attached to a `release.yml` run's GitHub
   Release. This satisfies the "at least one release already exists" requirement.

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

Signing in on-watch requires typing a ~48-character Clockify API key with the watch's own IME (see
`HANDOFF.md` section 8.3); a Play reviewer testing this app for the first time will hit that same
friction with no demo account provided. That's a submission-review risk, not something
`release.yml` or this doc's setup steps fix.
