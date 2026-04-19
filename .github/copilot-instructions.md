# Synckro repository instructions

- This repository is a single-module Android app (`:app`) written in Kotlin with
  Jetpack Compose, Hilt, Room, and WorkManager.
- Use the committed Gradle wrapper for local and CI commands:
  `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, and
  `./gradlew lintDebug`.
- Keep sync-domain logic platform-free when possible. `SyncDiffer` in
  `app/src/main/java/com/konarsubhojit/synckro/domain/sync/` is intentionally
  pure Kotlin and should stay unit-testable without Android dependencies.
- Cloud providers implement `CloudProvider`; `FakeCloudProvider` is the in-memory
  reference implementation for tests and offline development.
- Prefer small, focused changes that preserve the existing package layout:
  `ui/`, `domain/`, `data/`, `providers/`, `di/`.
- Do not introduce `MANAGE_EXTERNAL_STORAGE`; local folder access is expected to
  use the Storage Access Framework.
- When changing sync behaviour, add or update unit tests under `app/src/test/`.
