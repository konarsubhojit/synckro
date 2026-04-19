# Synckro

An Android app that syncs a folder on the phone's internal storage or SD card with
a folder on **OneDrive** or **Google Drive**.

> Status: **early scaffolding**. The project structure, core abstractions, and a
> runnable Compose shell are in place. Cloud provider implementations
> (OneDrive / Google Drive) are currently stubs that throw
> `NotImplementedError`, and the real sync engine is intentionally minimal —
> its file-diff logic is pure Kotlin and unit-tested, and everything plugs into
> a `FakeCloudProvider` so that the pipeline can be developed end-to-end without
> any network access.

## Features (planned)

- Pick a local folder (internal or SD card) via the Storage Access Framework.
- Link it to a folder on OneDrive or Google Drive.
- Bidirectional sync with configurable conflict policy (newest-wins / keep-both /
  prefer-local / prefer-remote).
- Periodic background sync (WorkManager) with constraints (Wi-Fi only,
  charging-only, etc.).
- Manual "Sync now".
- Conflict inbox, per-pair logs, retries with exponential backoff.

## Tech stack

- **Language / build**: Kotlin, Gradle (Kotlin DSL), version catalog
- **Min / target SDK**: 26 / 34
- **UI**: Jetpack Compose, Material 3
- **Architecture**: MVVM + Clean Architecture (`ui` / `domain` / `data`)
- **DI**: Hilt
- **Async**: Coroutines + Flow
- **Persistence**: Room
- **Background**: WorkManager + foreground service for long transfers
- **Networking**: OkHttp + Retrofit + kotlinx.serialization
- **Auth**:
  - OneDrive → MSAL (Microsoft Authentication Library)
  - Google Drive → Credential Manager + Google Identity Services + Drive REST v3
- **Local file access**: Storage Access Framework (`DocumentFile` /
  `DocumentsContract`) — no `MANAGE_EXTERNAL_STORAGE`

## Module / package layout

Currently a single-module project (`:app`) with a clean package layout so it can
be split into Gradle modules later without code churn:

```text
app/src/main/java/com/konarsubhojit/synckro/
├── SynckroApp.kt                       # Hilt Application
├── MainActivity.kt                     # Compose entry point
├── ui/                                 # Compose screens, theme, navigation
├── domain/
│   ├── model/                          # SyncPair, FileIndexEntry, enums
│   ├── provider/CloudProvider.kt       # Provider interface + models
│   └── sync/SyncEngine.kt              # Pure diff/apply logic
├── data/
│   ├── local/                          # Room entities, DAOs, database
│   └── worker/SyncWorker.kt            # WorkManager worker + scheduler
├── providers/
│   ├── fake/FakeCloudProvider.kt       # In-memory provider for tests / dev
│   ├── onedrive/OneDriveProvider.kt    # Stub
│   └── gdrive/GoogleDriveProvider.kt   # Stub
└── di/                                 # Hilt modules
```

## Building

Requires JDK 17 and the Android SDK (command-line tools or Android Studio).
Copy `local.properties.example` to `local.properties` and set `sdk.dir` to your
Android SDK location.
The Gradle wrapper is committed in `gradlew`, `gradlew.bat`, and
`gradle/wrapper/gradle-wrapper.jar`, so you can build immediately with
`./gradlew` (or `gradlew.bat` on Windows).

### Common tasks

```bash
./gradlew assembleDebug        # build APK
./gradlew testDebugUnitTest    # run unit tests
./gradlew lintDebug            # Android lint
```

## CI / CD

GitHub Actions builds the debug APK on every push, on pull requests, and on
manual dispatch. Each run uploads the generated APK from
`app/build/outputs/apk/debug/` as an Actions artifact named
`synckro-debug-apk-<run_number>`.

## Roadmap

Near-term work:

1. Wire up SAF folder picker and persist tree URIs.
2. Implement real local-folder enumeration against a `DocumentFile` tree and
   populate Room index.
3. Implement OneDrive provider (MSAL auth + Graph `/me/drive/root:/.../:/delta`
   + resumable upload sessions).
4. Implement Google Drive provider (OAuth + Drive v3 `changes.list` + resumable
   upload).
5. Conflict inbox UI and per-pair settings.

## License

See [LICENSE](./LICENSE).
