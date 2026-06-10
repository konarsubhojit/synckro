# Scheduling Syncs

Synckro runs background syncs using Android WorkManager. Each sync pair has its
own independent schedule; a global setting in **Settings → Sync** controls
default behaviour that applies to all pairs.

> See **[docs/sync-pairs.md](sync-pairs.md)** for the full guide on creating
> and editing pairs, including where to find the schedule controls in the Pair
> Editor.

---

## Table of contents

1. [Per-pair schedule](#1-per-pair-schedule)
2. [Schedule presets](#2-schedule-presets)
3. [Network and power constraints](#3-network-and-power-constraints)
4. [Global auto-sync setting](#4-global-auto-sync-setting)
5. [Manual sync](#5-manual-sync)
6. [Exponential backoff on failure](#6-exponential-backoff-on-failure)
7. [Checking scheduled jobs](#7-checking-scheduled-jobs)

---

## 1. Per-pair schedule

Each sync pair carries its own schedule configuration:

| Field | Default | Description |
|:------|:--------|:------------|
| **Auto sync enabled** | `true` | Whether periodic background sync is active for this pair. When disabled, only manual "Sync now" triggers a run. |
| **Schedule interval** | 60 min | How often WorkManager enqueues a new sync run for this pair. Subject to WorkManager's 15-minute minimum. |
| **Wi-Fi only** | `true` | Sync only when the device is connected to an unmetered (non-cellular) network. |
| **Requires charging** | `false` | Sync only when the device is charging. |

Schedule settings are applied immediately when you tap **Save** in the Pair
Editor: the existing WorkManager periodic job is cancelled and a replacement
job with the new interval and constraints is enqueued.

---

## 2. Schedule presets

The Pair Editor offers named presets to simplify common choices:

| Preset | Interval |
|:-------|:---------|
| Every 15 minutes | 15 min (WorkManager minimum floor) |
| Every 30 minutes | 30 min |
| Every hour | 60 min (default) |
| Every day | 1 440 min |
| Custom | Enter any value ≥ 15 minutes |

> **Android Doze and App Standby:** WorkManager respects Doze mode and App
> Standby buckets. A 15-minute interval does not guarantee a run every 15 minutes
> when the device is idle — Android may defer the job. This is expected behaviour
> and not a bug. Use **Sync now** for time-critical transfers.

---

## 3. Network and power constraints

WorkManager passes the pair's `wifiOnly` and `requiresCharging` flags as
`Constraints` when building the `PeriodicWorkRequest`:

```kotlin
Constraints.Builder()
    .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .setRequiresCharging(pair.requiresCharging)
    .build()
```

When the constraint is not satisfied at the scheduled time, WorkManager holds
the job until the constraint is met, then runs it.

**Recommended defaults:**

- Keep **Wi-Fi only** enabled to avoid using mobile data for large transfers.
- Enable **Charging only** for pairs that sync large folders (photos, videos) so
  background transfers don't drain the battery.

---

## 4. Global auto-sync setting

**Settings → Sync → Auto sync schedule** provides a global on/off toggle and a
default interval that is pre-filled when creating a new pair. Changes to the
global setting do **not** retroactively update existing pairs' individual
schedules — edit each pair separately if you want to change its schedule.

When the global auto-sync toggle is turned off, *all* pairs' periodic jobs are
paused; turning it back on re-enqueues each pair's own job at its individual
interval.

Additional global sync settings:

| Setting | Description |
|:--------|:------------|
| **Max concurrent transfers** | Number of file uploads/downloads that may run in parallel within a single sync run (default: 1 = sequential). Higher values speed up large folders at the cost of more bandwidth and battery. |
| **Notify on success** | Post a notification in the `sync_status` channel after a background sync completes with at least one transferred file. Off by default. See [docs/notifications.md](notifications.md). |

---

## 5. Manual sync

Trigger an immediate, on-demand sync independently of the schedule:

- **Home screen / Pairs tab**: tap **Sync now** on a pair card.
- **Pair Detail screen**: tap **Sync now** in the action bar.

The manual request uses a `OneTimeWorkRequest` with the same constraints as
the periodic job **plus** exponential backoff (initial delay 30 s) so a
transient failure retries automatically. The periodic schedule is unaffected.

The **Sync now** button is disabled while a sync for that pair is already
running. If the button is blocked for another reason (account needs sign-in,
SAF permission lost), the Home screen shows a descriptive snackbar.

---

## 6. Exponential backoff on failure

When a sync run encounters a retriable error (network timeout, transient
server error, temporary authentication failure), WorkManager automatically
schedules a retry using exponential backoff:

- **Policy**: `BackoffPolicy.EXPONENTIAL`
- **Initial delay**: 30 seconds
- **Maximum delay**: governed by WorkManager's internal ceiling (approximately
  5 hours for `EXPONENTIAL`).

Non-retriable (terminal) errors — such as a permanently revoked token or a
deleted remote folder — do **not** trigger automatic retries. The pair is
marked as failed and, if authentication is the issue, a re-authentication
notification is posted. See **[docs/error-recovery.md](error-recovery.md)**
for the full recovery workflow.

---

## 7. Checking scheduled jobs

To inspect WorkManager jobs during development, use the
[WorkManager Inspector](https://developer.android.com/studio/inspect/task)
in Android Studio's App Inspection pane:

1. Connect a device or emulator running the debug APK.
2. In Android Studio, open **View → Tool Windows → App Inspection**.
3. Select the **WorkManager** tab.
4. Observe enqueued, running, and completed work requests, their tags, states,
   and constraints.

Each periodic sync job is tagged `sync_pair_<pairId>` so you can identify
which pair a job belongs to.
