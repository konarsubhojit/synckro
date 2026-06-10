# Synckro

An Android app that syncs a folder on the phone's internal storage or SD card with
a folder on **OneDrive** or **Google Drive**.

> Status: **actively developed and functional**. Both cloud providers (Google Drive
> and OneDrive) are fully implemented with OAuth 2.0 / MSAL authentication,
> incremental change enumeration, resumable uploads, multi-account support, and
> transparent token-refresh retry. The sync engine performs bidirectional
> file-diff/apply with configurable conflict policies and runs as a
> WorkManager foreground service with exponential backoff.

## Features

- Pick a local folder (internal or SD card) via the Storage Access Framework.
  See **[docs/sync-pairs.md](docs/sync-pairs.md)** for a full walkthrough.
- Link it to a folder on OneDrive or Google Drive.
- Connect multiple Google Drive or OneDrive accounts and bind each sync pair to a
  specific account.
- Bidirectional sync with configurable conflict policy (newest-wins / keep-both /
  prefer-local / prefer-remote).
- Upload-only backup and download-only offload modes with optional automatic
  retention-based deletion.
- Periodic background sync (WorkManager) with per-pair schedule presets
  (15 min / 30 min / 1 h / 24 h / custom interval) and constraints
  (Wi-Fi only, charging-only). See **[docs/scheduling.md](docs/scheduling.md)**.
- Manual "Sync now" from the Home screen or Pair Detail screen.
- **Conflict inbox** with per-conflict and bulk resolution (Keep local / Keep
  remote / Keep both) — long-press any row to enter selection mode and resolve
  many conflicts at once. See **[docs/conflict-inbox.md](docs/conflict-inbox.md)**.
- Per-pair sync logs, retries with exponential backoff, and re-authentication
  notifications. See **[docs/error-recovery.md](docs/error-recovery.md)**.

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
app/src/main/java/com/synckro/
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
│   ├── onedrive/OneDriveProvider.kt    # Full MSAL + Graph implementation
│   └── gdrive/GoogleDriveProvider.kt   # Full Credential Manager + Drive v3 implementation
└── di/                                 # Hilt modules
```

## Getting started

1. Clone the repository and open it in Android Studio (or use the Gradle wrapper
   from the command line).
2. Follow **[docs/debug-auth-setup.md](docs/debug-auth-setup.md)** to create a
   pinned debug keystore and register the app in Google Cloud and Azure.
3. Follow **[docs/login-setup.md](docs/login-setup.md)** for a complete
   end-to-end walkthrough of signing into the app with Google Drive and
   OneDrive, including provider-side console setup, first-run consent flows,
   and troubleshooting tips.
4. Follow **[docs/sync-pairs.md](docs/sync-pairs.md)** to create your first
   sync pair, configure the sync schedule, and trigger a manual sync.
5. Consult **[docs/error-recovery.md](docs/error-recovery.md)** if a sync run
   fails or you receive a re-authentication notification.

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
./gradlew assembleRelease      # build testing release APK (signed when DEBUG_KEYSTORE_* is set)
./gradlew testDebugUnitTest    # run unit tests
./gradlew lintDebug            # Android lint
```

### Client IDs and signing secrets for debug + testing release builds

Debug and testing-release builds read several values from `local.properties` or
environment variables and expose them in generated code via `BuildConfig`.
If unset, the build still succeeds but cloud auth will not work at runtime.
In CI, these values come from repository secrets configured under
**Settings → Secrets and variables → Actions**.

See **[docs/debug-auth-setup.md](docs/debug-auth-setup.md)** for the complete
step-by-step instructions covering:

- Creating a pinned debug keystore (required for stable SHA-1 fingerprints).
- Registering the Android app in Google Cloud (for `GOOGLE_WEB_CLIENT_ID`).
- Registering the app in Microsoft Entra / Azure AD (for `MS_CLIENT_ID` and
  `MSAL_REDIRECT_URI`).
- Adding all seven secrets to GitHub Actions.
- Testing locally without secrets.

## CI / CD

GitHub Actions builds both the debug APK and a testing-only signed release APK
on every push, on pull requests, and on manual dispatch. Each run uploads:

- `synckro-debug-apk-<run_number>` from `app/build/outputs/apk/debug/`
- `synckro-testing-release-apk-<run_number>` from `app/build/outputs/apk/release/`

The release artifact is for internal/dev testing only. It reuses the same
`GOOGLE_WEB_CLIENT_ID`, `MS_CLIENT_ID`, `MSAL_REDIRECT_URI`, and
`DEBUG_KEYSTORE_*` values already used by debug builds.

## Roadmap

Completed work (current release):

- [x] SAF folder picker and persisted tree URIs.
- [x] Local-folder enumeration against a `DocumentFile` tree with Room index.
- [x] OneDrive provider (MSAL auth + Graph `/me/drive/root:/.../:/delta` + resumable upload).
- [x] Google Drive provider (Credential Manager + Drive v3 `changes.list` + resumable upload).
- [x] Per-pair settings screen (direction, conflict policy, schedule, globs, constraints).
- [x] Multi-account support (multiple Google Drive / OneDrive identities).
- [x] Conflict inbox with bulk resolution.
- [x] Re-authentication notifications with deep-link back to Accounts screen.
- [x] Sync logs, per-pair sync history, and log export.
- [x] Concurrent transfer support with configurable parallelism.

Near-term planned work:

- [ ] Storage-quota display improvements (inline progress bar on account cards).
- [ ] Onboarding wizard improvements for first-time users.
- [ ] Selective sync tree view (browse and choose sub-folders to include/exclude).
- [ ] Android widget showing last-sync timestamps and quick "Sync now" buttons.

## License

See [LICENSE](./LICENSE).
