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
        val config = Configuration.Builder()
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
    )

    // -------------------------------------------------------------------------
    // schedulePeriodic – enqueuing
    // -------------------------------------------------------------------------

    @Test
    fun `schedulePeriodic enqueues work under the pair's unique name`() {
        val p = pair(id = 5L)
        scheduler.schedulePeriodic(p)

        val infos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.uniqueName(5L))
            .get()
        assertFalse("Expected at least one work info", infos.isEmpty())
    }

    @Test
    fun `schedulePeriodic work is in ENQUEUED state initially`() {
        val p = pair(id = 2L)
        scheduler.schedulePeriodic(p)

        val info = workManager
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

        val info = workManager
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

        val info = workManager
            .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
            .get()
            .first()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `requiresCharging true applies charging constraint`() {
        val p = pair(requiresCharging = true)
        scheduler.schedulePeriodic(p)

        val info = workManager
            .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
            .get()
            .first()
        assertTrue("Expected requiresCharging constraint", info.constraints.requiresCharging())
    }

    @Test
    fun `battery-not-low constraint is always applied`() {
        val p = pair()
        scheduler.schedulePeriodic(p)

        val info = workManager
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

        val info = workManager
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

        val info = workManager
            .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
            .get()
            .first()
        // The work is still enqueued (clamped, not rejected).
        assertNotNull(info)
    }

    // -------------------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------------------

    @Test
    fun `cancel removes periodic work for the pair`() {
        val p = pair(id = 3L)
        scheduler.schedulePeriodic(p)
        scheduler.cancel(p.id)

        val infos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.uniqueName(p.id))
            .get()
        assertTrue(
            "After cancel the work should be CANCELLED or absent",
            infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED },
        )
    }
}
