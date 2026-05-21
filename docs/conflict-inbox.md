# Conflict Inbox

This document describes the conflict inbox screen, its data model, and the bulk-resolution selection mode introduced in Phase 6c.

---

## Overview

When a sync run detects that **both** the local and remote copies of a file have changed since the last successful sync, it records a `ConflictRecord` in Room and surfaces it in the Conflict Inbox screen.  The user must choose one of three resolutions; the choice is persisted immediately and applied on the next sync run.

| Resolution     | Constant                              | Effect on next sync                              |
|:---------------|:--------------------------------------|:-------------------------------------------------|
| Keep local     | `ConflictRecord.RESOLUTION_KEEP_LOCAL`  | Local file overwrites the cloud copy             |
| Keep remote    | `ConflictRecord.RESOLUTION_KEEP_REMOTE` | Cloud file overwrites the local copy             |
| Keep both      | `ConflictRecord.RESOLUTION_KEEP_BOTH`   | Both copies are kept; one is renamed with a conflict-copy suffix |

---

## Architecture

```
ConflictInboxScreen   ──reads──►  ConflictInboxViewModel.state (StateFlow<UiState>)
                      ◄─calls──   viewModel.keepLocal / keepRemote / keepBoth
                                  viewModel.enterSelectionMode / toggleSelection
                                  viewModel.exitSelectionMode
                                  viewModel.bulkKeepLocal / bulkKeepRemote / bulkKeepBoth
                                          │
                              ConflictRepository.resolve(id, resolution)   ──►  Room (conflict_record table)
```

### `ConflictInboxViewModel`

| State field        | Type            | Purpose                                                      |
|:-------------------|:----------------|:-------------------------------------------------------------|
| `conflicts`        | `List<ConflictRow>` | Projected rows from `ConflictRepository.observeUnresolved()` |
| `isLoading`        | `Boolean`       | `true` until the first emission from the repository flow     |
| `isSelectionMode`  | `Boolean`       | Whether the user is in bulk-selection mode                   |
| `selectedIds`      | `Set<Long>`     | IDs of currently selected conflict records                   |
| `selectedCount`    | `Int` (computed)| `selectedIds.size` convenience property                      |

Selection state is kept in a private `MutableStateFlow<Pair<Boolean, Set<Long>>>` and combined with the conflicts flow so the screen always receives a consistent snapshot.

### `ConflictRecord` (domain model)

```kotlin
data class ConflictRecord(
    val id: Long,
    val pairId: Long,
    val relativePath: String,
    val localLastModifiedMs: Long,
    val remoteLastModifiedMs: Long,
    val detectedAtMs: Long,
    val resolution: String? = null,   // null = pending; one of the RESOLUTION_* constants = resolved
)
```

---

## Single-conflict resolution (normal mode)

Each conflict card shows metadata for both sides and three action buttons.  Tapping a button calls the corresponding `viewModel.keepLocal(id)` / `keepRemote(id)` / `keepBoth(id)` method, which immediately calls `ConflictRepository.resolve(id, resolution)`.  The card updates to show a "Queued" badge once the resolution is stored.

---

## Bulk resolution (selection mode)

Phase 6c adds selection mode so users with many conflicts can resolve them all in one go.

### Entering selection mode

Long-press any conflict row → calls `viewModel.enterSelectionMode(id)`.

- Sets `isSelectionMode = true`.
- Adds the long-pressed row's `id` to `selectedIds` as the first selection.
- The top app bar swaps to a **contextual bar** showing `N selected` and three action icon buttons (Keep local ↑, Keep remote ↓, Keep both ⎘) plus a Close button.

### While in selection mode

- **Tap** a row → `viewModel.toggleSelection(id)` adds the id if unselected, removes it if already selected.
- Each row shows a `CheckCircle` icon (filled = selected, faint = unselected) instead of the file-type icon.
- Per-card resolution buttons are hidden.
- The explainer banner at the top of the list is hidden.
- Cards for selected rows use `primaryContainer` background colour for visual distinction.
- TalkBack announces each row's selection state via `Modifier.semantics { stateDescription = "selected" / "not selected" }`.

### Applying a bulk resolution

Tapping one of the three icon buttons in the contextual bar calls the corresponding `bulkKeepLocal()` / `bulkKeepRemote()` / `bulkKeepBoth()` method, which:

1. Captures the current `selectedIds` as an ordered list.
2. Immediately calls `exitSelectionMode()` (clears `isSelectionMode` and `selectedIds`).
3. Iterates the captured IDs **sequentially** in a coroutine, calling `ConflictRepository.resolve(id, resolution)` for each one.  Failures are logged but do not abort the remaining resolutions.

> Resolutions are applied sequentially (not in parallel) to avoid concurrent writes to the same Room table rows, matching the single-conflict code path.

### Exiting selection mode without resolving

- Tap the **Close** (×) button in the contextual bar.
- Or perform the **system back gesture** (predictive back / button) — a `BackHandler` intercepts back while `isSelectionMode == true`, calls `exitSelectionMode()`, and prevents the screen from being popped.

---

## Accessibility

| Requirement                            | Implementation                                                      |
|:---------------------------------------|:--------------------------------------------------------------------|
| TalkBack selection announcement        | `Modifier.semantics { stateDescription = "selected" / "not selected" }` on each card |
| Contextual bar actions are icon-only   | Each `IconButton` has a `contentDescription` (the action label string) |
| Close button                           | `contentDescription = stringResource(R.string.conflict_inbox_cancel_selection)` |

---

## Relevant files

| File | Role |
|:-----|:-----|
| `app/src/main/java/com/synckro/ui/screens/conflictinbox/ConflictInboxScreen.kt` | Compose screen: selection mode UI, contextual app bar, `BackHandler` |
| `app/src/main/java/com/synckro/ui/screens/conflictinbox/ConflictInboxViewModel.kt` | State holder: selection mode transitions, bulk resolution |
| `app/src/main/java/com/synckro/data/repository/ConflictRepository.kt` | Persists resolutions via `resolve(id, resolution)` |
| `app/src/main/java/com/synckro/domain/model/ConflictRecord.kt` | Domain model + resolution constants |
| `app/src/main/res/values/strings.xml` | Localised strings for selection mode UI |
| `app/src/test/java/com/synckro/ui/screens/conflictinbox/ConflictInboxViewModelTest.kt` | Unit tests for selection-mode state transitions and bulk apply |
