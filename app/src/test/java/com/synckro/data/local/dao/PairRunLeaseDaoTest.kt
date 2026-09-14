package com.synckro.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairRunLeaseDaoTest {
    private lateinit var db: SynckroDatabase
    private lateinit var pairDao: SyncPairDao
    private lateinit var leaseDao: PairRunLeaseDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        pairDao = db.syncPairDao()
        leaseDao = db.pairRunLeaseDao()
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    @Test
    fun `only one run owns a pair at a time`() =
        runTest {
            val pairId = insertPair()

            assertTrue(acquire(pairId, "periodic-run", "periodic"))
            assertFalse(acquire(pairId, "instant-run", "instant"))
            assertFalse(acquire(pairId, "manual-run", "manual"))
            assertEquals("periodic", leaseDao.get(pairId)?.ownerKind)
        }

    @Test
    fun `concurrent acquisitions elect a single owner`() =
        runTest {
            val pairId = insertPair()

            val outcomes =
                listOf("instant-run", "manual-run", "periodic-run")
                    .map { token ->
                        async(Dispatchers.IO) { acquire(pairId, token, "periodic") }
                    }.awaitAll()

            assertEquals(1, outcomes.count { it })
        }

    @Test
    fun `different pairs run concurrently`() =
        runTest {
            val firstPairId = insertPair("First Pair")
            val secondPairId = insertPair("Second Pair")

            assertTrue(acquire(firstPairId, "run-a", "periodic"))
            assertTrue(acquire(secondPairId, "run-b", "instant"))
        }

    @Test
    fun `stale ownership is recovered after owner death`() =
        runTest {
            val pairId = insertPair()
            assertTrue(acquire(pairId, "dead-run", "instant", nowMs = 1_000L))

            assertFalse(acquire(pairId, "next-run", "periodic", nowMs = 1_000L + STALE_AFTER_MS - 1))
            assertTrue(acquire(pairId, "next-run", "periodic", nowMs = 1_000L + STALE_AFTER_MS))
            assertEquals("next-run", leaseDao.get(pairId)?.ownerToken)
        }

    @Test
    fun `heartbeat keeps a long run from being taken over`() =
        runTest {
            val pairId = insertPair()
            assertTrue(acquire(pairId, "long-run", "manual", nowMs = 1_000L))

            assertEquals(1, leaseDao.renew(pairId, "long-run", nowMs = 1_000L + STALE_AFTER_MS))

            assertFalse(acquire(pairId, "other-run", "instant", nowMs = 1_000L + STALE_AFTER_MS))
            assertEquals(0, leaseDao.renew(pairId, "other-run", nowMs = 2_000L))
        }

    @Test
    fun `re-running the same owner reacquires its own lease`() =
        runTest {
            val pairId = insertPair()
            assertTrue(acquire(pairId, "retrying-run", "instant", nowMs = 1_000L))

            assertTrue(acquire(pairId, "retrying-run", "instant", nowMs = 1_500L))
            assertEquals(1_500L, leaseDao.get(pairId)?.acquiredAtMs)
        }

    @Test
    fun `release only clears the current owner and pair deletion cascades`() =
        runTest {
            val pairId = insertPair()
            acquire(pairId, "owner-run", "periodic")

            assertEquals(0, leaseDao.release(pairId, "other-run"))
            assertEquals(1, leaseDao.release(pairId, "owner-run"))
            assertNull(leaseDao.get(pairId))

            acquire(pairId, "owner-run", "periodic")
            pairDao.delete(pairId)
            assertNull(leaseDao.get(pairId))
        }

    private suspend fun acquire(
        pairId: Long,
        ownerToken: String,
        ownerKind: String,
        nowMs: Long = 1_000L,
    ): Boolean =
        leaseDao.acquire(
            pairId = pairId,
            ownerToken = ownerToken,
            ownerKind = ownerKind,
            nowMs = nowMs,
            staleAfterMs = STALE_AFTER_MS,
        )

    private suspend fun insertPair(displayName: String = "Test Pair"): Long =
        pairDao.insert(
            SyncPairEntity(
                displayName = displayName,
                localTreeUri = "content://example/tree/root",
                provider = CloudProviderType.FAKE,
                remoteFolderId = "root",
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                includeGlobs = "",
                excludeGlobs = "",
                wifiOnly = true,
                requiresCharging = false,
            ),
        )

    private companion object {
        const val STALE_AFTER_MS = 5 * 60 * 1_000L
    }
}
