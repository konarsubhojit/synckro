package com.synckro.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.SyncEventEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncEventTaxonomy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM unit tests for [SyncEventDao] using an in-memory Room database backed by
 * Robolectric.  Verifies the rolling-deletion cap and per-pair filtering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEventDaoTest {
    private lateinit var db: SynckroDatabase
    private lateinit var dao: SyncEventDao
    private lateinit var syncPairDao: SyncPairDao
    private lateinit var repository: SyncEventRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.syncEventDao()
        syncPairDao = db.syncPairDao()
        repository = SyncEventRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildSyncPair(id: Long = 1L) =
        SyncPairEntity(
            id = id,
            displayName = "Test Pair $id",
            localTreeUri = "content://test/$id",
            provider = CloudProviderType.FAKE,
            remoteFolderId = "remote-$id",
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
            includeGlobs = "",
            excludeGlobs = "",
            wifiOnly = false,
            requiresCharging = false,
        )

    private fun event(
        pairId: Long? = null,
        timestampMs: Long = System.currentTimeMillis(),
        level: String = "INFO",
        tag: String = "Test",
        message: String = "test message",
    ) = SyncEventEntity(
        pairId = pairId,
        timestampMs = timestampMs,
        level = level,
        tag = tag,
        message = message,
    )

    // -----------------------------------------------------------------------
    // Basic CRUD
    // -----------------------------------------------------------------------

    @Test
    fun `insert and observeAll round-trip for global event`() =
        runTest {
            dao.insertAndPrune(event(pairId = null, message = "hello"))

            val rows = dao.observeAll().first()
            assertEquals(1, rows.size)
            assertEquals("hello", rows[0].message)
        }

    @Test
    fun `repository preserves completed run transfer bytes`() =
        runTest {
            syncPairDao.insert(buildSyncPair())
            repository.log(
                pairId = 1L,
                level = SyncEventLevel.INFO,
                tag = SyncEventTag.SYNC_WORKER,
                message = "Sync succeeded: 1 applied, 0 conflicts",
                bytesTransferred = 4_096L,
            )

            assertEquals(4_096L, repository.getAll(limit = 1).single().bytesTransferred)
        }

    @Test
    fun `observeForPair returns only matching pairId rows`() =
        runTest {
            syncPairDao.insert(buildSyncPair(1L))
            syncPairDao.insert(buildSyncPair(2L))

            dao.insertAndPrune(event(pairId = 1L, message = "pair 1"))
            dao.insertAndPrune(event(pairId = 2L, message = "pair 2"))
            dao.insertAndPrune(event(pairId = null, message = "global"))

            val pair1Events = dao.observeForPair(1L).first()
            assertEquals(1, pair1Events.size)
            assertEquals("pair 1", pair1Events[0].message)
        }

    @Test
    fun `observeAll returns newest-first ordering`() =
        runTest {
            val base = 1_000_000L
            dao.insertAndPrune(event(pairId = null, timestampMs = base + 100, message = "newer"))
            dao.insertAndPrune(event(pairId = null, timestampMs = base, message = "older"))

            val rows = dao.observeAll().first()
            assertEquals("newer", rows[0].message)
            assertEquals("older", rows[1].message)
        }

    // -----------------------------------------------------------------------
    // Rolling-cap enforcement
    // -----------------------------------------------------------------------

    @Test
    fun `per-pair cap prunes oldest entries beyond MAX_EVENTS_PER_PAIR`() =
        runTest {
            syncPairDao.insert(buildSyncPair(1L))

            val cap = SyncEventDao.MAX_EVENTS_PER_PAIR
            val total = cap + 10
            for (i in 1..total) {
                dao.insertAndPrune(
                    event(pairId = 1L, timestampMs = i.toLong(), message = "msg-$i"),
                )
            }

            val rows = dao.observeForPair(1L, limit = total).first()
            assertTrue(
                "Expected at most $cap rows but got ${rows.size}",
                rows.size <= cap,
            )
            // The oldest messages (msg-1 … msg-10) must have been pruned.
            val messages = rows.map { it.message }
            assertTrue("msg-1 should have been pruned", "msg-1" !in messages)
            assertTrue("most recent must survive", "msg-$total" in messages)
        }

    @Test
    fun `global cap prunes oldest entries beyond MAX_EVENTS_GLOBAL`() =
        runTest {
            val cap = SyncEventDao.MAX_EVENTS_GLOBAL
            val total = cap + 5
            for (i in 1..total) {
                dao.insertAndPrune(
                    event(pairId = null, timestampMs = i.toLong(), message = "g-$i"),
                )
            }

            val rows = dao.observeAll(limit = total).first()
            assertTrue(
                "Expected at most $cap global rows but got ${rows.size}",
                rows.size <= cap,
            )
        }

    @Test
    fun `repository redacts paths and account identifiers before persisting`() =
        runTest {
            repository.log(
                pairId = null,
                level = SyncEventLevel.WARN,
                tag = SyncEventTag.INSTANT_WATCHER,
                message = "Fallback for content://tree/primary%3ADocs and /storage/emulated/0/Pictures/cat.jpg accountId=abc123 user@example.com",
            )

            val message = repository.getAll(limit = 1).single().message
            assertTrue(message.contains("<uri>"))
            assertTrue(message.contains("<path>"))
            assertTrue(message.contains("accountId=<account>"))
            assertTrue(message.contains("<account>"))
            assertTrue(!message.contains("primary%3ADocs"))
            assertTrue(!message.contains("cat.jpg"))
            assertTrue(!message.contains("abc123"))
            assertTrue(!message.contains("user@example.com"))
        }

    @Test
    fun `getForPairAndTag returns only matching taxonomy events`() =
        runTest {
            syncPairDao.insert(buildSyncPair(1L))
            syncPairDao.insert(buildSyncPair(2L))

            repository.log(1L, SyncEventLevel.INFO, SyncEventTag.INSTANT_QUEUE, SyncEventTaxonomy.queueEnqueued("manual"))
            repository.log(1L, SyncEventLevel.INFO, SyncEventTag.INSTANT_WATCHER, SyncEventTaxonomy.watcherRegistered("work_info"))
            repository.log(2L, SyncEventLevel.INFO, SyncEventTag.INSTANT_QUEUE, SyncEventTaxonomy.queueEnqueued("manual"))

            val rows = repository.getForPairAndTag(1L, SyncEventTag.INSTANT_QUEUE)
            assertEquals(1, rows.size)
            assertEquals(1L, rows.single().pairId)
            assertEquals(SyncEventTag.INSTANT_QUEUE, rows.single().tag)
        }

    @Test
    fun `logRateLimited suppresses repeated noisy deferrals inside window`() =
        runTest {
            syncPairDao.insert(buildSyncPair(1L))
            val message = SyncEventTaxonomy.stabilityDeferred("debounce_window")

            assertTrue(
                repository.logRateLimited(
                    pairId = 1L,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.INSTANT_STABILITY,
                    message = message,
                    throttleKey = "deferred",
                    windowMs = 1_000L,
                    nowMs = 10_000L,
                ),
            )
            assertTrue(
                !repository.logRateLimited(
                    pairId = 1L,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.INSTANT_STABILITY,
                    message = message,
                    throttleKey = "deferred",
                    windowMs = 1_000L,
                    nowMs = 10_500L,
                ),
            )
            assertTrue(
                repository.logRateLimited(
                    pairId = 1L,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.INSTANT_STABILITY,
                    message = message,
                    throttleKey = "deferred",
                    windowMs = 1_000L,
                    nowMs = 11_000L,
                ),
            )

            assertEquals(2, repository.getForPairAndTag(1L, SyncEventTag.INSTANT_STABILITY).size)
        }
}
