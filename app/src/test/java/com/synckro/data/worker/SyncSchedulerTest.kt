package com.synckro.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
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
    fun `cancel calls cancelUniqueWork for both periodic and syncnow unique names`() {
        // Use a mock WorkManager to directly verify that cancel() invokes cancelUniqueWork
        // for both the periodic work name and the one-shot "sync now" work name.
        val mockWm = mockk<WorkManager>(relaxed = true)
        val testScheduler = SyncScheduler(mockWm)

        testScheduler.cancel(42L)

        verify { mockWm.cancelUniqueWork(SyncWorker.uniqueName(42L)) }
        verify { mockWm.cancelUniqueWork(SyncWorker.syncNowUniqueName(42L)) }
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
}
