package com.synckro.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.ConflictRecordEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.ConflictRecord
import com.synckro.domain.model.SyncDirection
import kotlinx.coroutines.flow.first
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

/**
 * JVM unit tests for [ConflictRecordDao] using an in-memory Room database.
 * Verifies insert, resolve, observe, and cascade-delete behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConflictRecordDaoTest {
    private lateinit var db: SynckroDatabase
    private lateinit var pairDao: SyncPairDao
    private lateinit var conflictDao: ConflictRecordDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        pairDao = db.syncPairDao()
        conflictDao = db.conflictRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private fun buildConflict(
        pairId: Long,
        path: String = "docs/file.txt",
    ) = ConflictRecordEntity(
        pairId = pairId,
        relativePath = path,
        localLastModifiedMs = 2_000L,
        remoteLastModifiedMs = 1_000L,
        detectedAtMs = 3_000L,
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert and observeUnresolved returns conflict`() =
        runTest {
            val pairId = insertPair()
            conflictDao.insert(buildConflict(pairId))

            val unresolved = conflictDao.observeUnresolved().first()

            assertEquals(1, unresolved.size)
            assertEquals("docs/file.txt", unresolved.first().relativePath)
            assertNull(unresolved.first().resolution)
        }

    @Test
    fun `resolve sets resolution and conflict no longer appears in unresolved`() =
        runTest {
            val pairId = insertPair()
            val id = conflictDao.insert(buildConflict(pairId))

            conflictDao.resolve(id, ConflictRecord.RESOLUTION_KEEP_LOCAL)

            val unresolved = conflictDao.observeUnresolved().first()
            assertTrue(unresolved.isEmpty())
        }

    @Test
    fun `getResolvedForPair returns resolved records only`() =
        runTest {
            val pairId = insertPair()
            val id1 = conflictDao.insert(buildConflict(pairId, "a.txt"))
            val id2 = conflictDao.insert(buildConflict(pairId, "b.txt"))

            conflictDao.resolve(id1, ConflictRecord.RESOLUTION_KEEP_REMOTE)

            val resolved = conflictDao.getResolvedForPair(pairId)
            assertEquals(1, resolved.size)
            assertEquals("a.txt", resolved.first().relativePath)
            assertEquals(ConflictRecord.RESOLUTION_KEEP_REMOTE, resolved.first().resolution)
        }

    @Test
    fun `delete removes the record`() =
        runTest {
            val pairId = insertPair()
            val id = conflictDao.insert(buildConflict(pairId))

            conflictDao.delete(id)

            val unresolved = conflictDao.observeUnresolved().first()
            assertTrue(unresolved.isEmpty())
        }

    @Test
    fun `deleteResolvedForPair only removes resolved records`() =
        runTest {
            val pairId = insertPair()
            val id1 = conflictDao.insert(buildConflict(pairId, "resolved.txt"))
            conflictDao.insert(buildConflict(pairId, "pending.txt"))
            conflictDao.resolve(id1, ConflictRecord.RESOLUTION_KEEP_BOTH)

            conflictDao.deleteResolvedForPair(pairId)

            val all = conflictDao.observeForPair(pairId).first()
            assertEquals(1, all.size)
            assertEquals("pending.txt", all.first().relativePath)
        }

    @Test
    fun `cascade delete removes conflict records when pair is deleted`() =
        runTest {
            val pairId = insertPair()
            conflictDao.insert(buildConflict(pairId))

            pairDao.delete(pairId)

            val unresolved = conflictDao.observeUnresolved().first()
            assertTrue(unresolved.isEmpty())
        }

    @Test
    fun `observeForPair returns records ordered by detectedAtMs descending`() =
        runTest {
            val pairId = insertPair()
            conflictDao.insert(buildConflict(pairId, "older.txt").copy(detectedAtMs = 1_000L))
            conflictDao.insert(buildConflict(pairId, "newer.txt").copy(detectedAtMs = 5_000L))

            val records = conflictDao.observeForPair(pairId).first()

            assertEquals(2, records.size)
            assertEquals("newer.txt", records[0].relativePath)
            assertEquals("older.txt", records[1].relativePath)
        }

    @Test
    fun `observeUnresolvedCount counts only pending conflicts`() =
        runTest {
            val pairId = insertPair()
            assertEquals(0, conflictDao.observeUnresolvedCount().first())

            val a = conflictDao.insert(buildConflict(pairId, "a.txt"))
            conflictDao.insert(buildConflict(pairId, "b.txt"))
            conflictDao.insert(buildConflict(pairId, "c.txt"))
            assertEquals(3, conflictDao.observeUnresolvedCount().first())

            // Resolved records must NOT be counted as pending.
            conflictDao.resolve(a, ConflictRecord.RESOLUTION_KEEP_LOCAL)
            assertEquals(2, conflictDao.observeUnresolvedCount().first())
        }
}
