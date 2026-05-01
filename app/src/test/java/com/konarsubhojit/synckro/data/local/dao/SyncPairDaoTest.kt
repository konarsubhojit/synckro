package com.konarsubhojit.synckro.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.konarsubhojit.synckro.data.local.db.SynckroDatabase
import com.konarsubhojit.synckro.data.local.entity.SyncPairEntity
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM unit tests for [SyncPairDao] using an in-memory Room database backed by
 * Robolectric's SQLite driver.  These tests verify that [SyncPairEntity.localTreeUri]
 * survives insert/query round-trips without modification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncPairDaoTest {

    private lateinit var db: SynckroDatabase
    private lateinit var dao: SyncPairDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.syncPairDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildEntity(localTreeUri: String) = SyncPairEntity(
        displayName = "Test Pair",
        localTreeUri = localTreeUri,
        provider = CloudProviderType.ONEDRIVE,
        remoteFolderId = "remote-folder-123",
        direction = SyncDirection.BIDIRECTIONAL,
        conflictPolicy = ConflictPolicy.NEWEST_WINS,
        includeGlobs = "",
        excludeGlobs = "",
        wifiOnly = true,
        requiresCharging = false,
    )

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun `insert and getById round-trips localTreeUri for internal storage`() = runTest {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
        val id = dao.insert(buildEntity(uri))

        val retrieved = dao.getById(id)

        assertNotNull("Entity must be retrievable by its returned id", retrieved)
        assertEquals(
            "localTreeUri must be identical after insert + getById",
            uri,
            retrieved!!.localTreeUri,
        )
    }

    @Test
    fun `insert and getById round-trips localTreeUri for SD card`() = runTest {
        val uri = "content://com.android.externalstorage.documents/tree/1234-ABCD%3A"
        val id = dao.insert(buildEntity(uri))

        val retrieved = dao.getById(id)

        assertNotNull("Entity must be retrievable by its returned id", retrieved)
        assertEquals(
            "localTreeUri must be identical after insert + getById for SD card URI",
            uri,
            retrieved!!.localTreeUri,
        )
    }

    @Test
    fun `upsert overwrites localTreeUri`() = runTest {
        val originalUri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
        val updatedUri = "content://com.android.externalstorage.documents/tree/primary%3APictures"

        val id = dao.insert(buildEntity(originalUri))
        dao.upsert(buildEntity(updatedUri).copy(id = id))

        val retrieved = dao.getById(id)

        assertNotNull(retrieved)
        assertEquals(
            "upsert must overwrite localTreeUri",
            updatedUri,
            retrieved!!.localTreeUri,
        )
    }

    @Test
    fun `observeAll emits all inserted entities with correct localTreeUri`() = runTest {
        val uri1 = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val uri2 = "content://com.android.externalstorage.documents/tree/primary%3AVideos"

        dao.insert(buildEntity(uri1))
        dao.insert(buildEntity(uri2))

        val all = dao.observeAll().first()

        assertEquals(2, all.size)
        assertEquals(uri1, all[0].localTreeUri)
        assertEquals(uri2, all[1].localTreeUri)
    }
}
