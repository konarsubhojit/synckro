package com.synckro.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.PendingUploadEntity
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        db.close()
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

    private suspend fun insertPair(): Long =
        pairDao.insert(
            SyncPairEntity(
                displayName = "Test Pair",
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
    ) = PendingUploadEntity(
        pairId = pairId,
        relativePath = path,
        documentIdHint = "document-id",
        observedSizeBytes = 100L,
        observedMtimeMs = 500L,
        eligibleAtMs = 1_000L,
        createdAtMs = 500L,
        updatedAtMs = 500L,
    )
}
