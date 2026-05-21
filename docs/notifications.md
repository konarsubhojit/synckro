# Synckro Notification Strategy

This document describes the app's notification channels, their intended usage, and maintenance notes for future contributors.

---

## Notification Channels

### 1. `synckro_sync` — Sync progress

| Property      | Value                                                         |
|:--------------|:--------------------------------------------------------------|
| Constant      | `SyncWorker.SYNC_CHANNEL_ID`                                  |
| Importance    | `IMPORTANCE_LOW` (silent, no badge, no heads-up)              |
| Badge         | Disabled                                                      |
| Registered in | `SynckroApp.createNotificationChannels()`                     |

**Purpose:** Shown as a foreground-service progress bar while `SyncWorker` is actively transferring files.  The notification is posted only when:

- A sync run exceeds 30 seconds (`FOREGROUND_PROMOTE_DELAY_MS`), **or**
- The engine reports a total transfer size ≥ 10 MiB (`LARGE_TRANSFER_THRESHOLD_BYTES`).

Because a syncing animation is already visible in the Home screen, low importance is correct here — we do not want an intrusive alert for routine background work.

**Lifecycle:** Automatically dismissed when the foreground service stops (WorkManager manages this).

---

### 2. `synckro_reauth` — Re-authentication required

| Property      | Value                                                                       |
|:--------------|:----------------------------------------------------------------------------|
| Constant      | `ReauthNotificationHelper.REAUTH_CHANNEL_ID`                                |
| Importance    | `IMPORTANCE_HIGH` (heads-up banner, badge, sound)                           |
| Badge         | Enabled                                                                     |
| Registered in | `SynckroApp.createNotificationChannels()`                                   |

**Purpose:** Alerts the user when a cloud account needs interactive sign-in before sync can continue.  This state is terminal — the periodic sync chain has already been cancelled — so the user must actively reconnect the account.  The notification therefore uses `IMPORTANCE_HIGH` to ensure it is visible even if the app is in the background or killed.

**When is it posted?**  
`ReauthNotificationHelper.postReauthNotification(context, pair)` is called from `SyncWorker.doWork()` at every location where the outcome is written as `RESULT_NEEDS_REAUTH`:

1. `SyncEngine.Result.Terminal` with `needsReauth = true`.
2. An escaped `CloudProviderException` mapped to a terminal reauth result.

**Per-account notifications:** Each distinct `accountId` receives its own notification (ID computed from `accountId.hashCode()` in the range `[100,000 – 104,095]`).  On Android 7+ (API 24) these are grouped under a single drawer header using the group key `com.synckro.REAUTH_GROUP`.

**Deep link:** Both the notification tap and the "Reconnect" action button fire an `Intent` with action `com.synckro.ACTION_OPEN_ACCOUNTS` targeting `MainActivity`. The intent additionally carries the offending `accountId` as the `com.synckro.EXTRA_ACCOUNT_ID` string extra (omitted on the group-summary intent). `MainActivity` forwards both pieces to `AppNavigationDispatcher`, which posts `AppNavEvent.OpenAccounts(accountId = …)`. `SynckroNavHost` observes the dispatcher, selects the Accounts tab inside `MainScaffold`, and — when an `accountId` was supplied — asks `AccountsScreen` to briefly highlight (and scroll to) the affected row. The same `OpenAccounts` event is reused by the in-app reauth deep-link from a pair card on `PairsScreen` (Phase 5d).

**Dismissal:** `ReauthNotificationHelper.cancelReauthNotification(context, accountId)` is called from `AccountsViewModel.handleResult` when a sign-in succeeds (`AuthResult.Success`).  The group summary is automatically removed once all individual alerts have been cancelled.

---

### 3. `sync_status` — Sync results

| Property      | Value                                                   |
|:--------------|:--------------------------------------------------------|
| Constant      | `SyncStatusNotifier.SYNC_STATUS_CHANNEL_ID`             |
| Importance    | `IMPORTANCE_DEFAULT`                                    |
| Badge         | Enabled                                                 |
| Registered in | `SynckroApp.createNotificationChannels()`               |

**Purpose:** Sync result notifications for terminal failures and opt-in grouped success summaries after background runs.

**Current behaviour:** `SyncStatusNotifier.notifyFailure(...)` posts a notification for the affected pair on terminal retry exhaustion, reusing the pair id as the notification id so later failures replace earlier ones. When `SettingsRepository.notifyOnSuccess` is enabled, `SyncWorker` also forwards non-empty periodic successes to `SyncStatusNotifier.notifySuccessSummary(...)`; a short in-memory aggregation window collapses burst completions into one grouped `InboxStyle` summary (`id = 0`) plus one child notification per pair (`id = pairId`). No-op runs do not post success notifications.

---

## Notification ID Ranges

| Range               | Owner                          | Purpose                                   |
|:--------------------|:-------------------------------|:------------------------------------------|
| 1,000 – 66,535      | `SyncWorker`                   | Per-pair sync-progress foreground service |
| 99,999              | `ReauthNotificationHelper`     | Reauth group summary                      |
| 100,000 – 104,095   | `ReauthNotificationHelper`     | Per-account reauth alerts (`MAX_ACCOUNTS = 4,096`) |

`SyncStatusNotifier` does not allocate notification IDs yet because Phase 8a only registers the channel; reserve a non-overlapping range when actual posts are added in later phases.

---

## Checklist for Adding a New Notification Type

1. **Define a channel** in `SynckroApp.createNotificationChannels()` with the appropriate importance.
2. **Choose the right importance:**
   - `IMPORTANCE_LOW` — silent progress / informational (no heads-up, no sound).
   - `IMPORTANCE_DEFAULT` — default sound, shown in drawer (user can configure).
   - `IMPORTANCE_HIGH` — heads-up banner + sound; use only for critical, actionable alerts.
3. **Assign a unique ID range** (update the table above).
4. **Localise all text** in `strings.xml`; never hard-code English strings in notification builders.
5. **Add a meaningful action** (PendingIntent) on every critical notification.
6. **Cancel the notification** when the underlying condition is resolved.
7. **Write unit tests** using Robolectric to verify post/cancel behaviour (see `ReauthNotificationHelperTest`).
8. **Document the new type** in this file.

---

## Runtime Permission (`POST_NOTIFICATIONS`)

On Android 13+ (API 33, `TIRAMISU`) posting notifications requires the user to explicitly grant `POST_NOTIFICATIONS`.  All notification helpers must call `SyncWorker.canPostNotifications(context)` before calling `NotificationManager.notify()` and silently skip the post when the permission is not granted.

The permission is declared in `AndroidManifest.xml` (`<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`).  The app does not currently show a rationale dialog before requesting it; if this changes, update `MainActivity` and document it here.
