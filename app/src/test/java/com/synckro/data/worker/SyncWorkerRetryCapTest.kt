package com.synckro.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.sync.SyncEngine
import com.synckro.util.notification.SyncStatusNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the [SyncWorker.MAX_RETRY_ATTEMPTS] retry-cap mechanism.
 *
 * The production logic checks `runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS` to decide
 * whether to escalate a [com.synckro.domain.sync.SyncEngine.Result.Retriable] result
 * to a terminal failure instead of scheduling another retry via WorkManager.
 *
 * These tests verify:
 * - The constant is defined and has the expected value.
 * - The cap predicate (`runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS`) correctly classifies
 *   each attempt number as "should retry" or "should escalate".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncWorkerRetryCapTest {
    private val cap = SyncWorker.MAX_RETRY_ATTEMPTS
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runCatching { WorkManager.getInstance(context) }
            .onFailure {
                WorkManager.initialize(
                    context,
                    Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build(),
                )
            }
    }

    @Test
    fun `MAX_RETRY_ATTEMPTS is positive`() {
        assertTrue("MAX_RETRY_ATTEMPTS must be positive, was $cap", cap > 0)
    }

    @Test
    fun `MAX_RETRY_ATTEMPTS equals expected value`() {
        assertEquals(
            "MAX_RETRY_ATTEMPTS should be 5 (5 total attempts before escalation)",
            5,
            cap,
        )
    }

    @Test
    fun `first attempt should not escalate`() {
        val runAttemptCount = 0 // WorkManager first call
        val shouldEscalate = runAttemptCount + 1 >= cap
        assertFalse("First attempt (runAttemptCount=0) must NOT escalate", shouldEscalate)
    }

    @Test
    fun `intermediate attempts should not escalate`() {
        for (attempt in 0 until cap - 1) {
            val shouldEscalate = attempt + 1 >= cap
            assertFalse(
                "Attempt runAttemptCount=$attempt (attempt ${attempt + 1} of $cap) must NOT escalate",
                shouldEscalate,
            )
        }
    }

    @Test
    fun `final attempt should escalate`() {
        val runAttemptCount = cap - 1 // last allowed attempt index
        val shouldEscalate = runAttemptCount + 1 >= cap
        assertTrue(
            "Last attempt (runAttemptCount=${cap - 1}) MUST escalate to terminal",
            shouldEscalate,
        )
    }

    @Test
    fun `attempts beyond cap should also escalate`() {
        for (attempt in cap..cap + 5) {
            val shouldEscalate = attempt + 1 >= cap
            assertTrue(
                "Over-cap attempt runAttemptCount=$attempt must escalate",
                shouldEscalate,
            )
        }
    }

    @Test
    fun `exactly cap-minus-1 retries are allowed before escalation`() {
        // Attempts 0, 1, …, cap-2 → should retry (cap-1 retries total)
        val retryableCount = (0 until cap).count { attempt -> attempt + 1 < cap }
        assertEquals(
            "Exactly ${cap - 1} intermediate attempts should be retried before escalation",
            cap - 1,
            retryableCount,
        )
    }

    @Test
    fun `terminal retry exhaustion notifies failure once`() =
        runTest {
            val pair =
                SyncPair(
                    id = 42L,
                    displayName = "Docs",
                    localTreeUri = "content://docs",
                    provider = CloudProviderType.GOOGLE_DRIVE,
                    accountId = "acct-1",
                    remoteFolderId = "remote",
                    direction = SyncDirection.BIDIRECTIONAL,
                    conflictPolicy = ConflictPolicy.NEWEST_WINS,
                    wifiOnly = false,
                    autoSyncEnabled = true,
                    scheduleIntervalMinutes = 60,
                )
            val syncPairDao = mockk<SyncPairDao>(relaxed = true)
            val syncEventRepository = mockk<SyncEventRepository>(relaxed = true)
            val syncStatusNotifier = mockk<SyncStatusNotifier>(relaxed = true)
            val worker =
                SyncWorker(
                    appContext = context,
                    params = mockk(relaxed = true),
                    syncPairDao = syncPairDao,
                    providerFactories = emptyMap<CloudProviderType, CloudProviderFactory>(),
                    engine = mockk<SyncEngine>(relaxed = true),
                    syncEventRepository = syncEventRepository,
                    syncStatusNotifier = syncStatusNotifier,
                    settingsRepository = mockk<SettingsRepository>(relaxed = true),
                )

            coEvery { syncStatusNotifier.notifyFailure(pair, "boom") } returns Unit

            val result =
                worker.handleRetriableExhaustion(
                    pair = pair,
                    pairId = pair.id,
                    tag = "SyncWorker",
                    reason = "boom",
                )

            assertEquals(androidx.work.ListenableWorker.Result.failure().javaClass, result.javaClass)
            coVerify(exactly = 1) { syncStatusNotifier.notifyFailure(pair, "boom") }
            coVerify(exactly = 1) {
                syncPairDao.updateLastSyncResult(pair.id, any(), SyncWorker.RESULT_FAILURE)
            }
            coVerify(exactly = 1) {
                syncEventRepository.log(
                    pair.id,
                    SyncEventLevel.ERROR,
                    "SyncWorker",
                    "Sync failed after ${SyncWorker.MAX_RETRY_ATTEMPTS} attempt(s), giving up: boom",
                )
            }
        }
}
