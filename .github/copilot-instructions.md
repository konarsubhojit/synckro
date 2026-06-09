# Synckro repository instructions

- This repository is a single-module Android app (`:app`) written in Kotlin with
  Jetpack Compose, Hilt, Room, and WorkManager.
- Use the committed Gradle wrapper for local and CI commands:
  `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, and
  `./gradlew lintDebug`.
- Keep sync-domain logic platform-free when possible. `SyncDiffer` in
  `app/src/main/java/com/synckro/domain/sync/` is intentionally
  pure Kotlin and should stay unit-testable without Android dependencies.
- Cloud providers implement `CloudProvider`; `FakeCloudProvider` is the in-memory
  reference implementation for tests and offline development.
- Prefer small, focused changes that preserve the existing package layout:
  `ui/`, `domain/`, `data/`, `providers/`, `di/`.
- Do not introduce `MANAGE_EXTERNAL_STORAGE`; local folder access is expected to
  use the Storage Access Framework.
- When changing sync behaviour, add or update unit tests under `app/src/test/`.

## Agent workflow guidance

- Prefer the built-in `view`, `grep`, and `glob` tools over `bash` for reading
  files and searching. Reserve `bash` for actually executing things (Gradle,
  git, package managers).
- When iterating, run the narrowest Gradle target first
  (`./gradlew :app:testDebugUnitTest --tests <Class>` or
  `./gradlew :app:lintDebug ktlintCheck`). Only run the full
  `testDebugUnitTest assembleDebug lintDebug` triple once as the final gate
  before `parallel_validation`. Always pass `--no-daemon --console=plain` in
  CI-style invocations so output is parseable.
- The `:app` module applies the ktlint Gradle plugin, so unused imports and
  other ktlint findings will fail CI — run `ktlintCheck` (or `ktlintFormat`)
  before declaring a change done.
- Call `parallel_validation` once after a clean local build+test pass, and
  again only after significant follow-up changes. For doc-only, comment-only,
  formatting, or test-only changes, set
  `trivialChangeDeclaration.codeql.isTrivial = true` instead of re-running for
  cosmetic deltas.
- Batch work into a few meaningful `report_progress` commits (typically 3–5
  per task), not one per file edit.
- Standardize on the `edit` tool for in-place file modifications; avoid mixing
  `edit` and `apply_patch` in the same session to prevent hash conflicts.
- For PR/CI triage, prefer `pull_request_read` with
  `method: get_check_runs` followed by `get_job_logs` with `failed_only: true`
  and the `run_id`, rather than listing all workflow runs and jobs.
- For wide cross-cutting investigations, delegate to the `explore` subagent
  to keep the main context window clean for the actual edits.
