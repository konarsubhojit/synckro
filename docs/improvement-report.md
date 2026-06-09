# Synckro — Improvement Report

_A review of the application workflow, UI, and features, with concrete,
prioritized recommendations._

This report is the result of a repository-wide exploration of the Synckro
codebase (~30k LOC of Kotlin across the `ui`, `domain`, `data`, and `providers`
layers). It identifies opportunities to improve the **end-to-end user
workflow**, the **Compose UI/UX**, and the **feature set**. Every finding cites
the relevant source so it can be acted on directly.

> Scope note: This document is an analysis and planning artifact. It does not
> change application code. Recommendations are grouped by area and ranked by
> impact so they can be turned into individual issues/PRs.

---

## 1. Executive summary

Synckro is a well-architected Android folder-sync app (MVVM + Clean
Architecture, Hilt, Compose/Material 3, Room, WorkManager). The sync core is
pure, unit-tested Kotlin (`SyncDiffer`), and the providers (Google Drive,
OneDrive) implement real OAuth, token refresh, delta/changes enumeration, and
resumable uploads. Notable strengths include the conflict inbox with bulk
resolution, an adaptive list/detail tablet layout, structured event logging
with export redaction, and robust retry/backoff handling.

The biggest opportunities fall into three buckets:

1. **Workflow friction** — manual-sync eligibility fails silently, sync results
   are not always surfaced, local-folder permission is validated late, and
   several form inputs (glob filters, retention, intervals) lack inline help or
   validation feedback.
2. **UI completeness & accessibility** — most screens are missing
   loading/error/empty states, there are **43** `Icon(..., contentDescription =
   null)` occurrences hurting screen-reader support, and tablet/adaptive support
   is uneven across screens.
3. **Feature gaps** — the **mobile bandwidth limit setting is wired into the UI
   and persisted but never enforced**, there is no file compression, no
   client-side encryption, no additional providers (e.g., Dropbox), and no
   selective-sync UI.

A fourth, cheap-but-important item: the **README is significantly out of date**
— it still describes the project as "early scaffolding" whose providers "return
`false`" and "throw `NotYetImplementedException`," which no longer reflects the
implemented Google Drive / OneDrive providers.

---

## 2. Workflow improvements

### 2.1 Manual "Sync now" fails silently when a pair is ineligible
When a pair is paused or not eligible for a manual run, the action appears to do
nothing — there is no disabled state, tooltip, or message explaining why.
- **Impact:** High. Users perceive the app as broken.
- **Fix:** Disable the control with an explanatory tooltip/snackbar (e.g.,
  "Paused — enable auto-sync or resume to sync"), or allow a manual run to
  override the paused state explicitly.
- **Where:** `ui/screens/home/HomeViewModel.kt`,
  `ui/screens/pairs/PairsScreen.kt`, `ui/screens/pairdetail/PairDetailScreen.kt`.

### 2.2 Sync results are not consistently surfaced
`PairsScreen` shows a completion snackbar, but a manual sync started elsewhere
(e.g., pair detail) does not always report conflicts/errors back to the user.
- **Impact:** High. Conflicts can pile up unnoticed.
- **Fix:** Standardize a post-sync snackbar/result summary (files changed,
  conflicts created, errors) across all manual-sync entry points, with a deep
  link to the conflict inbox when conflicts were produced.
- **Where:** `ui/screens/pairs/PairsScreen.kt` (existing pattern, lines ~153-169),
  `ui/screens/pairdetail/PairDetailScreen.kt`.

### 2.3 Local-folder permission is validated too late
A selected SAF tree URI whose permission is later revoked is only discovered at
sync time, surfacing as a confusing failure.
- **Impact:** Medium-High.
- **Fix:** Validate the persisted URI permission at pair-save time and on pair
  detail load; prompt the user to re-pick the folder if access is gone.
- **Where:** `ui/screens/paireditor/PairEditorViewModel.kt`,
  `data/local/fs/SafLocalFileAccess.kt`.

### 2.4 "Provider not configured" is not explained
When client IDs aren't configured, sign-in/sync controls are disabled with no
reason given.
- **Impact:** Medium.
- **Fix:** Show helper text under disabled controls pointing to setup docs.
- **Where:** `ui/screens/accounts/AccountsScreen.kt`,
  `ui/screens/paireditor/PairEditorScreen.kt`.

### 2.5 Folder-picker friction
- Remote folder picker has no visible loading state, no error/retry on fetch
  failure, no search, and no manual refresh — deep hierarchies are slow to
  browse.
- After selecting a folder there is no "Change" affordance to re-pick without
  starting over.
- **Where:** `ui/screens/pickfolder/PickRemoteFolderScreen.kt`,
  `ui/screens/pickfolder/PickRemoteFolderViewModel.kt`,
  `ui/screens/pickfolder/PickLocalFolderScreen.kt`.

### 2.6 Onboarding guidance gaps
- No step indicator (dots/progress) in the pager.
- No inline explanation of what a "sync pair" is before the user creates one.
- No error/offline handling if account linking fails mid-onboarding.
- **Where:** `ui/screens/OnboardingScreen.kt`,
  `ui/screens/onboarding/OnboardingGateway.kt`.

### 2.7 Account re-auth not surfaced proactively
`AccountsViewModel` exposes a `needsReauth` signal, but the Accounts list does
not always show a clear "Re-authentication required" indicator with a one-tap
fix.
- **Where:** `ui/screens/accounts/AccountsScreen.kt`,
  `ui/screens/accounts/AccountsViewModel.kt`.

### 2.8 Log hygiene
There is no in-app "Clear logs" action and no documented retention behavior, so
the event table can grow unbounded.
- **Fix:** Add a "Clear logs" / "Clear logs older than N days" menu action and a
  retention sweep.
- **Where:** `ui/screens/logs/LogsScreen.kt`,
  `data/repository/SyncEventRepository.kt`.

---

## 3. UI / UX improvements

### 3.1 Add reusable loading/error states everywhere
There is a good `LoadingState` and `EmptyState` component, but most screens do
not render loading or error states, so failures show as blank content.

| Screen | Empty | Loading | Error | Retry |
|--------|:-----:|:-------:|:-----:|:-----:|
| Status | ❌ | ❌ | ❌ | ❌ |
| Pairs | ✅ | ❌ | ❌ | ❌ |
| Logs | ❌ | ❌ | ❌ | ❌ |
| Accounts | ❌ | ⚠️ | ❌ | ❌ |
| Conflicts | ❌ | ❌ | ❌ | ❌ |
| Pair Detail | ❌ | ❌ | ❌ | ❌ |
| Pair Editor | ❌ | ❌ | ❌ | ❌ |

- **Fix:** Add an `ErrorState` component (icon + message + Retry) mirroring
  `LoadingState`/`EmptyState`, and wire loading/error states for every async
  screen.
- **Where:** `ui/components/` (new `ErrorState.kt`), all `ui/screens/*`.

### 3.2 Accessibility: 43 missing content descriptions
There are **43** `Icon(..., contentDescription = null)` occurrences across the
UI. Many are meaningful (overflow menu, account actions, conflict
direction/provider icons, pair-card actions) and should carry localized labels;
genuinely decorative icons should keep `null` with a brief comment justifying
it.
- **Hotspots:** `ui/navigation/MainScaffold.kt`,
  `ui/screens/pairs/PairsScreen.kt`,
  `ui/screens/accounts/AccountsScreen.kt`,
  `ui/screens/conflictinbox/ConflictInboxScreen.kt`,
  `ui/screens/pairdetail/PairDetailScreen.kt`.
- **Also:** ensure event-level badges (SUCCESS/WARNING/ERROR) convey state via
  icon/text, not color alone; announce sync completion (not just progress %).

### 3.3 Adaptive layout consistency
`PairsScreen` and `ConflictInboxScreen` use
`NavigableListDetailPaneScaffold` (great), but detection uses a raw
`screenWidthDp` breakpoint (720dp) rather than Material 3
`WindowSizeClass`, and `StatusScreen` only width-caps content. There is no
landscape/foldable-aware handling.
- **Fix:** Standardize on `WindowSizeClass`; extend adaptive layouts to Status
  and Settings; add landscape previews.

### 3.4 Form clarity in the Pair Editor
- Sync direction and conflict-policy options have no inline descriptions
  (BIDIRECTIONAL / UPLOAD_ONLY / NEWEST_WINS / KEEP_BOTH, etc.).
- Glob include/exclude fields have minimal help and no validation feedback.
- Custom interval is silently clamped to a 15-minute floor (WorkManager limit)
  with no hint or validation message.
- Retention days lack an explanation of what happens at the boundary.
- **Where:** `ui/screens/paireditor/PairEditorScreen.kt`,
  `ui/screens/paireditor/PairEditorViewModel.kt`.

### 3.5 Confirmation, undo, and destructive-action safety
- Conflict resolutions are permanent with no undo (pair delete already has a
  good undo pattern — extend it).
- Disabling global auto-sync, logging out an account, and large/destructive
  one-way syncs have no confirmation or data-loss warning.
- **Where:** `ui/screens/conflictinbox/*`, `ui/screens/accounts/AccountsScreen.kt`,
  `ui/screens/pairs/PairsScreen.kt`.

### 3.6 Smaller polish items
- Toast/snackbar feedback when copying a log line to the clipboard.
- Show export file location after a log export completes.
- Make folder-picker breadcrumbs visibly tappable.
- `CoachTooltip` has no manual dismiss affordance and is hidden from screen
  readers (`enableUserInput = false`).
- Consider an **extended FAB** with a label on the Pairs screen for
  discoverability.
- **Where:** `ui/screens/logs/LogsScreen.kt`, `ui/components/CoachTooltip.kt`,
  `ui/screens/pairs/PairsScreen.kt`.

---

## 4. Feature improvements

### 4.1 Enforce mobile bandwidth limits (currently a stub)
The Settings screen exposes mobile upload/download limit dialogs and persists
`mobileUploadLimitMb` / `mobileDownloadLimitMb`, but **no enforcement exists** in
the sync worker or applier — the setting has no runtime effect.
- **Impact:** High (correctness/trust). Users believe limits apply.
- **Fix:** Enforce throttling in the transfer path (and/or honor a
  metered-network "warn on mobile" gate), or hide the UI until enforcement
  lands.
- **Where (UI/persistence):** `ui/screens/settings/SettingsScreen.kt:506-547,706-728`,
  `ui/screens/settings/SettingsViewModel.kt:57-58,260-265`,
  `data/repository/SettingsRepository.kt:101-119`.
- **Where (missing enforcement):** `data/worker/SyncWorker.kt`,
  `domain/sync/SyncOpApplier.kt`.

### 4.2 Selective sync UI
Glob include/exclude exists at the engine level, but there is no friendly
per-folder/per-type selective-sync UI.
- **Fix:** Add a tree/checkbox selection or category toggles that compile down to
  the existing glob filters.
- **Where:** `ui/screens/paireditor/*`, `data/local/fs/LocalFsEnumerator.kt`.

### 4.3 Compression for transfers
Text-heavy folders transfer uncompressed.
- **Fix:** Optional gzip on upload/download with a per-pair toggle.
- **Where:** `domain/sync/SyncOpApplier.kt`, provider REST/Graph clients.

### 4.4 Client-side encryption (at rest / in transit)
No app-level encryption of file contents (auth tokens use the SDKs' encrypted
stores).
- **Fix:** Optional end-to-end encryption for synced content as a security
  differentiator.
- **Where:** new module in `domain/sync/` + provider clients.

### 4.5 Additional providers
Only Google Drive and OneDrive are implemented behind a clean `CloudProvider`
abstraction.
- **Fix:** Add Dropbox (high demand) and potentially S3/WebDAV using the existing
  provider + enumerator + auth-manager pattern.
- **Where:** `providers/` (mirror `gdrive`/`onedrive` structure),
  `di/CloudProviderModule.kt`.

### 4.6 File move/rename detection
Renames/moves are currently treated as delete + create, causing unnecessary
re-uploads/downloads of large files.
- **Fix:** Use provider parent-reference/parents change data to detect moves and
  remap instead of re-transferring.
- **Where:** `providers/*/...RemoteEnumerator.kt`, `domain/sync/SyncDiffer.kt`.

### 4.7 Delta-token resilience and user feedback
On delta-token expiry (HTTP 410) the enumerators fall back to a baseline scan; a
WARN event is logged but the user may just see "nothing happened."
- **Fix:** Surface a transient, user-visible notice and consider periodic full
  re-enumeration to validate incremental state.
- **Where:** `providers/gdrive/GoogleDriveRemoteEnumerator.kt`,
  `providers/onedrive/OneDriveRemoteEnumerator.kt`, `domain/sync/SyncEngine.kt`.

### 4.8 Sync statistics & richer conflict tooling
- No per-run statistics dashboard (files/bytes transferred, errors by type,
  duration).
- Conflict inbox could add file-size delta, thumbnails (already cached for some
  providers), and "resolve all as <policy>" batch actions.
- **Where:** new `data/repository/SyncStatisticsRepository.kt`,
  `ui/screens/pairdetail/*`, `ui/screens/conflictinbox/*`.

---

## 5. Documentation & housekeeping

- **README is stale.** It still says the project is "early scaffolding" and that
  the cloud providers "return `false` from `ensureAuthenticated()`" and "throw
  `NotYetImplementedException`." The Google Drive and OneDrive providers are now
  implemented (OAuth, token refresh, delta/changes, resumable uploads). Update
  the status, features, and roadmap sections to reflect reality.
  - **Where:** `README.md:6-13` (status), `README.md:15-27` (features),
    `README.md:128-139` (roadmap).
- **Document core workflows.** `docs/` covers conflict inbox, login, and
  notifications well, but there is no end-to-end guide for **creating a sync
  pair**, **running/scheduling syncs** (including the 15-minute interval floor
  and constraints), or **error recovery** beyond re-auth. Add these.
- **Package path mismatch in README.** The README package tree shows
  `com.konarsubhojit.synckro`, while the actual source is under `com.synckro`.
  Reconcile the docs with the real package.

---

## 6. Prioritized roadmap

**Phase 1 — Trust & core workflow (highest ROI)**
1. Enforce or hide mobile bandwidth limits (§4.1).
2. Fix silent manual-sync eligibility + surface sync results everywhere (§2.1, §2.2).
3. Validate local-folder permission at save time (§2.3).
4. Refresh the README to match the implemented app (§5).

**Phase 2 — UI completeness & accessibility**
5. Add `ErrorState` + loading/error/empty states across screens (§3.1).
6. Resolve the 43 missing content descriptions and color-only badges (§3.2).
7. Inline help + validation in the Pair Editor; folder-picker retry/loading (§3.4, §2.5).

**Phase 3 — Feature depth**
8. Selective-sync UI (§4.2), conflict-inbox batch actions & previews (§4.8).
9. Move/rename detection (§4.6), delta-token user feedback (§4.7).
10. Additional providers (Dropbox), compression, and client-side encryption
    (§4.5, §4.3, §4.4).

**Cross-cutting**
- Standardize adaptive layout on `WindowSizeClass`; add landscape/foldable
  support (§3.3).
- Add confirmations/undo and data-loss warnings for destructive actions (§3.5).
- Document pair creation, sync scheduling, and error recovery (§5).
