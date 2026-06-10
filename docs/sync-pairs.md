# Sync Pairs — Creating, Editing, and Managing

A *sync pair* links a local folder on your device (selected via the Storage
Access Framework) to a folder inside a cloud provider account (Google Drive or
OneDrive). This document walks you through creating your first pair, adjusting
its settings, and triggering syncs manually.

> **Prerequisites**
>
> - At least one cloud account must be connected before you can create a pair.
>   Follow **[docs/login-setup.md](login-setup.md)** if you have not signed in yet.
> - If you are building from source, follow
>   **[docs/debug-auth-setup.md](debug-auth-setup.md)** first to register the
>   app in Google Cloud and Azure so authentication works.

---

## Table of contents

1. [Creating a sync pair](#1-creating-a-sync-pair)
2. [Required fields](#2-required-fields)
3. [Sync direction](#3-sync-direction)
4. [Conflict policy](#4-conflict-policy)
5. [Schedule and constraints](#5-schedule-and-constraints)
6. [File filters](#6-file-filters)
7. [Editing a pair](#7-editing-a-pair)
8. [Deleting a pair](#8-deleting-a-pair)
9. [Manual sync](#9-manual-sync)
10. [Pair status and last-run details](#10-pair-status-and-last-run-details)

---

## 1. Creating a sync pair

1. Open the app and go to the **Pairs** tab (folder icon in the bottom nav).
2. Tap the **+** / **New pair** button.
3. The **Pair Editor** screen opens.
4. Fill in the required fields described in the next section.
5. Tap **Save**. The pair is persisted in Room and, if auto-sync is enabled, its
   first periodic WorkManager job is enqueued immediately.

---

## 2. Required fields

| Field | Description |
|:------|:------------|
| **Display name** | A friendly label shown in the Pairs list and Home screen (e.g. `Camera → Drive`). |
| **Local folder** | Tap **Choose folder** to open the system folder picker (SAF `ACTION_OPEN_DOCUMENT_TREE`). Works for internal storage and removable SD cards. The app holds a persisted read+write URI permission for the lifetime of the pair — no `MANAGE_EXTERNAL_STORAGE` required. |
| **Cloud provider** | Select **Google Drive** or **OneDrive**. |
| **Account** | One of the signed-in accounts for the chosen provider. If no account is listed, go to **Accounts** and sign in first. |
| **Remote folder** | Tap **Choose remote folder** to browse the cloud and select the target folder. The folder's display name (e.g. `Documents/Receipts`) is saved alongside its opaque provider ID and shown in the UI. |

---

## 3. Sync direction

Choose how data flows between local and cloud:

| Option | Description |
|:-------|:------------|
| **Bidirectional** (default) | Changes on either side are propagated to the other. Conflicts resolved per [conflict policy](#4-conflict-policy). |
| **Upload only** | Only local → cloud. Remote-only changes are ignored. |
| **Download only** | Only cloud → local. Local-only changes are ignored. |
| **Upload + delete local after N days** | Upload-only backup: local files are deleted once confirmed in the cloud and older than the retention period. |
| **Download + delete remote after N days** | Download-only offload: remote files are deleted once downloaded locally and older than the retention period. |

> **Warning**: The two deletion modes permanently remove files. The Pair Editor
> requires explicit confirmation and highlights affected fields in a warning
> colour before you can save.

---

## 4. Conflict policy

A *conflict* occurs when both the local and remote copies of a file have changed
since the last successful sync.

| Policy | Behaviour |
|:-------|:----------|
| **Newest wins** (default) | The copy with the more recent last-modified timestamp overwrites the other. |
| **Prefer local** | The local copy always wins. |
| **Prefer remote** | The cloud copy always wins. |
| **Keep both** | Both copies are kept; the non-winning copy is renamed with a `(conflict copy)` suffix. |

Conflicts recorded during a sync run appear in the **Conflict Inbox**
(accessible from the Home screen or the bottom nav). See
[docs/conflict-inbox.md](conflict-inbox.md) for the resolution workflow,
including bulk resolution.

---

## 5. Schedule and constraints

See **[docs/scheduling.md](scheduling.md)** for the full scheduling guide.
In brief:

- Toggle **Auto sync** to enable or disable background periodic sync for this
  pair independently of the global setting.
- Choose a **schedule preset** (15 min / 30 min / 1 h / 24 h / custom).
- Enable **Wi-Fi only** to prevent syncing on metered mobile data (on by default).
- Enable **Charging only** to run syncs only while the device is plugged in.

---

## 6. File filters

Use glob patterns to narrow the set of files included in a sync:

| Setting | Effect |
|:--------|:-------|
| **Include globs** | Only files matching at least one pattern are synced. Leave empty to include everything. Example: `*.jpg,*.png` |
| **Exclude globs** | Files matching any pattern are skipped. Applied after include globs. Example: `*.tmp,.DS_Store` |
| **Exclude sub-folders** | When enabled, only files directly inside the root folder are synced; nested directories are ignored on both sides. |
| **Exclude empty folders** | Remote folders that contain no files are not created on the local side (and vice versa). |

Patterns use standard glob syntax: `*` matches any sequence of characters within
a path component; `**` is not currently supported — patterns are matched against
the filename only, not the full relative path.

---

## 7. Editing a pair

1. Open the **Pairs** tab and tap the pair you want to edit.
2. On the **Pair Detail** screen tap the **edit / pencil** icon in the top-right.
3. Make your changes in the Pair Editor and tap **Save**.

Changes take effect on the next sync run. If you change the schedule interval,
the existing WorkManager periodic job is cancelled and a new one is enqueued.

> **Re-linking a local folder**: If the SAF permission for the local folder was
> revoked (e.g. after an SD card swap), the pair is marked **Needs re-link** in
> orange in the Pairs list. Open the editor and tap **Choose folder** again to
> grant a fresh permission.

---

## 8. Deleting a pair

1. Open the **Pairs** tab and long-press (or swipe) the pair, or tap into **Pair
   Detail** and use the **Delete** action in the overflow menu.
2. Confirm the deletion in the dialog.

Deleting a pair:
- Removes the pair record and its full event log from Room.
- Cancels all associated WorkManager jobs.
- Does **not** delete any files — neither local nor remote.
- Does **not** sign out the associated cloud account.

---

## 9. Manual sync

Trigger an immediate sync outside the normal schedule from two places:

- **Home screen / Pairs tab**: Tap the **Sync now** button on a pair card.
- **Pair Detail screen**: Tap the **Sync now** button in the action bar.

The button is disabled (greyed out) if:
- The pair is already syncing.
- Auto sync is disabled for this pair (you can still trigger a manual sync from
  Pair Detail, but a snackbar explains any restriction).
- The pair needs re-linking (SAF permission lost).
- The pair's account requires re-authentication.

If a manual sync is blocked, the Home screen shows a snackbar describing the
reason (e.g. "Sync in progress", "Account needs sign-in").

---

## 10. Pair status and last-run details

The **Pair Detail** screen shows:

| Item | Description |
|:-----|:------------|
| **Last synced** | Timestamp of the most recently completed sync run. |
| **Last result** | `SUCCESS`, `PARTIAL_FAILURE`, or `FAILURE`. |
| **Sync log** | Filterable per-pair log of every sync event (uploads, downloads, skips, errors). |
| **Progress** | While a sync is in progress, per-file transfer rows show the current file name and direction. |

For failure diagnosis and recovery steps, see
**[docs/error-recovery.md](error-recovery.md)**.
