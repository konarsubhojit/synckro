package com.konarsubhojit.synckro.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.konarsubhojit.synckro.data.local.dao.FileIndexDao
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.data.local.db.SynckroDatabase
import com.konarsubhojit.synckro.data.local.entity.FileIndexEntity
import com.konarsubhojit.synckro.data.local.entity.SyncPairEntity
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection
import com.konarsubhojit.synckro.domain.scan.LocalFolderScanner
import com.konarsubhojit.synckro.domain.scan.ScanProgress
import kotlinx.coroutines.flow.toList
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
 * Robolectric unit tests for [LocalFolderScannerImpl].
 *
 * The [DocumentChildrenQuery] is replaced with a pure in-memory fake so these
 * tests run on the JVM without a live ContentProvider.
 *
 * Tree URI used throughout: `content://com.example.test/tree/root`
 * Root document ID as returned by [DocumentsContract.getTreeDocumentId]: `"root"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalFolderScannerImplTest {

    private lateinit var db: SynckroDatabase
    private lateinit var syncPairDao: SyncPairDao
    private lateinit var fileIndexDao: FileIndexDao

    // SAF tree URI whose path segment after "tree/" is "root" so that
    // DocumentsContract.getTreeDocumentId returns "root".
    private val treeUri = Uri.parse("content://com.example.test/tree/root")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncPairDao = db.syncPairDao()
        fileIndexDao = db.fileIndexDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun insertPair(): Long = syncPairDao.insert(
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
        )
    )

    /** Constructs a [LocalFolderScannerImpl] with a pure in-memory fake query. */
    private fun scannerWith(fakeTree: Map<String, List<RawDocChild>>): LocalFolderScannerImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeQuery = DocumentChildrenQuery { _, _, parentDocId ->
            fakeTree[parentDocId] ?: emptyList()
        }
        return LocalFolderScannerImpl(context, fileIndexDao, fakeQuery)
    }

    /** Builds a flat [RawDocChild] representing a regular file. */
    private fun file(
        name: String,
        size: Long = 100L,
        lastModifiedMs: Long = 1_000L,
        mimeType: String = "application/octet-stream",
    ) = RawDocChild(
        docId = name,
        name = name,
        size = size,
        lastModifiedMs = lastModifiedMs,
        mimeType = mimeType,
    )

    /** Builds a [RawDocChild] representing a directory. */
    private fun dir(name: String, docId: String = name) = RawDocChild(
        docId = docId,
        name = name,
        size = 0L,
        lastModifiedMs = 0L,
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
    )

    /**
     * Converts a list of relative file paths into a fake document-tree map suitable
     * for [DocumentChildrenQuery].
     *
     * Each path like "subdir/nested/file.txt" produces directory nodes at "root",
     * "subdir", and "subdir/nested", and a file node at "subdir/nested/file.txt".
     * Duplicate directory entries are deduplicated automatically.
     */
    private fun buildFakeTree(
        files: List<Triple<String, Long, Long>>,  // (relativePath, size, lastModifiedMs)
    ): Map<String, List<RawDocChild>> {
        val tree = mutableMapOf<String, MutableList<RawDocChild>>()
        tree["root"] = mutableListOf()

        for ((path, size, mtime) in files) {
            val parts = path.split("/")
            var parentKey = "root"
            for (i in 0 until parts.size - 1) {
                val dirDocId = parts.take(i + 1).joinToString("/")
                val dirName = parts[i]
                // Add this directory to its parent (once).
                if (tree[dirDocId] == null) {
                    tree[dirDocId] = mutableListOf()
                    tree.getOrPut(parentKey) { mutableListOf() }
                        .add(dir(name = dirName, docId = dirDocId))
                }
                parentKey = dirDocId
            }
            // Add the file itself under its immediate parent.
            tree.getOrPut(parentKey) { mutableListOf() }
                .add(file(name = parts.last(), size = size, lastModifiedMs = mtime))
        }
        return tree
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `fresh scan of three root-level files adds all to index`() = runTest {
        val pairId = insertPair()

        val fakeTree = mapOf(
            "root" to listOf(
                file("alpha.txt", size = 100, lastModifiedMs = 1_000),
                file("beta.txt", size = 200, lastModifiedMs = 2_000),
                file("gamma.txt", size = 300, lastModifiedMs = 3_000),
            )
        )
        val scanner = scannerWith(fakeTree)

        val events = scanner.scan(pairId, treeUri).toList()

        val done = events.filterIsInstance<ScanProgress.Done>().single()
        assertEquals("added", 3, done.added)
        assertEquals("updated", 0, done.updated)
        assertEquals("deleted", 0, done.deleted)

        val indexed = fileIndexDao.getForPair(pairId)
        assertEquals(3, indexed.size)
        assertTrue(indexed.any { it.relativePath == "alpha.txt" && it.localSize == 100L })
        assertTrue(indexed.any { it.relativePath == "beta.txt" && it.localSize == 200L })
        assertTrue(indexed.any { it.relativePath == "gamma.txt" && it.localSize == 300L })
    }

    @Test
    fun `scan stores mimeType on indexed entries`() = runTest {
        val pairId = insertPair()

        val fakeTree = mapOf(
            "root" to listOf(
                RawDocChild("image.jpg", "image.jpg", 1024L, 5_000L, "image/jpeg"),
            )
        )
        val scanner = scannerWith(fakeTree)
        scanner.scan(pairId, treeUri).toList()

        val entry = fileIndexDao.getForPair(pairId).single()
        assertEquals("image/jpeg", entry.mimeType)
    }

    @Test
    fun `unchanged file is not upserted and existing hash is preserved`() = runTest {
        val pairId = insertPair()

        // Seed the index with a file that already has a hash.
        fileIndexDao.upsertAll(
            listOf(
                FileIndexEntity(
                    pairId = pairId,
                    relativePath = "readme.md",
                    localSize = 512L,
                    localLastModifiedMs = 9_000L,
                    localHash = "abc123hash",
                    remoteId = null,
                    remoteETag = null,
                    remoteSize = null,
                    remoteLastModifiedMs = null,
                    mimeType = "text/markdown",
                )
            )
        )

        // Scan returns the same size + mtime → should preserve the existing hash.
        val fakeTree = mapOf(
            "root" to listOf(
                RawDocChild("readme.md", "readme.md", 512L, 9_000L, "text/markdown"),
            )
        )
        val scanner = scannerWith(fakeTree)
        val events = scanner.scan(pairId, treeUri).toList()

        val done = events.filterIsInstance<ScanProgress.Done>().single()
        // No metadata change → not counted as updated.
        assertEquals("updated", 0, done.updated)

        val entry = fileIndexDao.getForPair(pairId).single()
        assertEquals("hash must be preserved", "abc123hash", entry.localHash)
    }

    @Test
    fun `modified file is re-indexed with null hash`() = runTest {
        val pairId = insertPair()

        // Seed with existing entry.
        fileIndexDao.upsertAll(
            listOf(
                FileIndexEntity(
                    pairId = pairId,
                    relativePath = "doc.pdf",
                    localSize = 1_000L,
                    localLastModifiedMs = 1_000L,
                    localHash = "oldhash",
                    remoteId = null,
                    remoteETag = null,
                    remoteSize = null,
                    remoteLastModifiedMs = null,
                )
            )
        )

        // Scan returns a larger file → hash must be cleared.
        val fakeTree = mapOf(
            "root" to listOf(
                RawDocChild("doc.pdf", "doc.pdf", 2_000L, 1_000L, "application/pdf"),
            )
        )
        val scanner = scannerWith(fakeTree)
        val events = scanner.scan(pairId, treeUri).toList()

        val done = events.filterIsInstance<ScanProgress.Done>().single()
        assertEquals("updated", 1, done.updated)

        val entry = fileIndexDao.getForPair(pairId).single()
        assertEquals("size must be updated", 2_000L, entry.localSize)
        assertNull("stale hash must be cleared", entry.localHash)
    }

    @Test
    fun `removed file is deleted from index on second scan`() = runTest {
        val pairId = insertPair()

        // First scan: two files.
        val fakeTree1 = mapOf(
            "root" to listOf(
                file("keep.txt", size = 10, lastModifiedMs = 100),
                file("remove.txt", size = 20, lastModifiedMs = 200),
            )
        )
        scannerWith(fakeTree1).scan(pairId, treeUri).toList()
        assertEquals(2, fileIndexDao.getForPair(pairId).size)

        // Second scan: remove.txt is gone.
        val fakeTree2 = mapOf(
            "root" to listOf(
                file("keep.txt", size = 10, lastModifiedMs = 100),
            )
        )
        val events = scannerWith(fakeTree2).scan(pairId, treeUri).toList()

        val done = events.filterIsInstance<ScanProgress.Done>().single()
        assertEquals("deleted", 1, done.deleted)

        val indexed = fileIndexDao.getForPair(pairId)
        assertEquals(1, indexed.size)
        assertEquals("keep.txt", indexed.single().relativePath)
    }

    @Test
    fun `nested folder fixture of 50 files is indexed correctly`() = runTest {
        val pairId = insertPair()

        // Build a 50-file fixture: 5 top-level files + 5 directories × 8 files + 1 nested dir × 5 files
        // = 5 + 40 + 5 = 50 files.
        val files = mutableListOf<Triple<String, Long, Long>>()

        // 5 top-level files
        repeat(5) { i ->
            files += Triple("root_file_$i.txt", (i + 1) * 100L, (i + 1) * 1_000L)
        }
        // 5 directories × 8 files each
        repeat(5) { d ->
            repeat(8) { f ->
                files += Triple("dir$d/file_${d}_$f.txt", (f + 1) * 50L, (f + 1) * 500L)
            }
        }
        // 1 nested directory with 5 files (dir0/nested/)
        repeat(5) { i ->
            files += Triple("dir0/nested/nested_file_$i.bin", (i + 1) * 200L, (i + 1) * 2_000L)
        }

        assertEquals("fixture must contain exactly 50 files", 50, files.size)

        val fakeTree = buildFakeTree(files)
        val scanner = scannerWith(fakeTree)

        val events = scanner.scan(pairId, treeUri).toList()

        val done = events.filterIsInstance<ScanProgress.Done>().single()
        assertEquals("all 50 files must be added", 50, done.added)
        assertEquals("no updates on first scan", 0, done.updated)
        assertEquals("no deletions on first scan", 0, done.deleted)

        val indexed = fileIndexDao.getForPair(pairId)
        assertEquals("index must contain exactly 50 entries", 50, indexed.size)
    }

    @Test
    fun `scanning emits Scanning progress events every PROGRESS_INTERVAL files`() = runTest {
        val pairId = insertPair()

        // Create exactly PROGRESS_INTERVAL * 2 files to get exactly 2 Scanning events.
        val count = LocalFolderScanner.PROGRESS_INTERVAL * 2
        val fakeTree = mapOf(
            "root" to (1..count).map { i -> file("file_$i.dat", size = i.toLong()) }
        )
        val scanner = scannerWith(fakeTree)

        val events = scanner.scan(pairId, treeUri).toList()

        val scanningEvents = events.filterIsInstance<ScanProgress.Scanning>()
        assertEquals(
            "expected exactly 2 Scanning events for $count files",
            2,
            scanningEvents.size,
        )
        assertEquals(LocalFolderScanner.PROGRESS_INTERVAL, scanningEvents[0].filesScanned)
        assertEquals(LocalFolderScanner.PROGRESS_INTERVAL * 2, scanningEvents[1].filesScanned)

        val done = events.filterIsInstance<ScanProgress.Done>().single()
        assertEquals(count, done.added)
    }

    @Test
    fun `empty tree results in zero-count Done event`() = runTest {
        val pairId = insertPair()

        val fakeTree = mapOf("root" to emptyList<RawDocChild>())
        val scanner = scannerWith(fakeTree)

        val events = scanner.scan(pairId, treeUri).toList()

        val done = events.filterIsInstance<ScanProgress.Done>().single()
        assertEquals(0, done.added)
        assertEquals(0, done.updated)
        assertEquals(0, done.deleted)
        assertTrue(fileIndexDao.getForPair(pairId).isEmpty())
    }

    @Test
    fun `incremental re-scan leaves unchanged entries untouched`() = runTest {
        val pairId = insertPair()

        val files = buildFakeTree(
            listOf(
                Triple("a.txt", 1L, 1L),
                Triple("b.txt", 2L, 2L),
                Triple("sub/c.txt", 3L, 3L),
            )
        )
        val scanner = scannerWith(files)

        // First scan — all three added.
        scanner.scan(pairId, treeUri).toList()
        assertEquals(3, fileIndexDao.getForPair(pairId).size)

        // Second scan — nothing changed.
        val events2 = scanner.scan(pairId, treeUri).toList()
        val done2 = events2.filterIsInstance<ScanProgress.Done>().single()
        assertEquals("added on re-scan", 0, done2.added)
        assertEquals("updated on re-scan", 0, done2.updated)
        assertEquals("deleted on re-scan", 0, done2.deleted)

        // Index unchanged.
        assertEquals(3, fileIndexDao.getForPair(pairId).size)
    }

    @Test
    fun `remote columns are preserved during incremental scan`() = runTest {
        val pairId = insertPair()

        // Seed an entry that already has remote columns set (e.g. after a prior sync).
        fileIndexDao.upsertAll(
            listOf(
                FileIndexEntity(
                    pairId = pairId,
                    relativePath = "synced.txt",
                    localSize = 999L,
                    localLastModifiedMs = 8_000L,
                    localHash = "existinghash",
                    remoteId = "remote-doc-id",
                    remoteETag = "etag-v2",
                    remoteSize = 999L,
                    remoteLastModifiedMs = 8_000L,
                )
            )
        )

        // Scan: same size+mtime → should preserve all columns.
        val fakeTree = mapOf(
            "root" to listOf(
                RawDocChild("synced.txt", "synced.txt", 999L, 8_000L, "text/plain"),
            )
        )
        val scanner = scannerWith(fakeTree)
        scanner.scan(pairId, treeUri).toList()

        val entry = fileIndexDao.getForPair(pairId).single()
        assertEquals("remoteId preserved", "remote-doc-id", entry.remoteId)
        assertEquals("remoteETag preserved", "etag-v2", entry.remoteETag)
        assertEquals("remoteSize preserved", 999L, entry.remoteSize)
        assertEquals("hash preserved", "existinghash", entry.localHash)
    }
}
