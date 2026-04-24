# Play Store Release Setup

This repository now supports a Play Store oriented release path in [`.github/workflows/android-release.yml`](/.github/workflows/android-release.yml).

## What the workflow does

- builds a signed release Android App Bundle with `:app:bundleRelease`
- injects `RELEASE_VERSION_CODE` and `RELEASE_VERSION_NAME` into Gradle
- uploads the signed `.aab` as a GitHub Actions artifact
- optionally creates or updates a GitHub release for the same bundle
- optionally submits the bundle to Google Play using a service account

## Required GitHub Actions secrets

Add these repository secrets before running the workflow:

- `RELEASE_KEYSTORE_BASE64`: base64-encoded upload keystore file
- `RELEASE_STORE_PASSWORD`: upload keystore password
- `RELEASE_KEY_ALIAS`: upload key alias
- `RELEASE_KEY_PASSWORD`: upload key password
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: full JSON for a Google Play service account with release permissions

`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` is only required if `publish_to_play` is enabled.

For local signing, copy [`keystore.properties.example`](/keystore.properties.example) to `keystore.properties` and fill in the same keystore values.

## Local validation

To validate the same release path locally:

```powershell
$env:RELEASE_STORE_FILE="C:\path\to\upload-keystore.jks"
$env:RELEASE_STORE_PASSWORD="..."
$env:RELEASE_KEY_ALIAS="..."
$env:RELEASE_KEY_PASSWORD="..."
$env:RELEASE_VERSION_CODE="2"
$env:RELEASE_VERSION_NAME="0.2.0"
.\gradlew.bat :app:bundleRelease
```

The bundle will be written to `app/build/outputs/bundle/release/app-release.aab`.

You can also use the repo helper:

```powershell
$env:RELEASE_VERSION_CODE="2"
$env:RELEASE_VERSION_NAME="0.2.0"
.\build_release_bundle.bat
```

## Recommended first release process

1. Create the app in Google Play Console with package name `com.voiceledger.lite`.
2. Enroll in Play App Signing and keep the upload key used by this repo separate from the Play-managed app signing key.
3. Run the workflow with `publish_to_play = false` and confirm the signed AAB is produced.
4. Run the workflow again with `publish_to_play = true`, `play_track = internal`, and a new version code.
5. Verify installation and update behavior from the internal track before promoting to broader tracks.

## Versioning rules

- `release_version_code` must increase on every Play submission.
- `release_version_name` is the user-visible version string.
- Reusing a previous version code will cause Google Play to reject the upload.
