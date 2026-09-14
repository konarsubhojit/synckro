package com.synckro.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.dao.PendingUploadDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingUploadRepositoryTest {
    private lateinit var db: SynckroDatabase
    private lateinit var pairDao: SyncPairDao
    private lateinit var pendingUploadDao: PendingUploadDao
    private lateinit var repository: PendingUploadRepository

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
        repository = PendingUploadRepository(pendingUploadDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `multiple watcher observations for one candidate create one refreshed row`() =
        runTest {
            val pairId = insertPair()

            repository.upsertCandidate(
                pairId = pairId,
                relativePath = "DCIM/photo.jpg",
                documentIdHint = "first-document",
                observedSizeBytes = 100L,
                observedMtimeMs = 1_000L,
                eligibleAtMs = 2_000L,
                observedAtMs = 1_500L,
            )
            repository.upsertCandidate(
                pairId = pairId,
                relativePath = "DCIM/photo.jpg",
                documentIdHint = "second-document",
                observedSizeBytes = 200L,
                observedMtimeMs = 1_100L,
                eligibleAtMs = 2_500L,
                observedAtMs = 1_600L,
            )

            val rows = pendingUploadDao.getForPair(pairId)
            assertEquals(1, rows.size)
            with(rows.single()) {
                assertEquals("second-document", documentIdHint)
                assertEquals(200L, observedSizeBytes)
                assertEquals(1_100L, observedMtimeMs)
                assertEquals(2_500L, eligibleAtMs)
                assertEquals(1_500L, createdAtMs)
                assertEquals(1_600L, updatedAtMs)
            }
        }

    @Test
    fun `same relative path is deduplicated independently for each pair`() =
        runTest {
            val firstPairId = insertPair("First Pair")
            val secondPairId = insertPair("Second Pair")

            repository.upsertCandidate(firstPairId, "shared.txt", "first", 100L, 1_000L, 2_000L, 1_500L)
            repository.upsertCandidate(secondPairId, "shared.txt", "second", 200L, 1_100L, 2_100L, 1_600L)

            assertEquals("first", pendingUploadDao.getForPair(firstPairId).single().documentIdHint)
            assertEquals("second", pendingUploadDao.getForPair(secondPairId).single().documentIdHint)
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
}
