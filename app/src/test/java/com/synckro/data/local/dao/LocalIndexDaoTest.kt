package com.synckro.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
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
 * JVM unit tests for [LocalIndexDao] using an in-memory Room database backed by
 * Robolectric.  Verifies upsert, list-by-pair, and clearForPair behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalIndexDaoTest {
    private lateinit var db: SynckroDatabase
    private lateinit var pairDao: SyncPairDao
    private lateinit var localIndexDao: LocalIndexDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        pairDao = db.syncPairDao()
        localIndexDao = db.localIndexDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun insertPair(id: Long = 0L): Long =
        pairDao.insert(
            SyncPairEntity(
                id = id,
                displayName = "Test Pair",
                localTreeUri = "content://example/tree/root",
                provider = CloudProviderType.FAKE,
                remoteFolderId = "root",
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                includeGlobs = "",
                excludeGlobs = "",
                wifiOnly = true,
                requiresCharging = false,
            ),
        )

    private fun buildEntry(
        pairId: Long,
        path: String = "docs/file.txt",
        sizeBytes: Long = 1024L,
        mtimeMs: Long = 1_000_000L,
        contentHash: String? = null,
        remoteId: String? = null,
    ) = LocalIndexEntity(
        pairId = pairId,
        relativePath = path,
        sizeBytes = sizeBytes,
        mtimeMs = mtimeMs,
        contentHash = contentHash,
        remoteId = remoteId,
    )

    // -------------------------------------------------------------------------
    // upsert / getForPair
    // -------------------------------------------------------------------------

    @Test
    fun `upsert inserts entry and getForPair returns it`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsert(buildEntry(pairId))

            val entries = localIndexDao.getForPair(pairId)

            assertEquals(1, entries.size)
            assertEquals("docs/file.txt", entries.first().relativePath)
            assertEquals(1024L, entries.first().sizeBytes)
            assertEquals(1_000_000L, entries.first().mtimeMs)
            assertNull(entries.first().contentHash)
            assertNull(entries.first().remoteId)
        }

    @Test
    fun `upsert updates existing entry on same pairId and relativePath`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsert(buildEntry(pairId, sizeBytes = 100L))

            // Upsert again with different size and hash
            localIndexDao.upsert(
                buildEntry(pairId, sizeBytes = 200L, contentHash = "abc123", remoteId = "r1"),
            )

            val entries = localIndexDao.getForPair(pairId)
            assertEquals(1, entries.size)
            assertEquals(200L, entries.first().sizeBytes)
            assertEquals("abc123", entries.first().contentHash)
            assertEquals("r1", entries.first().remoteId)
        }

    @Test
    fun `upsertAll inserts multiple entries`() =
        runTest {
            val pairId = insertPair()
            val entries =
                listOf(
                    buildEntry(pairId, path = "a.txt"),
                    buildEntry(pairId, path = "b.txt"),
                    buildEntry(pairId, path = "c.txt"),
                )

            localIndexDao.upsertAll(entries)

            val result = localIndexDao.getForPair(pairId)
            assertEquals(3, result.size)
            assertEquals(listOf("a.txt", "b.txt", "c.txt"), result.map { it.relativePath })
        }

    @Test
    fun `getForPair only returns entries for the given pairId`() =
        runTest {
            val pair1 = insertPair()
            val pair2 = insertPair()
            localIndexDao.upsert(buildEntry(pair1, "pair1-file.txt"))
            localIndexDao.upsert(buildEntry(pair2, "pair2-file.txt"))

            val result = localIndexDao.getForPair(pair1)

            assertEquals(1, result.size)
            assertEquals("pair1-file.txt", result.first().relativePath)
        }

    @Test
    fun `getForPair returns empty list when no entries exist`() =
        runTest {
            val pairId = insertPair()

            val result = localIndexDao.getForPair(pairId)

            assertTrue(result.isEmpty())
        }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    fun `delete removes the specified entry`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsert(buildEntry(pairId, "keep.txt"))
            localIndexDao.upsert(buildEntry(pairId, "remove.txt"))

            localIndexDao.delete(pairId, "remove.txt")

            val result = localIndexDao.getForPair(pairId)
            assertEquals(1, result.size)
            assertEquals("keep.txt", result.first().relativePath)
        }

    // -------------------------------------------------------------------------
    // clearForPair
    // -------------------------------------------------------------------------

    @Test
    fun `clearForPair removes all entries for the given pair`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsertAll(
                listOf(
                    buildEntry(pairId, "a.txt"),
                    buildEntry(pairId, "b.txt"),
                ),
            )

            localIndexDao.clearForPair(pairId)

            val result = localIndexDao.getForPair(pairId)
            assertTrue(result.isEmpty())
        }

    @Test
    fun `clearForPair does not affect entries for other pairs`() =
        runTest {
            val pair1 = insertPair()
            val pair2 = insertPair()
            localIndexDao.upsert(buildEntry(pair1, "a.txt"))
            localIndexDao.upsert(buildEntry(pair2, "b.txt"))

            localIndexDao.clearForPair(pair1)

            assertTrue(localIndexDao.getForPair(pair1).isEmpty())
            assertEquals(1, localIndexDao.getForPair(pair2).size)
        }

    // -------------------------------------------------------------------------
    // Cascade delete
    // -------------------------------------------------------------------------

    @Test
    fun `cascade delete removes entries when parent pair is deleted`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsertAll(
                listOf(
                    buildEntry(pairId, "a.txt"),
                    buildEntry(pairId, "b.txt"),
                ),
            )

            pairDao.delete(pairId)

            assertTrue(localIndexDao.getForPair(pairId).isEmpty())
        }
}
