package com.synckro.domain.sync

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalStorageException
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.RemoteFile
import com.synckro.providers.fake.FakeCloudProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Tests for [SyncEngine.runTargetedUploads] — the upload-only entry point used by
 * Instant Sync for candidates that were already claimed from the durable queue.
 *
 * The engine under test is deliberately built **without** a [LocalFsEnumerator],
 * a [RemoteEnumerator], or a [SyncPairDao], which proves that a targeted run needs
 * neither a local tree walk, remote enumeration, nor checkpoint advancement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTargetedUploadTest {
    /** Pure in-memory [LocalFileAccess] backed by a [MutableMap]. */
    private class InMemoryLocalFileAccess(
        private val nowMs: Long = 5_000L,
    ) : LocalFileAccess {
        private val files = mutableMapOf<String, ByteArray>()
        private val mtimes = mutableMapOf<String, Long>()
        private val pendingMutations = mutableMapOf<String, Pair<ByteArray, Long>>()

        fun put(
            path: String,
            bytes: ByteArray,
            mtimeMs: Long = nowMs,
        ) {
            files[path] = bytes
            mtimes[path] = mtimeMs
        }

        fun mutateAfterNextRead(
            path: String,
            bytes: ByteArray,
            mtimeMs: Long,
        ) {
            pendingMutations[path] = bytes to mtimeMs
        }

        override fun openRead(path: String): InputStream? =
            files[path]?.let { bytes ->
                ByteArrayInputStream(bytes).also {
                    pendingMutations.remove(path)?.let { (newBytes, newMtimeMs) ->
                        files[path] = newBytes
                        mtimes[path] = newMtimeMs
                    }
                }
            }

        override fun write(
            path: String,
            content: InputStream,
            mimeType: String?,
        ): LocalFileStat {
            val bytes = content.use { it.readBytes() }
            files[path] = bytes
            mtimes[path] = nowMs
            return LocalFileStat(sizeBytes = bytes.size.toLong(), mtimeMs = nowMs, mimeType = mimeType)
        }

        override fun delete(path: String): Boolean {
            mtimes.remove(path)
            return files.remove(path) != null
        }

        override fun stat(path: String): LocalFileStat? =
            files[path]?.let { LocalFileStat(sizeBytes = it.size.toLong(), mtimeMs = mtimes[path] ?: nowMs) }
    }

    private val treeUri = Uri.parse("content://com.example.test/tree/root")

    private lateinit var db: SynckroDatabase
    private lateinit var syncPairDao: SyncPairDao
    private lateinit var localIndexDao: LocalIndexDao
    private lateinit var conflictRepository: ConflictRepository
    private lateinit var eventRepository: SyncEventRepository
    private lateinit var fakeProvider: FakeCloudProvider
    private lateinit var localFileAccess: InMemoryLocalFileAccess

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        syncPairDao = db.syncPairDao()
        localIndexDao = db.localIndexDao()
        conflictRepository = ConflictRepository(db.conflictRecordDao())
        eventRepository = SyncEventRepository(db.syncEventDao())
        fakeProvider = FakeCloudProvider()
        localFileAccess = InMemoryLocalFileAccess()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertPair(
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        excludeGlobs: String = "",
    ): SyncPair {
        val entity =
            SyncPairEntity(
                displayName = "Targeted Upload Pair",
                localTreeUri = treeUri.toString(),
                provider = CloudProviderType.ONEDRIVE,
                accountId = "test-account",
                remoteFolderId = "remote-root",
                direction = direction,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                includeGlobs = "",
                excludeGlobs = excludeGlobs,
                wifiOnly = false,
                requiresCharging = false,
                lastDeltaToken = "token-42",
                lastFullScanAtMs = 1_000L,
            )
        val id = syncPairDao.insert(entity)
        return SyncPair(
            id = id,
            displayName = entity.displayName,
            localTreeUri = entity.localTreeUri,
            provider = entity.provider,
            accountId = entity.accountId,
            remoteFolderId = entity.remoteFolderId,
            direction = entity.direction,
            conflictPolicy = entity.conflictPolicy,
            excludeGlobs = if (excludeGlobs.isEmpty()) emptyList() else excludeGlobs.split(","),
            deltaToken = entity.lastDeltaToken,
            lastFullScanAtMs = entity.lastFullScanAtMs,
        )
    }

    private fun buildEngine(provider: CloudProvider = fakeProvider): SyncEngine =
        SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(provider)),
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = { _ -> localFileAccess },
        )

    private fun singleProviderFactory(provider: CloudProvider): CloudProviderFactory =
        object : CloudProviderFactory {
            override fun providerFor(accountId: String): CloudProvider = provider
        }

    @Test
    fun `uploads only the named paths`() =
        runTest {
            val pair = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray())
            localFileAccess.put("other.txt", "ignored".toByteArray())

            val outcome = buildEngine().runTargetedUploads(pair, listOf("notes.txt"))

            assertTrue("Expected Success, got: ${outcome.result}", outcome.result is SyncEngine.Result.Success)
            assertEquals(1, outcome.result.applied)
            assertEquals(listOf("notes.txt"), outcome.uploadedPaths)
            assertEquals(listOf("notes.txt"), localIndexDao.getForPair(pair.id).map { it.relativePath })
            assertEquals(listOf("notes.txt"), fakeProvider.list("remote-root").map { it.name })
        }

    @Test
    fun `does not advance delta token or lastFullScanAtMs`() =
        runTest {
            val pair = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray())

            buildEngine().runTargetedUploads(pair, listOf("notes.txt"))

            val stored = syncPairDao.getById(pair.id)
            assertNotNull(stored)
            assertEquals("token-42", stored!!.lastDeltaToken)
            assertEquals(1_000L, stored.lastFullScanAtMs)
        }

    @Test
    fun `updates remote content when the path already has a remote id`() =
        runTest {
            val pair = insertPair()
            localFileAccess.put("notes.txt", "v1".toByteArray())
            val created =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "notes.txt",
                    content = ByteArrayInputStream("v1".toByteArray()),
                    size = 2L,
                    mimeType = null,
                )
            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pair.id,
                    relativePath = "notes.txt",
                    sizeBytes = 2L,
                    mtimeMs = 5_000L,
                    contentHash = null,
                    remoteId = created.id,
                    remoteSizeBytes = created.size,
                    remoteMtimeMs = created.lastModifiedMs,
                    remoteEtag = created.eTag,
                ),
            )
            localFileAccess.put("notes.txt", "v2-longer".toByteArray())

            val outcome = buildEngine().runTargetedUploads(pair, listOf("notes.txt"))

            assertTrue(outcome.result is SyncEngine.Result.Success)
            // No second remote file was created: the existing remote object was updated in place.
            assertEquals(listOf(created.id), fakeProvider.list("remote-root").map { it.id })
            assertEquals("v2-longer".length.toLong(), fakeProvider.getMetadata(created.id).size)
            assertEquals(created.id, localIndexDao.get(pair.id, "notes.txt")?.remoteId)
        }

    @Test
    fun `skips every path for download-only directions without uploading`() =
        runTest {
            val pair = insertPair(direction = SyncDirection.REMOTE_TO_LOCAL)
            localFileAccess.put("notes.txt", "hello".toByteArray())

            val outcome = buildEngine().runTargetedUploads(pair, listOf("notes.txt"))

            // A download-only pair is a valid configuration, so the run must not mark the
            // pair as broken; the candidates are simply reported as permanently skipped.
            assertTrue("Expected Success, got: ${outcome.result}", outcome.result is SyncEngine.Result.Success)
            assertEquals(0, outcome.result.applied)
            assertEquals(listOf("notes.txt"), outcome.skippedPaths)
            assertEquals(emptyList<String>(), outcome.uploadedPaths)
            assertTrue(fakeProvider.list("remote-root").isEmpty())
        }

    @Test
    fun `skips every path for the download-and-delete-remote direction`() =
        runTest {
            val pair = insertPair(direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS)
            localFileAccess.put("notes.txt", "hello".toByteArray())

            val outcome = buildEngine().runTargetedUploads(pair, listOf("notes.txt"))

            assertTrue(outcome.result is SyncEngine.Result.Success)
            assertEquals(listOf("notes.txt"), outcome.skippedPaths)
            assertTrue(fakeProvider.list("remote-root").isEmpty())
        }

    @Test
    fun `skips out-of-scope paths and uploads the remaining ones`() =
        runTest {
            val pair = insertPair(excludeGlobs = "*.tmp")
            localFileAccess.put("notes.txt", "hello".toByteArray())
            localFileAccess.put("scratch.tmp", "junk".toByteArray())

            val outcome = buildEngine().runTargetedUploads(pair, listOf("notes.txt", "scratch.tmp"))

            assertTrue("Expected Success, got: ${outcome.result}", outcome.result is SyncEngine.Result.Success)
            assertEquals(listOf("notes.txt"), outcome.uploadedPaths)
            assertEquals(listOf("scratch.tmp"), outcome.skippedPaths)
            assertEquals(listOf("notes.txt"), fakeProvider.list("remote-root").map { it.name })
        }

    @Test
    fun `returns Success without uploading when every path is out of scope`() =
        runTest {
            val pair = insertPair(excludeGlobs = "*.tmp")
            localFileAccess.put("scratch.tmp", "junk".toByteArray())

            val outcome = buildEngine().runTargetedUploads(pair, listOf("scratch.tmp"))

            assertTrue(outcome.result is SyncEngine.Result.Success)
            assertEquals(0, outcome.result.applied)
            assertEquals(listOf("scratch.tmp"), outcome.skippedPaths)
            assertTrue(fakeProvider.list("remote-root").isEmpty())
        }

    @Test
    fun `collapses duplicate paths into a single upload`() =
        runTest {
            val pair = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray())

            val outcome = buildEngine().runTargetedUploads(pair, listOf("notes.txt", "notes.txt"))

            assertEquals(1, outcome.result.applied)
            assertEquals(listOf("notes.txt"), outcome.uploadedPaths)
        }

    @Test
    fun `reports PartialFailure and the failed path when one upload fails`() =
        runTest {
            val pair = insertPair()
            localFileAccess.put("ok.txt", "hello".toByteArray())
            // "missing.txt" is claimed but no longer readable locally, so its op fails.

            val outcome = buildEngine().runTargetedUploads(pair, listOf("ok.txt", "missing.txt"))

            assertTrue(
                "Expected PartialFailure, got: ${outcome.result}",
                outcome.result is SyncEngine.Result.PartialFailure,
            )
            assertEquals(1, outcome.result.applied)
            assertEquals(listOf("ok.txt"), outcome.uploadedPaths)
            assertEquals(listOf("missing.txt"), outcome.failedPaths)
        }

    @Test
    fun `does not persist remote baseline when file mutates during targeted upload`() =
        runTest {
            val pair = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray(), mtimeMs = 5_000L)
            localFileAccess.mutateAfterNextRead("notes.txt", "hello changed".toByteArray(), mtimeMs = 6_000L)

            val outcome = buildEngine().runTargetedUploads(pair, listOf("notes.txt"))

            assertTrue(
                "Expected PartialFailure for mutated upload, got: ${outcome.result}",
                outcome.result is SyncEngine.Result.PartialFailure,
            )
            assertEquals(emptyList<String>(), outcome.uploadedPaths)
            assertEquals(listOf("notes.txt"), outcome.failedPaths)
            assertNull(localIndexDao.get(pair.id, "notes.txt")?.remoteId)
            // The uploaded remote result is removed again, so the mutated file cannot leave
            // a half-written orphan behind for the retry to duplicate.
            assertTrue(fakeProvider.list("remote-root").isEmpty())
        }

    @Test
    fun `maps AuthenticationRequired to Terminal needing reauth`() =
        runTest {
            val pair = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray())
            val provider =
                object : CloudProvider by fakeProvider {
                    override suspend fun uploadNew(
                        parentId: String,
                        name: String,
                        content: InputStream,
                        size: Long,
                        mimeType: String?,
                    ): RemoteFile = throw CloudProviderException.AuthenticationRequired("token expired")
                }

            val outcome = buildEngine(provider).runTargetedUploads(pair, listOf("notes.txt"))

            val result = outcome.result
            assertTrue("Expected Terminal, got: $result", result is SyncEngine.Result.Terminal)
            assertTrue((result as SyncEngine.Result.Terminal).needsReauth)
            assertEquals(listOf("notes.txt"), outcome.failedPaths)
        }

    @Test
    fun `maps LocalStorageException to Terminal needing re-link`() =
        runTest {
            val pair = insertPair()
            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> throw LocalStorageException("SAF permission revoked") },
                )

            val outcome = engine.runTargetedUploads(pair, listOf("notes.txt"))

            val result = outcome.result
            assertTrue("Expected Terminal, got: $result", result is SyncEngine.Result.Terminal)
            assertTrue((result as SyncEngine.Result.Terminal).needsReLink)
            assertEquals(listOf("notes.txt"), outcome.failedPaths)
        }

    @Test
    fun `returns Terminal when the pair is not linked to an account`() =
        runTest {
            val pair = insertPair().copy(accountId = null)
            localFileAccess.put("notes.txt", "hello".toByteArray())

            val outcome = buildEngine().runTargetedUploads(pair, listOf("notes.txt"))

            assertTrue(outcome.result is SyncEngine.Result.Terminal)
            assertTrue((outcome.result as SyncEngine.Result.Terminal).needsReLink)
            assertTrue(fakeProvider.list("remote-root").isEmpty())
        }

    @Test
    fun `returns Terminal when the provider is not registered`() =
        runTest {
            val pair = insertPair()
            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = emptyMap(),
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )

            val outcome = engine.runTargetedUploads(pair, listOf("notes.txt"))

            assertTrue(outcome.result is SyncEngine.Result.Terminal)
        }

    @Test
    fun `returns Success with no work for an empty path list`() =
        runTest {
            val pair = insertPair()

            val outcome = buildEngine().runTargetedUploads(pair, emptyList())

            assertTrue(outcome.result is SyncEngine.Result.Success)
            assertEquals(0, outcome.result.applied)
            assertNull(localIndexDao.get(pair.id, "notes.txt"))
        }
}
