# Ledger Lite

Ledger Lite is a local-first Android journal for quick note capture, incremental rollups, and semantic search that can run entirely on the phone.

## What it does

- saves notes locally with a title, body, created timestamp, and updated timestamp
- lets you manage reusable labels and attach any number of them to a note
- keeps everything on-device in a Room database
- builds daily, weekly, monthly, and yearly rollups from the last dirty checkpoint forward
- keeps a local semantic index for notes and rollups
- routes search from years to months to weeks to days before narrowing to raw notes
- auto-installs the summary and embedding models into app storage from the app release when those assets are published
- falls back to built-in local heuristics when model files are not present

## Free and Open Source

Ledger Lite is **free software and freeware** — it costs nothing to download, use, or share.
The Android app is distributed at no charge and will remain free.

- **License**: MIT — permissive, no royalties, no restrictions on personal or commercial use
- **Source code**: available in full in this repository
- **Third-party attributions**: see [`NOTICE`](/NOTICE) for all library and model licenses
- Contributor guidance: see [`CONTRIBUTING.md`](/CONTRIBUTING.md)
- Security reporting: see [`SECURITY.md`](/SECURITY.md)
- Community expectations: see [`CODE_OF_CONDUCT.md`](/CODE_OF_CONDUCT.md)

### License summary

The MIT License (see [`LICENSE`](/LICENSE)) applies to all original source code in this
repository. You are free to use, copy, modify, merge, publish, distribute, sublicense,
and sell copies of the software.

The app bundles no proprietary runtime code. All Android library dependencies use the
Apache License 2.0 or MIT License. The two on-device AI models (Gemma 4 E2B and the
TensorFlow text embedder) are downloaded at first launch under their respective free-use
terms (Gemma Terms of Use and Apache 2.0) and are not redistributed with the app package.

## Project Layout

- `app/src/main/java/com/voiceledger/lite/data`: Room entities, DAO, repositories, and local settings
- `app/src/main/java/com/voiceledger/lite/semantic`: local aggregation, embedding, background work, and model provisioning
- `app/src/main/java/com/voiceledger/lite/ui`: Compose app shell and view model

## How Summarization Works

Summarization runs as a four-level pipeline: **daily → weekly → monthly → yearly**. Each level reads the output of the level below it as its source documents.

### Rollup pipeline

`LocalAggregationCoordinator.runAggregation()` drives the full pipeline:

1. Notes newer than the last dirty checkpoint are re-embedded and written to the semantic index.
2. The pipeline iterates through the four granularities in order. Daily rollups are generated from raw notes; each subsequent level (weekly, monthly, yearly) reads the rollups produced by the previous level.
3. After all rollups are generated, every rollup is embedded and added to the semantic index so they are available to search.

A **checkpoint** is stored per granularity. If a note is edited or a new note arrives after the last run, only the periods that intersect the dirty window are regenerated, not the full history.

### Summarization model and prompts

Summarization runs entirely on-device using a LiteRT-LM session (Gemma 4 E2B, 2048-token context). The model is resolved by `LocalModelLocator` and opened once per run with bounded parameters (max 512–2048 output tokens, topK capped at 8).

For each period, `LocalSummaryEngine` builds a prompt in two parts:

- **System instructions** — daily periods request 3–5 bullet points covering the day's notes; weekly/monthly/yearly periods request 4–6 bullet points focused on key themes and recurring patterns. Both variants instruct the model to rewrite in its own words and skip filler, greetings, and repetition.
- **Source block** — each source document is formatted as `[ISO timestamp] Title\nBody`. A total body budget of ~4 000 characters is distributed evenly across documents, with per-document limits ranging from 180 to 1 500 characters depending on how many sources are present.

The 4 000-character body budget leaves roughly 1 000 characters for the instruction text, titles, and timestamps, keeping the full prompt under ~1 572 input tokens — safely within the 1 772 tokens available after reserving 256 for output and ~20 for special tokens.

### Output normalization

The raw model response is normalized before storage: NFKC Unicode normalization, `\r\n` → `\n`, control-character removal, and collapsing runs of three or more blank lines to two. A blank result after normalization is treated as a failure.

Each completed rollup is stored as an `AggregateInsight` containing the model label, an auto-generated period title ("Daily summary: Nov 15, 2024"), and the normalized overview text.

## How Ask-a-Question Search Works

When a user submits a query in the Insights search bar, `LedgerViewModel.runSearch()` first classifies the query, then routes it to one of two search strategies.

### Step 1 — Query classification

`SearchStrategyRouter.classify()` sends the query to the local summary model with a short decision prompt. The model chooses between two strategies:

| Strategy | When it applies |
|---|---|
| **S (Semantic / topical)** | The answer lives in a handful of thematically related notes — one clear topic, readable by scanning a few entries. |
| **B (Broad scan)** | The answer could appear in notes on unrelated topics, or the query asks for an extreme value, aggregate, or uses qualifiers like "ever", "any time", or "how many times". |

The model is run with temperature 0 and topK 1 for a deterministic single-letter answer. Anything other than `S` routes to broad scan.

### Step 2a — Semantic search with adaptive hierarchy

For topical queries, `LocalAggregationCoordinator.search()` walks the rollup pyramid from the top down.

#### The four-level pyramid

The search loads all rollup embeddings, generates a query embedding, and computes cosine similarity at each level:

1. **Yearly** — picks the top 2 yearly rollups by similarity.
2. **Monthly** — picks the top 3 monthly rollups, filtered to the time ranges of the top yearly results (if the yearly stage passed an evidence gate check — see below).
3. **Weekly** — picks the top 4 weekly rollups, filtered to the time ranges of the top monthly results.
4. **Daily** — picks the top 5 daily rollups, filtered to the time ranges of the top weekly results.

Notes are then fetched from the union of all rollup windows, re-scored by their own embeddings, and the top 8 results are returned.

#### Adaptive evidence gates (the skip-level mechanism)

Before each lower level applies its parent's filter, `StageEvidenceGate` asks the model whether the parent-level results actually discuss the query's subject. The prompt shows the parent summaries and asks for a single Y/N answer:

- **Y (sufficient)** — the parent stage's rollups mention the topic clearly; apply the parent's time-range filter to the next level.
- **N (insufficient)** — the parent summaries cover other topics or only touch the subject in passing; **drop the filter entirely** and re-score the full set at the next level without constraint.

This means the pyramid can skip a level's narrowing effect if that level's summaries happen not to mention the subject — for example, if a topic only appears in one month's notes and the yearly summary didn't surface it. The gate falls back to `true` (keep filtering) if model inference fails, preserving the pre-existing behavior.

If the finest populated stage fails its own evidence gate at the end, the search returns an empty result set and sets `suggestBroadScan = true`, prompting the user to try a broad scan instead.

#### Answer generation

The top-ranked notes and rollups are passed to `LocalAnswerEngine.answer()`, which formats them with timestamps and titles and asks the model to answer the question using only those sources. The model is instructed to say directly if the sources are insufficient.

### Step 2b — Broad scan

For aggregate or far-ranging queries, `LocalAggregationCoordinator.searchBroadScan()` scores every note embedding against the query without any hierarchical filtering. It oversamples by 4× the result limit (default 32 documents) to get good candidates before applying an optional date-range filter that the user can supply via the UI confirmation dialog. The final answer is generated the same way as semantic search.

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
- Windows release bundle: `set RELEASE_VERSION_CODE=2 && set RELEASE_VERSION_NAME=0.2.0 && .\build_release_bundle.bat`

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Optional local release signing:

- Create a repo-local `keystore.properties` file or set environment variables with `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`.
- Start from [`keystore.properties.example`](/keystore.properties.example) for local setup.
- `RELEASE_STORE_FILE` can be an absolute path or a path relative to the repo root.
- Without those values, `:app:assembleRelease` still builds, but the APK is not signed for device installation.
- Release builds also accept `RELEASE_VERSION_CODE` and `RELEASE_VERSION_NAME`, which is the path used by CI for store updates.

Important settings:

- `Labels`: reusable note tags managed in Settings and used as optional search filters
- `Summarize since`: optional `YYYY-MM-DD` floor for rollup backfill
- Background processing runs daily when charging if enabled

## Phone Install Path

1. Build the app or download the latest APK from GitHub Releases.
2. Install the APK on your phone.
3. Open Ledger Lite.
4. Add a few notes in `Compose`.
5. Optional: open `Settings` and create a few labels such as `Investing`, `Ideas`, or `Work`.
6. Open `Insights` and tap `Run now` to build local rollups and the local semantic index.
7. Search from `Insights`, optionally filtering by one or more labels.
8. Open `Summarize` and confirm the model status shows both local models as installed.

No PC or server connection is required for the app's note storage, rollups, or semantic search.

## Distribution Paths

The repo includes:

- `Build Android APK` at [`.github/workflows/android-apk.yml`](/.github/workflows/android-apk.yml)
- `Build And Publish Android Release` at [`.github/workflows/android-release.yml`](/.github/workflows/android-release.yml)
- `Deploy Download Page` at [`.github/workflows/pages.yml`](/.github/workflows/pages.yml)
- a static Pages site in [`docs/index.html`](/docs/index.html)

The intended side-load path is:

1. Run the `Build Android APK` workflow from GitHub.
2. Let it create or update a release with `ledger-lite-debug.apk` plus the model assets expected by the app.
3. Open the GitHub Pages download page and install the APK from there.

This is currently a debug APK for fast testing. A signed release build would need a keystore plus GitHub Actions secrets.

The intended Play Store path is:

1. Configure the repository secrets described in [`docs/play-store-release.md`](/docs/play-store-release.md).
2. Run `Build And Publish Android Release`.
3. Provide a new `release_version_code` and `release_version_name`.
4. Start with `publish_to_play = false` to validate the signed AAB.
5. When ready, rerun with `publish_to_play = true` and target the `internal` track first.

## Intended First Test

1. Create a few short notes in the `Compose` tab.
2. Open `Insights` and tap `Run now`.
3. Review the daily, weekly, monthly, and yearly rollups.
4. Create a few labels and attach them to notes.
5. Enter a query in semantic search and confirm the route and local results appear.
