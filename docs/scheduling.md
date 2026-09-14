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
5. [Instant Sync: best-effort dispatch](#5-instant-sync-best-effort-dispatch)
6. [Manual sync](#6-manual-sync)
7. [Exponential backoff on failure](#7-exponential-backoff-on-failure)
8. [Rollout gates and device matrix](#8-rollout-gates-and-device-matrix)
9. [Checking scheduled jobs](#9-checking-scheduled-jobs)

---

## 1. Per-pair schedule

Each sync pair carries its own schedule configuration:

| Field | Default | Description |
|:------|:--------|:------------|
| **Auto sync enabled** | `true` | Whether periodic background sync is active for this pair. When disabled, only manual "Sync now" triggers a run. |
| **Schedule interval** | 60 min | How often WorkManager enqueues a new sync run for this pair. Subject to WorkManager's 15-minute minimum. |
| **Wi-Fi only** | `true` | Sync only when the device is connected to an unmetered (non-cellular) network. |
| **Requires charging** | `false` | Sync only when the device is charging. |

When you tap **Save** in the Pair Editor, the existing WorkManager periodic job
is cancelled and a replacement job with the new interval and constraints is
enqueued. Its start time remains under WorkManager and platform control.

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

When a constraint is not satisfied at the nominal run time, WorkManager keeps
the job eligible and may run it after the constraint is met. Doze, App Standby,
OEM battery controls, and system load may add further delay.

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

## 5. Instant Sync: best-effort dispatch

Where enabled for a rollout cohort, Instant Sync is an **opt-in**, upload-only
acceleration path for completed local changes. Both **Settings → Sync → Instant
Sync** and the pair's own **Instant Sync** control must be enabled; both default
to off. If these controls are absent, the feature is not available in that
build. The global background-sync master must also permit background work. A
pair must be linked, have a usable account, use an upload-capable direction, and
pass its filters.

Enabling Instant Sync may keep a low-importance foreground notification visible
while local folders are being watched. This has a battery cost. See
[docs/notifications.md](notifications.md) for notification and permission
behavior.

Instant Sync is not a real-time or guaranteed-latency service:

- SAF `ContentObserver`, optional `FileObserver`, and `MediaStore` notifications
  are best-effort hints. A DocumentsProvider may coalesce, delay, or omit them,
  especially for removable storage or after a process/device restart.
- A hint only queues a candidate. Stability checks must establish that the file
  is complete before any upload starts.
- Each pair dispatches at most one instant request per 60 seconds. Signals that
  arrive inside that window are delayed to the end of it, never dropped, and the
  window is persisted so a restart cannot dispatch more often. Pairs are rate
  limited independently.
- Dispatch uses constrained WorkManager work. Expedited quota exhaustion falls
  back to ordinary non-expedited work; Doze, standby buckets, constraints, and
  OEM policy can defer either form.
- The regular periodic full sync remains the reconciliation and correctness
  fallback for provider silence, missed events, and interrupted instant work.

Use the global Instant Sync switch as the kill switch. Turning it off unregisters
watchers and cancels only instant WorkManager requests; periodic schedules and
manual sync remain available. Durable queued candidates are retained so that
re-enabling Instant Sync can retry them and periodic reconciliation can discover
the same changes. Deleting a pair removes its queue as part of pair cleanup.

---

## 6. Manual sync

Request an on-demand sync independently of the periodic schedule:

- **Home screen / Pairs tab**: tap **Sync now** on a pair card.
- **Pair Detail screen**: tap **Sync now** in the action bar.

The manual request uses a `OneTimeWorkRequest` with the same constraints as
the periodic job **plus** exponential backoff (initial delay 30 s), allowing a
transient failure to be retried. The periodic schedule is unaffected.

The **Sync now** button is disabled while a sync for that pair is already
running. If the button is blocked for another reason (account needs sign-in,
SAF permission lost), the Home screen shows a descriptive snackbar.

---

## 7. Exponential backoff on failure

When a sync run encounters a retriable error (network timeout, transient
server error, temporary authentication failure), it requests a retry using
exponential backoff. The eventual run time remains under WorkManager and
platform control:

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

## 8. Rollout gates and device matrix

Instant Sync must remain default-off while it moves through internal testing,
opt-in beta, and broader opt-in availability. Expand a stage only when:

- all stability, duplicate-event, stale-claim/process-death, exactly-once queue
  completion, and unchanged periodic-checkpoint tests pass;
- the device matrix below has no known data-loss, partial-write, foreground
  service, boot/restart, or permission regressions;
- crash/ANR and battery impact remain within the release's agreed baseline; and
- queued-candidate age/depth, retry exhaustion, foreground-service start
  failures, expedited-quota fallback, provider-silence fallback, and targeted
  outcomes show no candidate remains after two successful periodic
  reconciliation cycles unless it has a recorded terminal cause.

Treat any suspected data loss, committed partial file, checkpoint corruption, or
queue that cannot be reconciled as a stop-ship condition. Use the global switch
to roll back Instant Sync without disabling periodic sync. Metrics and sync-log
events must contain only bucketed durations/counts and categorical API,
provider, capability, and outcome values — never file names, paths, account IDs,
or file content.

Before each rollout expansion, validate every API level from 26 through the
current supported Android release (individual devices or representative bands
may be used):

| API coverage | Local provider/storage | Remote provider | Required scenarios |
|:-------------|:-----------------------|:----------------|:-------------------|
| 26–28 | AOSP DocumentsUI, internal storage | Fake provider, then Google Drive and OneDrive | Watch registration, completed-file upload, duplicate/coarse event, process restart |
| 29–30 | DocumentsUI/Files, internal and removable SD | Google Drive and OneDrive | `MediaStore`/SAF fallback, card removal/reinsert, permission revoke/re-link, reboot restore |
| 31–32 | Google Files and Samsung My Files (or another representative OEM DocumentsProvider) | Google Drive and OneDrive | Background foreground-service restrictions, Doze/App Standby, expedited quota fallback |
| 33–34 | Internal and removable storage | Google Drive and OneDrive | Notification allowed/denied, foreground notification visibility, battery restrictions |
| 35–current | Google and representative OEM devices/providers | Google Drive and OneDrive | Current foreground-service/boot policy, kill switch, long-running transfer, periodic reconciliation after provider silence |

For every row, also exercise growing/temp files, an open/read failure, mutation
during upload, offline recovery, quota exhaustion, pair edit/delete, and both
global and per-pair disable paths.

---

## 9. Checking scheduled jobs

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
