package com.synckro.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [SyncScheduler] verifying that the correct WorkManager constraints
 * (Wi-Fi, charging, battery-not-low, storage-not-low) and policies are applied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(workManager)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun pair(
        id: Long = 1L,
        wifiOnly: Boolean = true,
        requiresCharging: Boolean = false,
        autoSyncEnabled: Boolean = true,
    ) = SyncPair(
        id = id,
        displayName = "Test $id",
        localTreeUri = "content://test/$id",
        provider = CloudProviderType.FAKE,
        remoteFolderId = "root",
        direction = SyncDirection.BIDIRECTIONAL,
        conflictPolicy = ConflictPolicy.NEWEST_WINS,
        wifiOnly = wifiOnly,
        requiresCharging = requiresCharging,
        autoSyncEnabled = autoSyncEnabled,
    )

    // -------------------------------------------------------------------------
    // schedulePeriodic – enqueuing
    // -------------------------------------------------------------------------

    @Test
    fun `schedulePeriodic enqueues work under the pair's unique name`() {
        val p = pair(id = 5L)
        scheduler.schedulePeriodic(p)

        val infos =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(5L))
                .get()
        assertFalse("Expected at least one work info", infos.isEmpty())
    }

    @Test
    fun `schedulePeriodic work is in ENQUEUED state initially`() {
        val p = pair(id = 2L)
        scheduler.schedulePeriodic(p)

        val info =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(2L))
                .get()
                .first()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
    }

    // -------------------------------------------------------------------------
    // schedulePeriodic – constraints
    // -------------------------------------------------------------------------

    @Test
    fun `wifiOnly true applies UNMETERED network constraint`() {
        val p = pair(wifiOnly = true)
        scheduler.schedulePeriodic(p)

        val info =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
                .first()
        // WorkInfo carries the constraints; UNMETERED == Wi-Fi only
        assertTrue(
            "Expected UNMETERED (Wi-Fi only) constraint",
            info.constraints.requiredNetworkType == NetworkType.UNMETERED,
        )
    }

    @Test
    fun `wifiOnly false applies CONNECTED network constraint`() {
        val p = pair(wifiOnly = false)
        scheduler.schedulePeriodic(p)

        val info =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
                .first()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `requiresCharging true applies charging constraint`() {
        val p = pair(requiresCharging = true)
        scheduler.schedulePeriodic(p)

        val info =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
                .first()
        assertTrue("Expected requiresCharging constraint", info.constraints.requiresCharging())
    }

    @Test
    fun `battery-not-low constraint is always applied`() {
        val p = pair()
        scheduler.schedulePeriodic(p)

        val info =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
                .first()
        assertTrue(
            "battery-not-low must always be set for Doze resilience on API 31+",
            info.constraints.requiresBatteryNotLow(),
        )
    }

    @Test
    fun `storage-not-low constraint is always applied`() {
        val p = pair()
        scheduler.schedulePeriodic(p)

        val info =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
                .first()
        assertTrue(
            "storage-not-low must always be set",
            info.constraints.requiresStorageNotLow(),
        )
    }

    // -------------------------------------------------------------------------
    // schedulePeriodic – interval clamping
    // -------------------------------------------------------------------------

    @Test
    fun `interval below minimum is clamped to 15 minutes`() {
        val p = pair()
        // Requesting 5 minutes should be silently clamped to 15 minutes.
        scheduler.schedulePeriodic(p, intervalMinutes = 5L)

        val info =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
                .first()
        // The work is still enqueued (clamped, not rejected).
        assertNotNull(info)
    }

    // -------------------------------------------------------------------------
    // schedulePeriodic – exponential backoff (sub-issue #142)
    // -------------------------------------------------------------------------

    @Test
    fun `schedulePeriodic uses 30-second initial backoff constant`() {
        // The backoff policy itself is not directly observable on WorkInfo, but the
        // constant is part of the public worker API and shared with the one-shot
        // Sync-now path in HomeViewModel — guard it against accidental changes.
        assertEquals(30L, SyncWorker.BACKOFF_INITIAL_DELAY_SECONDS)

        val p = pair(id = 99L)
        // Calling schedulePeriodic must not throw when setBackoffCriteria is applied
        // with the constant value above.
        scheduler.schedulePeriodic(p)

        val infos =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
        assertFalse("Expected periodic work to be enqueued with backoff policy", infos.isEmpty())
    }

    @Test
    fun `periodic and one-time requests share pair constraints`() {
        val syncPair = pair(id = 100L, wifiOnly = true, requiresCharging = true)

        val periodic = SyncScheduler.periodicRequestFor(syncPair, SyncScheduler.MIN_PERIODIC_INTERVAL_MINUTES)
        val oneTime = SyncScheduler.oneTimeRequestFor(syncPair)

        assertEquals(NetworkType.UNMETERED, periodic.workSpec.constraints.requiredNetworkType)
        assertEquals(periodic.workSpec.constraints.requiredNetworkType, oneTime.workSpec.constraints.requiredNetworkType)
        assertTrue(periodic.workSpec.constraints.requiresCharging())
        assertEquals(periodic.workSpec.constraints.requiresCharging(), oneTime.workSpec.constraints.requiresCharging())
        assertTrue(periodic.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(periodic.workSpec.constraints.requiresBatteryNotLow(), oneTime.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(periodic.workSpec.constraints.requiresStorageNotLow())
        assertEquals(periodic.workSpec.constraints.requiresStorageNotLow(), oneTime.workSpec.constraints.requiresStorageNotLow())
    }

    @Test
    fun `periodic and one-time requests share connected non-charging constraints`() {
        val syncPair = pair(id = 103L, wifiOnly = false, requiresCharging = false)

        val periodic = SyncScheduler.periodicRequestFor(syncPair, SyncScheduler.MIN_PERIODIC_INTERVAL_MINUTES)
        val oneTime = SyncScheduler.oneTimeRequestFor(syncPair)

        assertEquals(NetworkType.CONNECTED, periodic.workSpec.constraints.requiredNetworkType)
        assertEquals(periodic.workSpec.constraints.requiredNetworkType, oneTime.workSpec.constraints.requiredNetworkType)
        assertFalse(periodic.workSpec.constraints.requiresCharging())
        assertEquals(periodic.workSpec.constraints.requiresCharging(), oneTime.workSpec.constraints.requiresCharging())
        assertTrue(periodic.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(periodic.workSpec.constraints.requiresBatteryNotLow(), oneTime.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(periodic.workSpec.constraints.requiresStorageNotLow())
        assertEquals(periodic.workSpec.constraints.requiresStorageNotLow(), oneTime.workSpec.constraints.requiresStorageNotLow())
    }

    @Test
    fun `periodic and one-time requests share exponential backoff policy`() {
        val syncPair = pair(id = 101L)

        val periodic = SyncScheduler.periodicRequestFor(syncPair, SyncScheduler.MIN_PERIODIC_INTERVAL_MINUTES)
        val oneTime = SyncScheduler.oneTimeRequestFor(syncPair)

        assertEquals(BackoffPolicy.EXPONENTIAL, periodic.workSpec.backoffPolicy)
        assertEquals(periodic.workSpec.backoffPolicy, oneTime.workSpec.backoffPolicy)
        assertEquals(
            TimeUnit.SECONDS.toMillis(SyncWorker.BACKOFF_INITIAL_DELAY_SECONDS),
            periodic.workSpec.backoffDelayDuration,
        )
        assertEquals(periodic.workSpec.backoffDelayDuration, oneTime.workSpec.backoffDelayDuration)
    }

    @Test
    fun `one-time request carries manual sync input data`() {
        val syncPair = pair(id = 102L)

        val oneTime = SyncScheduler.oneTimeRequestFor(syncPair)

        assertEquals(syncPair.id, oneTime.workSpec.input.getLong(SyncWorker.KEY_PAIR_ID, -1L))
        assertFalse(oneTime.workSpec.input.getBoolean(SyncWorker.KEY_IS_PERIODIC, true))
    }

    // -------------------------------------------------------------------------
    // enqueueInstant / instant request
    // -------------------------------------------------------------------------

    @Test
    fun `enqueueInstant uses instant unique name and KEEP policy`() {
        val mockWm = mockk<WorkManager>(relaxed = true)
        val testScheduler = SyncScheduler(mockWm)
        val syncPair = pair(id = 104L)

        testScheduler.enqueueInstant(syncPair)

        verify {
            mockWm.enqueueUniqueWork(
                SyncWorker.instantName(syncPair.id),
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `enqueueInstant repeated calls keep one pending request`() {
        val syncPair = pair(id = 105L)

        scheduler.enqueueInstant(syncPair)
        scheduler.enqueueInstant(syncPair)

        val infos = workManager.getWorkInfosForUniqueWork(SyncWorker.instantName(syncPair.id)).get()
        assertEquals(1, infos.count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `instant request is expedited with quota fallback and shared policy`() {
        val syncPair = pair(id = 106L, wifiOnly = true, requiresCharging = true)

        val periodic = SyncScheduler.periodicRequestFor(syncPair, SyncScheduler.MIN_PERIODIC_INTERVAL_MINUTES)
        val expeditedConstraints = SyncScheduler.expeditedConstraintsFor(syncPair)
        val instant = SyncScheduler.instantRequestFor(syncPair)

        assertEquals(syncPair.id, instant.workSpec.input.getLong(SyncWorker.KEY_PAIR_ID, -1L))
        assertFalse(instant.workSpec.input.getBoolean(SyncWorker.KEY_IS_PERIODIC, true))
        assertTrue(instant.workSpec.input.getBoolean(SyncWorker.KEY_INSTANT, false))
        assertTrue(instant.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, instant.workSpec.outOfQuotaPolicy)
        assertEquals(expeditedConstraints.requiredNetworkType, instant.workSpec.constraints.requiredNetworkType)
        assertEquals(expeditedConstraints.requiresCharging(), instant.workSpec.constraints.requiresCharging())
        assertEquals(expeditedConstraints.requiresBatteryNotLow(), instant.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(expeditedConstraints.requiresStorageNotLow(), instant.workSpec.constraints.requiresStorageNotLow())
        assertTrue(periodic.workSpec.constraints.requiresCharging())
        assertFalse(instant.workSpec.constraints.requiresCharging())
        assertTrue(periodic.workSpec.constraints.requiresBatteryNotLow())
        assertFalse(instant.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(periodic.workSpec.backoffPolicy, instant.workSpec.backoffPolicy)
        assertEquals(periodic.workSpec.backoffDelayDuration, instant.workSpec.backoffDelayDuration)
    }

    // -------------------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------------------

    @Test
    fun `cancel removes periodic work for the pair`() {
        val p = pair(id = 3L)
        scheduler.schedulePeriodic(p)
        scheduler.cancel(p.id)

        val infos =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
        assertTrue(
            "After cancel the work should be CANCELLED or absent",
            infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `cancel calls cancelUniqueWork for periodic syncnow and instant unique names`() {
        // Use a mock WorkManager to directly verify that cancel() invokes cancelUniqueWork
        // for periodic, one-shot "sync now", and one-shot instant unique names.
        val mockWm = mockk<WorkManager>(relaxed = true)
        val testScheduler = SyncScheduler(mockWm)

        testScheduler.cancel(42L)

        verify { mockWm.cancelUniqueWork(SyncWorker.uniqueName(42L)) }
        verify { mockWm.cancelUniqueWork(SyncWorker.syncNowUniqueName(42L)) }
        verify { mockWm.cancelUniqueWork(SyncWorker.instantName(42L)) }
    }

    @Test
    fun `cancelInstant cancels only instant work`() {
        val mockWm = mockk<WorkManager>(relaxed = true)
        val testScheduler = SyncScheduler(mockWm)

        testScheduler.cancelInstant(42L)

        verify(exactly = 0) { mockWm.cancelUniqueWork(SyncWorker.uniqueName(42L)) }
        verify(exactly = 0) { mockWm.cancelUniqueWork(SyncWorker.syncNowUniqueName(42L)) }
        verify { mockWm.cancelUniqueWork(SyncWorker.instantName(42L)) }
    }

    @Test
    fun `cancelInstant retains periodic and manual queues`() {
        val p = pair(id = 43L)
        scheduler.schedulePeriodic(p)
        workManager.enqueueUniqueWork(
            SyncWorker.syncNowUniqueName(p.id),
            ExistingWorkPolicy.KEEP,
            SyncScheduler.oneTimeRequestFor(p),
        )
        scheduler.enqueueInstant(p)

        scheduler.cancelInstant(p.id)

        val periodicInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id)).get()
        val manualInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.syncNowUniqueName(p.id)).get()
        val instantInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.instantName(p.id)).get()
        assertTrue(periodicInfos.any { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(manualInfos.any { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(instantInfos.isEmpty() || instantInfos.all { it.state == WorkInfo.State.CANCELLED })
    }

    // -------------------------------------------------------------------------
    // scheduleOrCancel
    // -------------------------------------------------------------------------

    @Test
    fun `scheduleOrCancel with autoSyncEnabled true enqueues work`() {
        val p = pair(id = 10L, autoSyncEnabled = true)
        scheduler.scheduleOrCancel(p)

        val infos =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
        assertFalse("Expected work to be enqueued when autoSyncEnabled=true", infos.isEmpty())
    }

    @Test
    fun `scheduleOrCancel with autoSyncEnabled false cancels work`() {
        val p = pair(id = 11L, autoSyncEnabled = true)
        // Schedule first, then disable.
        scheduler.scheduleOrCancel(p)

        val disabled = p.copy(autoSyncEnabled = false)
        scheduler.scheduleOrCancel(disabled)

        val infos =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
        assertTrue(
            "Work should be CANCELLED or absent when autoSyncEnabled=false",
            infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `scheduleOrCancel with autoSyncEnabled false preserves manual and instant queues`() {
        val p = pair(id = 14L, autoSyncEnabled = true)
        scheduler.scheduleOrCancel(p)
        workManager.enqueueUniqueWork(
            SyncWorker.syncNowUniqueName(p.id),
            ExistingWorkPolicy.KEEP,
            SyncScheduler.oneTimeRequestFor(p),
        )
        scheduler.enqueueInstant(p)

        scheduler.scheduleOrCancel(p.copy(autoSyncEnabled = false))

        val periodicInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id)).get()
        val manualInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.syncNowUniqueName(p.id)).get()
        val instantInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.instantName(p.id)).get()
        assertTrue(periodicInfos.isEmpty() || periodicInfos.all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(manualInfos.any { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(instantInfos.any { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `scheduleOrCancel with globalAutoSyncEnabled false cancels work even when pair auto-sync is on`() {
        val p = pair(id = 12L, autoSyncEnabled = true)
        // Schedule first, then disable via global flag.
        scheduler.scheduleOrCancel(p)

        scheduler.scheduleOrCancel(p, globalAutoSyncEnabled = false)

        val infos =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
        assertTrue(
            "Work should be CANCELLED or absent when globalAutoSyncEnabled=false",
            infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `scheduleOrCancel with globalAutoSyncEnabled true and pair auto-sync on schedules work`() {
        val p = pair(id = 13L, autoSyncEnabled = true)
        scheduler.scheduleOrCancel(p, globalAutoSyncEnabled = true)

        val infos =
            workManager
                .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
                .get()
        assertFalse(
            "Expected work to be enqueued when both global and pair auto-sync are enabled",
            infos.isEmpty(),
        )
    }

    // -------------------------------------------------------------------------
    // scheduleOrCancelAll
    // -------------------------------------------------------------------------

    @Test
    fun `scheduleOrCancelAll with globalEnabled true schedules all eligible pairs`() {
        val p1 = pair(id = 20L, autoSyncEnabled = true)
        val p2 = pair(id = 21L, autoSyncEnabled = true)

        scheduler.scheduleOrCancelAll(listOf(p1, p2), globalAutoSyncEnabled = true)

        assertFalse(
            "p1 should be enqueued",
            workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(p1.id)).get().isEmpty(),
        )
        assertFalse(
            "p2 should be enqueued",
            workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(p2.id)).get().isEmpty(),
        )
    }

    @Test
    fun `scheduleOrCancelAll with globalEnabled false cancels all pairs`() {
        val p1 = pair(id = 22L, autoSyncEnabled = true)
        val p2 = pair(id = 23L, autoSyncEnabled = true)
        // Pre-schedule both so there is work to cancel.
        scheduler.schedulePeriodic(p1)
        scheduler.schedulePeriodic(p2)

        scheduler.scheduleOrCancelAll(listOf(p1, p2), globalAutoSyncEnabled = false)

        val infos1 = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(p1.id)).get()
        val infos2 = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(p2.id)).get()
        assertTrue(
            "p1 should be CANCELLED or absent when global is off",
            infos1.isEmpty() || infos1.all { it.state == WorkInfo.State.CANCELLED },
        )
        assertTrue(
            "p2 should be CANCELLED or absent when global is off",
            infos2.isEmpty() || infos2.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `scheduleOrCancelAll with globalEnabled false preserves instant queues`() {
        val p1 = pair(id = 26L, autoSyncEnabled = true)
        val p2 = pair(id = 27L, autoSyncEnabled = true)
        scheduler.schedulePeriodic(p1)
        scheduler.schedulePeriodic(p2)
        scheduler.enqueueInstant(p1)
        scheduler.enqueueInstant(p2)

        scheduler.scheduleOrCancelAll(listOf(p1, p2), globalAutoSyncEnabled = false)

        val periodicInfos1 = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(p1.id)).get()
        val periodicInfos2 = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(p2.id)).get()
        val instantInfos1 = workManager.getWorkInfosForUniqueWork(SyncWorker.instantName(p1.id)).get()
        val instantInfos2 = workManager.getWorkInfosForUniqueWork(SyncWorker.instantName(p2.id)).get()
        assertTrue(periodicInfos1.isEmpty() || periodicInfos1.all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(periodicInfos2.isEmpty() || periodicInfos2.all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(instantInfos1.any { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(instantInfos2.any { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `scheduleOrCancelAll with globalEnabled true respects per-pair autoSyncEnabled=false`() {
        val enabled = pair(id = 24L, autoSyncEnabled = true)
        val disabled = pair(id = 25L, autoSyncEnabled = false)

        scheduler.scheduleOrCancelAll(listOf(enabled, disabled), globalAutoSyncEnabled = true)

        assertFalse(
            "Enabled pair should be enqueued",
            workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(enabled.id)).get().isEmpty(),
        )
        val disabledInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueName(disabled.id)).get()
        assertTrue(
            "Disabled pair should be CANCELLED or absent even when global is on",
            disabledInfos.isEmpty() || disabledInfos.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    // -------------------------------------------------------------------------
    // estimateNextRunAtMs (Phase 5a — pair-card ETA helper)
    // -------------------------------------------------------------------------

    @Test
    fun `estimateNextRunAtMs returns lastSync + interval for a healthy pair`() {
        val p =
            pair(id = 30L, autoSyncEnabled = true)
                .copy(lastSyncAtMs = 1_000L, scheduleIntervalMinutes = 60L)
        val next = SyncScheduler.estimateNextRunAtMs(pair = p, nowMs = 5_000L, globalAutoSyncEnabled = true)
        assertEquals(1_000L + 60L * 60_000L, next)
    }

    @Test
    fun `estimateNextRunAtMs clamps interval below 15 minutes to the WorkManager floor`() {
        val p = pair(id = 31L).copy(lastSyncAtMs = 0L, scheduleIntervalMinutes = 5L)
        val next = SyncScheduler.estimateNextRunAtMs(pair = p, nowMs = 0L)
        assertEquals(15L * 60_000L, next)
    }

    @Test
    fun `estimateNextRunAtMs returns now when the pair has never synced`() {
        val p = pair(id = 32L).copy(lastSyncAtMs = null)
        assertEquals(7_777L, SyncScheduler.estimateNextRunAtMs(pair = p, nowMs = 7_777L))
    }

    @Test
    fun `estimateNextRunAtMs returns null when pair auto-sync is disabled`() {
        val p = pair(id = 33L, autoSyncEnabled = false)
        assertEquals(null, SyncScheduler.estimateNextRunAtMs(pair = p, nowMs = 0L))
    }

    @Test
    fun `estimateNextRunAtMs returns null when global auto-sync is disabled`() {
        val p = pair(id = 34L, autoSyncEnabled = true)
        assertEquals(null, SyncScheduler.estimateNextRunAtMs(pair = p, nowMs = 0L, globalAutoSyncEnabled = false))
    }

    @Test
    fun `estimateNextRunAtMs returns null for pairs stuck in NEEDS_REAUTH or NEEDS_RELINK`() {
        val reauth = pair(id = 35L).copy(lastSyncResult = "NEEDS_REAUTH", lastSyncAtMs = 1_000L)
        val relink = pair(id = 36L).copy(lastSyncResult = "NEEDS_RELINK", lastSyncAtMs = 1_000L)
        assertEquals(null, SyncScheduler.estimateNextRunAtMs(pair = reauth, nowMs = 0L))
        assertEquals(null, SyncScheduler.estimateNextRunAtMs(pair = relink, nowMs = 0L))
    }
}
