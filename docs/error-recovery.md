# Error Recovery

This document describes the errors that can occur during sync and how to
recover from each one.

---

## Table of contents

1. [Authentication errors](#1-authentication-errors)
2. [Network and transient errors](#2-network-and-transient-errors)
3. [Sync conflicts](#3-sync-conflicts)
4. [Local folder permission lost (SAF)](#4-local-folder-permission-lost-saf)
5. [Terminal sync failure](#5-terminal-sync-failure)
6. [Diagnosing failures with the sync log](#6-diagnosing-failures-with-the-sync-log)

---

## 1. Authentication errors

### How the app handles token expiry

Both the Google Drive and OneDrive providers proactively refresh access tokens
before they expire (at ~50 minutes). On a `401 Unauthorized` response during a
sync operation, the provider:

1. Clears the cached token.
2. Performs a silent token refresh.
3. Replays the failed operation once.

If the silent refresh succeeds, the sync continues without any user action.

### When interactive sign-in is required

If silent refresh fails (e.g. the refresh token was revoked or expired), the
provider tracks consecutive failures. After reaching the failure threshold
(default: 2 consecutive failures), the sync is marked **terminal** and a
**re-authentication notification** is posted via the `synckro_reauth` channel.

**Recovery steps:**

1. Tap the notification. The app opens and navigates directly to the
   **Accounts** screen, scrolling to and briefly highlighting the affected
   account row.
2. Tap **Sign in** on the affected account.
3. Complete the interactive sign-in flow (Google Credential Manager sheet or
   Microsoft browser sign-in).
4. Once sign-in succeeds, the notification is automatically dismissed.
5. Return to the **Pairs** tab and tap **Sync now** to resume syncing
   immediately, or wait for the next scheduled run.

Alternatively, from the **Pairs** tab, tap the pair card's warning badge
(if shown) to jump directly to the Accounts screen for the affected account.

### Re-authentication notification details

| Property | Value |
|:---------|:------|
| Channel | `synckro_reauth` (`IMPORTANCE_HIGH` — heads-up banner + sound) |
| Notification ID | Derived from the account ID; one notification per account |
| Group | `com.synckro.REAUTH_GROUP` (collapsed summary on Android 7+) |
| Action button | **Reconnect** — opens Accounts screen for the affected account |
| Auto-dismissed | Yes — cancelled automatically after a successful sign-in |

See **[docs/notifications.md](notifications.md)** for full channel
documentation.

---

## 2. Network and transient errors

Network failures (timeouts, DNS errors, server-side 5xx responses) are treated
as retriable errors. WorkManager retries the sync with exponential backoff:

- **Initial retry delay**: 30 seconds.
- **Policy**: `EXPONENTIAL` (doubles after each failure, up to WorkManager's
  internal ceiling of approximately 5 hours).

No user action is required for transient failures — the job will retry
automatically. If you want to force an immediate retry, tap **Sync now** on
the affected pair.

The per-pair sync log (accessible from **Pair Detail → Logs**) records each
attempt and its outcome, including the error message for failed runs.

---

## 3. Sync conflicts

A *conflict* is recorded when both the local and remote copies of a file have
changed since the last successful sync. The sync engine does **not** silently
overwrite either side; instead it records a `ConflictRecord` in Room and halts
that file's sync operation until you resolve it.

Pending conflicts are visible in the **Conflict Inbox**, accessible from the
Home screen badge or the bottom navigation.

**Resolution options per conflict:**

| Action | Effect |
|:-------|:-------|
| **Keep local** | The local file is uploaded and overwrites the remote copy on the next sync. |
| **Keep remote** | The remote file is downloaded and overwrites the local copy on the next sync. |
| **Keep both** | Both copies are retained; the non-winning copy is renamed with a `(conflict copy)` suffix. |

**Bulk resolution:** Long-press any conflict row to enter selection mode. Select
multiple rows and apply Keep local / Keep remote / Keep both to all of them at
once. See **[docs/conflict-inbox.md](conflict-inbox.md)** for details.

Once a resolution is stored, the chosen action is applied on the next sync run.

---

## 4. Local folder permission lost (SAF)

The Storage Access Framework grants a *persisted URI permission* for the local
folder when you create the pair. This permission can be lost when:

- The user manually revokes it (Settings → Privacy → Special app access →
  Files & media, or via a file manager's storage permissions view).
- An SD card is removed or reformatted.
- The device is factory-reset.

**Symptoms:**

- The pair card in the Pairs list shows an orange **Needs re-link** badge.
- Sync runs for the pair are skipped with a "local folder permission lost" error
  in the sync log.

**Recovery steps:**

1. Open the **Pairs** tab and tap the affected pair card.
2. Tap the **edit / pencil** icon to open the Pair Editor.
3. Tap **Choose folder** to open the system folder picker and re-select the
   local folder (or a replacement folder).
4. Tap **Save**.

The orange badge disappears and the next sync run will proceed normally.

---

## 5. Terminal sync failure

A *terminal failure* means the sync engine has exhausted all automatic retries
and the pair is in a permanent error state until you intervene. Terminal failures
include:

- **Authentication required**: the account's tokens are fully revoked and silent
  refresh cannot recover them. See [section 1](#1-authentication-errors).
- **Remote folder deleted**: the cloud folder the pair pointed to no longer
  exists. Open the Pair Editor and choose a new remote folder.
- **Quota exceeded**: the cloud account has no storage space remaining. Free up
  space in the cloud account or switch the pair to a different account.

The pair's **Last result** field on the Pair Detail screen shows `FAILURE`.
The sync log contains the specific error message and timestamp for the failed run.

A `sync_status` channel notification is posted for terminal failures (channel
importance: `IMPORTANCE_DEFAULT`). Tap it to open the Pair Detail screen
directly.

---

## 6. Diagnosing failures with the sync log

Every sync run appends structured events to the per-pair sync log. Access it
from:

- **Pair Detail** screen → **Logs** section.
- **Home screen** bottom sheet for a pair in an error state.

The log supports **level filtering** (Info / Warning / Error) and **tag
filtering** (Upload / Download / Conflict / Auth / etc.) to isolate the
relevant entries quickly.

**Exporting logs for support:**

1. Open **Settings → Debug → Export logs**.
2. Choose whether to redact file paths and account IDs in the export.
3. Share the exported file with the development team.

Log retention is configurable under **Settings → Sync → Log retention period**
(default: 30 days). Entries older than the retention period are pruned
automatically.
