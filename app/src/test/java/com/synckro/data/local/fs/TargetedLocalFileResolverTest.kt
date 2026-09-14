package com.synckro.data.local.fs

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.local.entity.PendingUploadEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.scanner.DocumentChildrenQuery
import com.synckro.data.scanner.RawDocChild
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
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TargetedLocalFileResolverTest {
    private val treeUri = Uri.parse("content://com.example.test/tree/root")

    private lateinit var db: SynckroDatabase
    private lateinit var syncPairDao: SyncPairDao
    private lateinit var localIndexDao: LocalIndexDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        syncPairDao = db.syncPairDao()
        localIndexDao = db.localIndexDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `document ID hint resolves one file without child enumeration and reuses matching cached hash`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsert(LocalIndexEntity(pairId, "docs/report.txt", 10L, 2_000L, contentHash = "cached"))
            val fs = CountingFsAccess(mapOf("doc-1" to "changed bytes".toByteArray()))
            val children = CountingChildrenQuery()

            val result =
                resolverWith(
                    childrenQuery = children,
                    metadata = mapOf("doc-1" to SafDocumentMetadata(10L, 2_000L, "text/plain")),
                    fsAccess = fs,
                ).resolve(upload(pairId, path = "docs/report.txt", documentIdHint = "doc-1"))

            assertTrue(result is TargetedLocalFileResolution.Resolved)
            val resolved = result as TargetedLocalFileResolution.Resolved
            assertEquals(LocalFileEntry("docs/report.txt", 10L, 2_000L, "cached"), resolved.entry)
            assertEquals("doc-1", resolved.documentId)
            assertEquals("document ID hint should avoid path traversal", 0, children.queryCount)
            assertEquals("one open verifies read access; cached hash avoids a second open", 1, fs.openCount)
            assertEquals("changed bytes", resolved.openRead()?.use { it.reader().readText() })
        }

    @Test
    fun `stale cached hash is recomputed and saved when size changes`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsert(LocalIndexEntity(pairId, "report.txt", 10L, 2_000L, contentHash = "stale"))
            val bytes = "new content".toByteArray()
            val expectedHash = LocalFsEnumerator.sha256Hex(bytes.inputStream())
            val fs = CountingFsAccess(mapOf("doc-1" to bytes))

            val result =
                resolverWith(
                    metadata = mapOf("doc-1" to SafDocumentMetadata(bytes.size.toLong(), 2_000L, "text/plain")),
                    fsAccess = fs,
                ).resolve(upload(pairId, path = "report.txt", documentIdHint = "doc-1"))

            assertTrue(result is TargetedLocalFileResolution.Resolved)
            assertEquals(expectedHash, (result as TargetedLocalFileResolution.Resolved).entry.contentHash)
            assertEquals(expectedHash, localIndexDao.get(pairId, "report.txt")?.contentHash)
            assertEquals("one open probes access and one recomputes the stale hash", 2, fs.openCount)
        }

    @Test
    fun `stale cached hash is cleared when changed file cannot be read`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsert(LocalIndexEntity(pairId, "locked.txt", 10L, 2_000L, contentHash = "stale"))

            val result =
                resolverWith(
                    metadata = mapOf("doc-1" to SafDocumentMetadata(20L, 3_000L, "text/plain")),
                    fsAccess = CountingFsAccess(),
                ).resolve(upload(pairId, path = "locked.txt", documentIdHint = "doc-1"))

            assertEquals(
                TargetedLocalFileResolution.Unavailable(
                    TargetedLocalFileResolution.Unavailable.Reason.NO_READ_ACCESS,
                ),
                result,
            )
            assertNull(localIndexDao.get(pairId, "locked.txt")?.contentHash)
            assertEquals(20L, localIndexDao.get(pairId, "locked.txt")?.sizeBytes)
        }

    @Test
    fun `missing target deletes indexed row and returns missing`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsert(LocalIndexEntity(pairId, "gone.txt", 10L, 2_000L, contentHash = "old"))

            val result =
                resolverWith()
                    .resolve(upload(pairId, path = "gone.txt", documentIdHint = "deleted-doc"))

            assertEquals(TargetedLocalFileResolution.Missing, result)
            assertNull(localIndexDao.get(pairId, "gone.txt"))
        }

    @Test
    fun `out of scope target deletes indexed row without probing provider`() =
        runTest {
            val pairId = insertPair()
            localIndexDao.upsert(LocalIndexEntity(pairId, "tmp/ignored.log", 10L, 2_000L, contentHash = "old"))
            val children = CountingChildrenQuery()
            val fs = CountingFsAccess(mapOf("doc-1" to "content".toByteArray()))

            val result =
                resolverWith(childrenQuery = children, fsAccess = fs)
                    .resolve(
                        upload(pairId, path = "tmp/ignored.log", documentIdHint = "doc-1"),
                        includeGlobs = listOf("*.txt"),
                    )

            assertEquals(TargetedLocalFileResolution.OutOfScope, result)
            assertNull(localIndexDao.get(pairId, "tmp/ignored.log"))
            assertEquals(0, children.queryCount)
            assertEquals(0, fs.openCount)
        }

    @Test
    fun `permission loss is an explicit unavailable outcome`() =
        runTest {
            val pairId = insertPair()
            val throwingChildren =
                DocumentChildrenQuery { _, _, _ ->
                    throw SecurityException("Permission denied")
                }

            val result =
                resolverWith(childrenQuery = throwingChildren)
                    .resolve(upload(pairId, path = "nested/file.txt", documentIdHint = null))

            assertEquals(
                TargetedLocalFileResolution.Unavailable(
                    TargetedLocalFileResolution.Unavailable.Reason.PERMISSION_LOST,
                ),
                result,
            )
        }

    private fun resolverWith(
        childrenQuery: DocumentChildrenQuery = CountingChildrenQuery(),
        metadata: Map<String, SafDocumentMetadata> = emptyMap(),
        fsAccess: FsAccess = CountingFsAccess(),
    ): TargetedLocalFileResolver {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TargetedLocalFileResolver(
            resolver = context.contentResolver,
            treeUri = treeUri,
            localIndexDao = localIndexDao,
            childrenQuery = childrenQuery,
            metadataQuery = SafDocumentMetadataQuery { _, _, documentId -> metadata[documentId] },
            fsAccess = fsAccess,
        )
    }

    private suspend fun insertPair(): Long =
        syncPairDao.insert(
            SyncPairEntity(
                displayName = "Test Pair",
                localTreeUri = treeUri.toString(),
                provider = CloudProviderType.ONEDRIVE,
                remoteFolderId = "remote-root",
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                includeGlobs = "",
                excludeGlobs = "",
                wifiOnly = false,
                requiresCharging = false,
            ),
        )

    private fun upload(
        pairId: Long,
        path: String,
        documentIdHint: String?,
    ) = PendingUploadEntity(
        pairId = pairId,
        relativePath = path,
        documentIdHint = documentIdHint,
        observedSizeBytes = 10L,
        observedMtimeMs = 2_000L,
        eligibleAtMs = 1_000L,
        createdAtMs = 500L,
        updatedAtMs = 500L,
    )

    private class CountingChildrenQuery(
        private val tree: Map<String, List<RawDocChild>> = emptyMap(),
    ) : DocumentChildrenQuery {
        var queryCount = 0

        override fun invoke(
            resolver: android.content.ContentResolver,
            treeUri: Uri,
            parentDocId: String,
        ): List<RawDocChild> {
            queryCount++
            return tree[parentDocId] ?: emptyList()
        }
    }

    private class CountingFsAccess(
        private val filesByDocId: Map<String, ByteArray> = emptyMap(),
    ) : FsAccess {
        var openCount = 0

        override fun openInputStream(
            treeUri: Uri,
            docId: String,
        ): InputStream? {
            openCount++
            return filesByDocId[docId]?.inputStream()
        }
    }
}
