# Voice Ledger Lite

Voice Ledger Lite is a local-first Android journal for quick note capture, incremental rollups, and semantic search that can run entirely on the phone.

## What it does

- saves notes locally with a title, body, created timestamp, and updated timestamp
- lets you manage reusable labels and attach any number of them to a note
- keeps everything on-device in a Room database
- builds daily, weekly, monthly, and yearly rollups from the last dirty checkpoint forward
- keeps a local semantic index for notes and rollups
- routes search from years to months to weeks to days before narrowing to raw notes
- supports importing an on-device summary model and an on-device embedding model into app storage
- falls back to built-in local heuristics when model files are not present

## Open Source

- License: MIT
- Contributor guidance: see [`CONTRIBUTING.md`](/CONTRIBUTING.md)
- Security reporting: see [`SECURITY.md`](/SECURITY.md)
- Community expectations: see [`CODE_OF_CONDUCT.md`](/CODE_OF_CONDUCT.md)

## Project Layout

- `app/src/main/java/com/voiceledger/lite/data`: Room entities, DAO, repositories, and local settings
- `app/src/main/java/com/voiceledger/lite/semantic`: local aggregation, embedding, background work, and model import
- `app/src/main/java/com/voiceledger/lite/ui`: Compose app shell and view model

## Build And Run

The repo now includes the Gradle wrapper, so command-line builds use the checked-in `gradlew` scripts instead of a separately installed Gradle.

Minimum machine setup:

- JDK 17
- Android SDK Platform 35
- Android Build-Tools 35.0.0
- Either Android Studio or a terminal with `JAVA_HOME` and `ANDROID_SDK_ROOT` configured

Common commands:

- Windows debug build: `.\gradlew.bat :app:assembleDebug`
- macOS/Linux debug build: `./gradlew :app:assembleDebug`
- Install to a connected Android device: `.\gradlew.bat :app:installDebug`

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Important settings:

- `Labels`: reusable note tags managed in Settings and used as optional search filters
- `Summary model path`: optional on-device `.task` bundle for local text generation
- `Embedding model path`: optional on-device text embedding model for local vector search
- `Summarize since`: optional `YYYY-MM-DD` floor for rollup backfill
- Background processing runs daily when charging if enabled

## Phone Install Path

1. Build the app or download the latest APK from GitHub Releases.
2. Install the APK on your phone.
3. Open Voice Ledger Lite.
4. Add a few notes in `Compose`.
5. Optional: open `Settings` and create a few labels such as `Investing`, `Ideas`, or `Work`.
6. Open `Insights` and tap `Run now` to build local rollups and the local semantic index.
7. Search from `Insights`, optionally filtering by one or more labels.
8. Optional: import a summary `.task` model plus an embedding model if you want model-backed on-device generation instead of the built-in fallback.

No PC or server connection is required for the app's note storage, rollups, or semantic search.

## GitHub Distribution

The repo includes:

- `Build Android APK` at [`.github/workflows/android-apk.yml`](/.github/workflows/android-apk.yml)
- `Deploy Download Page` at [`.github/workflows/pages.yml`](/.github/workflows/pages.yml)
- a static Pages site in [`docs/index.html`](/docs/index.html)

The intended path is:

1. Run the `Build Android APK` workflow from GitHub.
2. Let it create or update a release with `voice-ledger-lite-debug.apk`.
3. Open the GitHub Pages download page and install the APK from there.

This is currently a debug APK for fast testing. A signed release build would need a keystore plus GitHub Actions secrets.

## Intended First Test

1. Create a few short notes in the `Compose` tab.
2. Open `Insights` and tap `Run now`.
3. Review the daily, weekly, monthly, and yearly rollups.
4. Create a few labels and attach them to notes.
5. Enter a query in semantic search and confirm the route and local results appear.
