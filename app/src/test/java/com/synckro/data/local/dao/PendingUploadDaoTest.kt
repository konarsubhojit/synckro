package com.synckro.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.PendingUploadEntity
import com.synckro.data.local.entity.PendingUploadState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingUploadDaoTest {
    private lateinit var db: SynckroDatabase
    private lateinit var pairDao: SyncPairDao
    private lateinit var pendingUploadDao: PendingUploadDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        pairDao = db.syncPairDao()
        pendingUploadDao = db.pendingUploadDao()
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    @Test
    fun `concurrent claims are exclusive`() =
        runTest {
            val pairId = insertPair()
            pendingUploadDao.upsert(upload(pairId))

            val claims =
                listOf("worker-a", "worker-b")
                    .map { token ->
                        async(Dispatchers.IO) {
                            pendingUploadDao.claimEligible(token, claimedAtMs = 1_000L, limit = 1)
                        }
                    }.awaitAll()

            assertEquals(1, claims.count { it.isNotEmpty() })
            assertEquals(1, claims.flatten().size)
        }

    @Test
    fun `pair scoped claim does not consume another pair queue`() =
        runTest {
            val firstPairId = insertPair("First Pair")
            val secondPairId = insertPair("Second Pair")
            pendingUploadDao.upsert(upload(firstPairId, path = "first.txt"))
            pendingUploadDao.upsert(upload(secondPairId, path = "second.txt"))

            assertEquals(
                listOf("second.txt"),
                pendingUploadDao
                    .claimEligibleForPair(secondPairId, "worker", claimedAtMs = 1_000L, limit = 10)
                    .map { it.relativePath },
            )
            assertEquals(
                listOf("first.txt"),
                pendingUploadDao
                    .claimEligibleForPair(firstPairId, "other-worker", claimedAtMs = 1_000L, limit = 10)
                    .map { it.relativePath },
            )
        }

    @Test
    fun `same work token recovers its interrupted pair claim`() =
        runTest {
            val pairId = insertPair()
            pendingUploadDao.upsert(upload(pairId))
            pendingUploadDao.claimEligibleForPair(pairId, "worker", claimedAtMs = 1_000L, limit = 1)

            assertEquals(
                1,
                pendingUploadDao.recoverClaimsForPair(
                    pairId = pairId,
                    claimToken = "worker",
                    staleBeforeMs = 0L,
                    recoveredAtMs = 1_500L,
                ),
            )
            assertEquals(
                listOf("file.txt"),
                pendingUploadDao
                    .claimEligibleForPair(pairId, "worker", claimedAtMs = 1_500L, limit = 1)
                    .map { it.relativePath },
            )
        }

    @Test
    fun `release and stale recovery make claims eligible again`() =
        runTest {
            val pairId = insertPair()
            pendingUploadDao.upsert(upload(pairId))
            val claim = pendingUploadDao.claimEligible("worker-a", claimedAtMs = 1_000L, limit = 1).single()

            assertEquals(
                1,
                pendingUploadDao.release(
                    pairId = pairId,
                    relativePath = claim.relativePath,
                    claimToken = "worker-a",
                    eligibleAtMs = 2_000L,
                    updatedAtMs = 1_500L,
                ),
            )
            assertTrue(pendingUploadDao.claimEligible("worker-b", claimedAtMs = 1_999L, limit = 1).isEmpty())
            pendingUploadDao.claimEligible("worker-b", claimedAtMs = 2_000L, limit = 1)

            assertEquals(1, pendingUploadDao.recoverStaleClaims(staleBeforeMs = 2_000L, recoveredAtMs = 3_000L))
            assertEquals(
                listOf("file.txt"),
                pendingUploadDao.claimEligible("worker-c", claimedAtMs = 3_000L, limit = 1).map { it.relativePath },
            )
        }

    @Test
    fun `eligible pair ids skip claimed and not yet eligible rows`() =
        runTest {
            val readyPairId = insertPair("Ready Pair")
            val laterPairId = insertPair("Later Pair")
            val claimedPairId = insertPair("Claimed Pair")
            pendingUploadDao.upsert(upload(readyPairId, path = "ready.txt", eligibleAtMs = 500L))
            pendingUploadDao.upsert(upload(laterPairId, path = "later.txt", eligibleAtMs = 5_000L))
            pendingUploadDao.upsert(upload(claimedPairId, path = "claimed.txt", eligibleAtMs = 100L))
            pendingUploadDao.claimEligibleForPair(claimedPairId, "worker-a", claimedAtMs = 200L, limit = 10)

            assertEquals(listOf(readyPairId), pendingUploadDao.pairIdsWithEligibleRows(nowMs = 1_000L))

            pendingUploadDao.recoverStaleClaims(staleBeforeMs = 250L, recoveredAtMs = 300L)

            assertEquals(
                listOf(claimedPairId, readyPairId),
                pendingUploadDao.pairIdsWithEligibleRows(nowMs = 1_000L),
            )
        }

    @Test
    fun `complete only removes the matching claim and pair deletion cascades`() =
        runTest {
            val pairId = insertPair()
            pendingUploadDao.upsert(upload(pairId))
            pendingUploadDao.claimEligible("worker-a", claimedAtMs = 1_000L, limit = 1)

            assertEquals(0, pendingUploadDao.complete(pairId, "file.txt", "worker-b"))
            assertEquals(1, pendingUploadDao.complete(pairId, "file.txt", "worker-a"))

            pendingUploadDao.upsert(upload(pairId, path = "second.txt"))
            pairDao.delete(pairId)
            assertTrue(pendingUploadDao.getForPair(pairId).isEmpty())
        }

    @Test
    fun `upsert refreshes duplicate candidate without creating another row`() =
        runTest {
            val pairId = insertPair()
            pendingUploadDao.upsert(upload(pairId, attempts = 2))

            pendingUploadDao.upsert(
                upload(
                    pairId = pairId,
                    documentIdHint = "document-id-2",
                    observedSizeBytes = 200L,
                    observedMtimeMs = 800L,
                    eligibleAtMs = 1_500L,
                    createdAtMs = 900L,
                    updatedAtMs = 900L,
                ),
            )

            val rows = pendingUploadDao.getForPair(pairId)
            assertEquals(1, rows.size)
            with(rows.single()) {
                assertEquals("document-id-2", documentIdHint)
                assertEquals(200L, observedSizeBytes)
                assertEquals(800L, observedMtimeMs)
                assertEquals(PendingUploadState.PENDING, state)
                assertEquals(2, attempts)
                assertEquals(1_500L, eligibleAtMs)
                assertEquals(500L, createdAtMs)
                assertEquals(900L, updatedAtMs)
            }
        }

    @Test
    fun `upsert during claim leaves refreshed candidate pending after old claim completes`() =
        runTest {
            val pairId = insertPair()
            pendingUploadDao.upsert(upload(pairId))
            pendingUploadDao.claimEligible("worker-a", claimedAtMs = 1_000L, limit = 1).single()

            pendingUploadDao.upsert(
                upload(
                    pairId = pairId,
                    documentIdHint = "new-document-id",
                    observedSizeBytes = 300L,
                    observedMtimeMs = 900L,
                    eligibleAtMs = 1_500L,
                    createdAtMs = 1_100L,
                    updatedAtMs = 1_100L,
                ),
            )

            assertEquals(0, pendingUploadDao.complete(pairId, "file.txt", "worker-a"))
            val row = pendingUploadDao.getForPair(pairId).single()
            assertEquals(PendingUploadState.PENDING, row.state)
            assertEquals("new-document-id", row.documentIdHint)
            assertEquals(300L, row.observedSizeBytes)
            assertEquals(900L, row.observedMtimeMs)
            assertNull(row.claimToken)
            assertNull(row.claimedAtMs)
            assertEquals(
                listOf("file.txt"),
                pendingUploadDao.claimEligible("worker-b", claimedAtMs = 1_500L, limit = 1).map { it.relativePath },
            )
        }

    @Test
    fun `upsert is isolated by pair for the same relative path`() =
        runTest {
            val firstPairId = insertPair("First Pair")
            val secondPairId = insertPair("Second Pair")
            pendingUploadDao.upsert(upload(firstPairId, path = "shared.txt"))
            pendingUploadDao.upsert(
                upload(
                    secondPairId,
                    path = "shared.txt",
                    documentIdHint = "second-document-id",
                    observedSizeBytes = 400L,
                    updatedAtMs = 700L,
                ),
            )

            pendingUploadDao.upsert(
                upload(
                    firstPairId,
                    path = "shared.txt",
                    documentIdHint = "first-document-id-2",
                    observedSizeBytes = 500L,
                    updatedAtMs = 800L,
                ),
            )

            assertEquals("first-document-id-2", pendingUploadDao.getForPair(firstPairId).single().documentIdHint)
            assertEquals("second-document-id", pendingUploadDao.getForPair(secondPairId).single().documentIdHint)
        }

    @Test
    fun `pending candidates remain deduplicated across database reopen`() =
        runTest {
            db.close()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbFile = File(context.cacheDir, "pending-upload-dedup-${System.nanoTime()}.db")
            try {
                db = openPersistentDb(dbFile)
                pairDao = db.syncPairDao()
                pendingUploadDao = db.pendingUploadDao()

                val pairId = insertPair()
                pendingUploadDao.upsert(upload(pairId))
                pendingUploadDao.upsert(upload(pairId, documentIdHint = "after-restart-source", updatedAtMs = 900L))
                db.close()

                db = openPersistentDb(dbFile)
                pairDao = db.syncPairDao()
                pendingUploadDao = db.pendingUploadDao()

                val rows = pendingUploadDao.getForPair(pairId)
                assertEquals(1, rows.size)
                assertEquals("after-restart-source", rows.single().documentIdHint)
            } finally {
                if (db.isOpen) db.close()
                dbFile.delete()
            }
        }

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

    private fun upload(
        pairId: Long,
        path: String = "file.txt",
        documentIdHint: String? = "document-id",
        observedSizeBytes: Long = 100L,
        observedMtimeMs: Long = 500L,
        attempts: Int = 0,
        eligibleAtMs: Long = 1_000L,
        createdAtMs: Long = 500L,
        updatedAtMs: Long = 500L,
    ) = PendingUploadEntity(
        pairId = pairId,
        relativePath = path,
        documentIdHint = documentIdHint,
        observedSizeBytes = observedSizeBytes,
        observedMtimeMs = observedMtimeMs,
        attempts = attempts,
        eligibleAtMs = eligibleAtMs,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

    private fun openPersistentDb(dbFile: File): SynckroDatabase =
        Room
            .databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                SynckroDatabase::class.java,
                dbFile.absolutePath,
            ).allowMainThreadQueries()
            .build()
}
