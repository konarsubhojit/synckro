package com.synckro.data.local.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.LocalIndexEntity
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

/**
 * Robolectric unit tests for [LocalFsEnumerator].
 *
 * Both [DocumentChildrenQuery] and [FsAccess] are replaced with pure in-memory
 * fakes, keeping all IO off the device and making tests run on the JVM.
 *
 * Tree URI used throughout: `content://com.example.test/tree/root`
 * Root document ID (from [DocumentsContract.getTreeDocumentId]): `"root"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalFsEnumeratorTest {
    private lateinit var db: SynckroDatabase
    private lateinit var syncPairDao: SyncPairDao
    private lateinit var localIndexDao: LocalIndexDao

    private val treeUri = Uri.parse("content://com.example.test/tree/root")

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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    /**
     * Counting [FsAccess] fake.  Each call to [openInputStream] increments [openCount]
     * and returns the bytes registered for that docId (or `null` if absent).
     */
    private inner class FakeFsAccess(
        private val files: Map<String, ByteArray> = emptyMap(),
    ) : FsAccess {
        var openCount = 0

        override fun openInputStream(
            treeUri: Uri,
            docId: String,
        ): InputStream? {
            openCount++
            return files[docId]?.inputStream()
        }
    }

    /** Builds a flat [RawDocChild] representing a regular file. */
    private fun file(
        name: String,
        docId: String = name,
        size: Long = 100L,
        lastModifiedMs: Long = 1_000L,
        mimeType: String = "application/octet-stream",
    ) = RawDocChild(
        docId = docId,
        name = name,
        size = size,
        lastModifiedMs = lastModifiedMs,
        mimeType = mimeType,
    )

    /** Builds a [RawDocChild] representing a directory. */
    private fun dir(
        name: String,
        docId: String = name,
    ) = RawDocChild(
        docId = docId,
        name = name,
        size = 0L,
        lastModifiedMs = 0L,
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
    )

    /**
     * Builds an [LocalFsEnumerator] wired to in-memory fakes.
     *
     * @param fakeTree  Map from parentDocId → list of children (mirrors the SAF tree).
     * @param fsAccess  Fake [FsAccess] instance (optional; defaults to one with no files).
     */
    private fun enumeratorWith(
        fakeTree: Map<String, List<RawDocChild>>,
        fsAccess: FsAccess = FakeFsAccess(),
    ): LocalFsEnumerator {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeQuery =
            DocumentChildrenQuery { _, _, parentDocId ->
                fakeTree[parentDocId] ?: emptyList()
            }
        return LocalFsEnumerator(
            resolver = context.contentResolver,
            localIndexDao = localIndexDao,
            childrenQuery = fakeQuery,
            fsAccess = fsAccess,
        )
    }

    /**
     * Converts a flat list of (relativePath, size, mtime) into a fake document-tree
     * map compatible with [DocumentChildrenQuery].
     *
     * Intermediate directory nodes are generated automatically and deduplicated.
     */
    private fun buildFakeTree(
        files: List<Triple<String, Long, Long>>,
        fileBytes: Map<String, ByteArray> = emptyMap(),
    ): Pair<Map<String, List<RawDocChild>>, Map<String, ByteArray>> {
        val tree = mutableMapOf<String, MutableList<RawDocChild>>()
        tree["root"] = mutableListOf()

        for ((path, size, mtime) in files) {
            val parts = path.split("/")
            var parentKey = "root"
            for (i in 0 until parts.size - 1) {
                val dirDocId = parts.take(i + 1).joinToString("/")
                val dirName = parts[i]
                if (!tree.containsKey(dirDocId)) {
                    tree[dirDocId] = mutableListOf()
                    tree
                        .getOrPut(parentKey) { mutableListOf() }
                        .add(dir(name = dirName, docId = dirDocId))
                }
                parentKey = dirDocId
            }
            val leafName = parts.last()
            val leafDocId = path // use full path as docId for uniqueness
            tree
                .getOrPut(parentKey) { mutableListOf() }
                .add(file(name = leafName, docId = leafDocId, size = size, lastModifiedMs = mtime))
        }

        // Build bytes map: keyed by full path (which we use as docId above).
        val bytesMap =
            files.associate { (path, _, _) ->
                path to (fileBytes[path] ?: path.toByteArray())
            }
        return tree to bytesMap
    }

    // -------------------------------------------------------------------------
    // Basic enumeration
    // -------------------------------------------------------------------------

    @Test
    fun `fresh scan of three root-level files adds all to local_index`() =
        runTest {
            val pairId = insertPair()

            val fs =
                FakeFsAccess(
                    mapOf(
                        "alpha.txt" to "hello".toByteArray(),
                        "beta.txt" to "world".toByteArray(),
                        "gamma.txt" to "!".toByteArray(),
                    ),
                )
            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("alpha.txt", size = 5, lastModifiedMs = 1_000),
                            file("beta.txt", size = 5, lastModifiedMs = 2_000),
                            file("gamma.txt", size = 1, lastModifiedMs = 3_000),
                        ),
                )
            val result = enumeratorWith(fakeTree, fs).enumerate(pairId, treeUri)

            assertEquals(3, result.snapshot.size)
            assertEquals(setOf("alpha.txt", "beta.txt", "gamma.txt"), result.added)
            assertTrue(result.modified.isEmpty())
            assertTrue(result.deleted.isEmpty())

            val indexed = localIndexDao.getForPair(pairId)
            assertEquals(3, indexed.size)
            assertTrue(indexed.any { it.relativePath == "alpha.txt" && it.sizeBytes == 5L })
        }

    @Test
    fun `empty tree produces empty result`() =
        runTest {
            val pairId = insertPair()
            val result = enumeratorWith(mapOf("root" to emptyList())).enumerate(pairId, treeUri)

            assertTrue(result.snapshot.isEmpty())
            assertTrue(result.added.isEmpty())
            assertTrue(result.modified.isEmpty())
            assertTrue(result.deleted.isEmpty())
            assertTrue(localIndexDao.getForPair(pairId).isEmpty())
        }

    // -------------------------------------------------------------------------
    // Lazy SHA-256 hash strategy
    // -------------------------------------------------------------------------

    @Test
    fun `new file gets SHA-256 hash computed`() =
        runTest {
            val pairId = insertPair()
            val content = "test content".toByteArray()
            val expectedHash = LocalFsEnumerator.sha256Hex(content.inputStream())

            val fs = FakeFsAccess(mapOf("new.txt" to content))
            val result =
                enumeratorWith(mapOf("root" to listOf(file("new.txt", size = content.size.toLong()))), fs)
                    .enumerate(pairId, treeUri)

            val entry = result.snapshot.single()
            assertEquals(expectedHash, entry.contentHash)
            assertEquals(1, fs.openCount)
        }

    @Test
    fun `unchanged file reuses cached hash without re-reading`() =
        runTest {
            val pairId = insertPair()

            // Seed local_index with a pre-existing hash.
            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pairId,
                    relativePath = "readme.md",
                    sizeBytes = 512L,
                    mtimeMs = 9_000L,
                    contentHash = "cachedHash",
                ),
            )

            val fs = FakeFsAccess(mapOf("readme.md" to "data".toByteArray()))
            val result =
                enumeratorWith(
                    mapOf("root" to listOf(file("readme.md", size = 512, lastModifiedMs = 9_000))),
                    fs,
                ).enumerate(pairId, treeUri)

            // Hash must be the cached one, and the file must NOT be opened.
            assertEquals("cachedHash", result.snapshot.single().contentHash)
            assertEquals("file should not be re-read when size+mtime unchanged", 0, fs.openCount)
            assertTrue("unchanged file should not appear in modified", result.modified.isEmpty())
        }

    @Test
    fun `size change triggers hash recompute`() =
        runTest {
            val pairId = insertPair()

            localIndexDao.upsert(
                LocalIndexEntity(pairId, "doc.pdf", sizeBytes = 1_000L, mtimeMs = 1_000L, contentHash = "oldhash"),
            )

            val newContent = "new content".toByteArray()
            val expectedHash = LocalFsEnumerator.sha256Hex(newContent.inputStream())
            val fs = FakeFsAccess(mapOf("doc.pdf" to newContent))

            val result =
                enumeratorWith(
                    mapOf("root" to listOf(file("doc.pdf", size = newContent.size.toLong(), lastModifiedMs = 1_000))),
                    fs,
                ).enumerate(pairId, treeUri)

            assertEquals("new hash expected", expectedHash, result.snapshot.single().contentHash)
            assertEquals(1, fs.openCount)
            assertTrue("doc.pdf must be in modified", "doc.pdf" in result.modified)
        }

    @Test
    fun `mtime change triggers hash recompute`() =
        runTest {
            val pairId = insertPair()

            localIndexDao.upsert(
                LocalIndexEntity(pairId, "log.txt", sizeBytes = 100L, mtimeMs = 1_000L, contentHash = "oldhash"),
            )

            val fs = FakeFsAccess(mapOf("log.txt" to "data".toByteArray()))
            val result =
                enumeratorWith(
                    mapOf("root" to listOf(file("log.txt", size = 100, lastModifiedMs = 2_000))),
                    fs,
                ).enumerate(pairId, treeUri)

            assertEquals(1, fs.openCount)
            assertTrue("log.txt must be in modified", "log.txt" in result.modified)
        }

    @Test
    fun `inaccessible file has null hash but is still in snapshot`() =
        runTest {
            val pairId = insertPair()

            // FsAccess returns null → file cannot be opened.
            val fs = FakeFsAccess(emptyMap()) // no entry for "locked.bin"
            val result =
                enumeratorWith(
                    mapOf("root" to listOf(file("locked.bin", size = 100))),
                    fs,
                ).enumerate(pairId, treeUri)

            val entry = result.snapshot.single()
            assertEquals("locked.bin", entry.relativePath)
            assertNull("unreadable file should have null hash", entry.contentHash)
            assertEquals("locked.bin should still be in added", setOf("locked.bin"), result.added)
        }

    // -------------------------------------------------------------------------
    // Deleted files
    // -------------------------------------------------------------------------

    @Test
    fun `removed file is deleted from local_index`() =
        runTest {
            val pairId = insertPair()

            localIndexDao.upsertAll(
                listOf(
                    LocalIndexEntity(pairId, "keep.txt", 10L, 100L),
                    LocalIndexEntity(pairId, "remove.txt", 20L, 200L),
                ),
            )

            val fs = FakeFsAccess(mapOf("keep.txt" to "a".toByteArray()))
            val result =
                enumeratorWith(
                    mapOf("root" to listOf(file("keep.txt", size = 10, lastModifiedMs = 100))),
                    fs,
                ).enumerate(pairId, treeUri)

            assertEquals(setOf("remove.txt"), result.deleted)
            assertEquals(1, localIndexDao.getForPair(pairId).size)
            assertEquals("keep.txt", localIndexDao.getForPair(pairId).single().relativePath)
        }

    // -------------------------------------------------------------------------
    // Hidden files
    // -------------------------------------------------------------------------

    @Test
    fun `hidden files are skipped`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file(".hidden", size = 50),
                            file(".DS_Store", size = 10),
                            file("visible.txt", size = 100),
                        ),
                )
            val result = enumeratorWith(fakeTree).enumerate(pairId, treeUri)

            assertEquals(1, result.snapshot.size)
            assertEquals("visible.txt", result.snapshot.single().relativePath)
        }

    @Test
    fun `hidden files inside subdirectory are skipped`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to listOf(dir("subdir")),
                    "subdir" to
                        listOf(
                            file(".gitignore", size = 20),
                            file("main.kt", size = 300),
                        ),
                )
            val result = enumeratorWith(fakeTree).enumerate(pairId, treeUri)

            assertEquals(1, result.snapshot.size)
            assertEquals("subdir/main.kt", result.snapshot.single().relativePath)
        }

    // -------------------------------------------------------------------------
    // Ignore globs
    // -------------------------------------------------------------------------

    @Test
    fun `ignore glob filters matching files`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("build.gradle", size = 100),
                            file("main.kt", size = 200),
                            file("test.class", size = 300),
                        ),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, ignoreGlobs = listOf("*.class"))

            assertEquals(2, result.snapshot.size)
            assertTrue(result.snapshot.none { it.relativePath == "test.class" })
        }

    @Test
    fun `double-star glob matches across directories`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to listOf(dir("build"), file("main.kt")),
                    "build" to listOf(file("output.class"), file("output.jar")),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, ignoreGlobs = listOf("build/**"))

            assertEquals(1, result.snapshot.size)
            assertEquals("main.kt", result.snapshot.single().relativePath)
        }

    @Test
    fun `brace alternation glob matches either alternative`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("photo.jpg", size = 100),
                            file("photo.png", size = 200),
                            file("document.pdf", size = 300),
                        ),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, ignoreGlobs = listOf("*.{jpg,png}"))

            assertEquals(1, result.snapshot.size)
            assertEquals("document.pdf", result.snapshot.single().relativePath)
        }

    @Test
    fun `ignored file previously in local_index appears in deleted set`() =
        runTest {
            val pairId = insertPair()

            // Seed an entry that will now be filtered by an ignore glob.
            localIndexDao.upsert(
                LocalIndexEntity(pairId, "secret.log", sizeBytes = 50L, mtimeMs = 100L),
            )

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("secret.log", size = 50, lastModifiedMs = 100),
                            file("app.kt", size = 200, lastModifiedMs = 200),
                        ),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, ignoreGlobs = listOf("*.log"))

            assertTrue("secret.log must be deleted from index", "secret.log" in result.deleted)
            assertEquals(1, result.snapshot.size)
            assertEquals(0, localIndexDao.getForPair(pairId).count { it.relativePath == "secret.log" })
        }

    // -------------------------------------------------------------------------
    // Include globs
    // -------------------------------------------------------------------------

    @Test
    fun `include glob keeps only matching files`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("photo.jpg", size = 100),
                            file("document.pdf", size = 200),
                            file("notes.txt", size = 300),
                        ),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, includeGlobs = listOf("*.jpg"))

            assertEquals(1, result.snapshot.size)
            assertEquals("photo.jpg", result.snapshot.single().relativePath)
        }

    @Test
    fun `empty include glob keeps all files`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("photo.jpg", size = 100),
                            file("document.pdf", size = 200),
                            file("notes.txt", size = 300),
                        ),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, includeGlobs = emptyList())

            assertEquals(3, result.snapshot.size)
        }

    @Test
    fun `exclude glob takes precedence over include glob`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("image.jpg", size = 100),
                            file("thumb.jpg", size = 50),
                            file("notes.txt", size = 300),
                        ),
                )
            // Include all .jpg files, but exclude thumb.jpg specifically.
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(
                        pairId,
                        treeUri,
                        includeGlobs = listOf("*.jpg"),
                        ignoreGlobs = listOf("thumb.jpg"),
                    )

            assertEquals(1, result.snapshot.size)
            assertEquals("image.jpg", result.snapshot.single().relativePath)
        }

    @Test
    fun `include glob with double-star matches across directories`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to listOf(dir("src"), dir("docs"), file("readme.md")),
                    "src" to listOf(file("Main.kt", size = 200), file("build.log", size = 50)),
                    "docs" to listOf(file("guide.md", size = 300)),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, includeGlobs = listOf("**/*.kt"))

            assertEquals(1, result.snapshot.size)
            assertEquals("src/Main.kt", result.snapshot.single().relativePath)
        }

    @Test
    fun `non-included file previously in local_index appears in deleted set`() =
        runTest {
            val pairId = insertPair()

            // Seed an entry that will now be excluded by the include filter.
            localIndexDao.upsert(
                LocalIndexEntity(pairId, "archive.zip", sizeBytes = 500L, mtimeMs = 100L),
            )

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("archive.zip", size = 500, lastModifiedMs = 100),
                            file("app.kt", size = 200, lastModifiedMs = 200),
                        ),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, includeGlobs = listOf("*.kt"))

            assertTrue("archive.zip must appear in deleted set", "archive.zip" in result.deleted)
            assertEquals(1, result.snapshot.size)
            assertEquals("app.kt", result.snapshot.single().relativePath)
        }

    @Test
    fun `all invalid include patterns exclude all files (fail closed)`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("main.kt", size = 100),
                            file("readme.md", size = 200),
                        ),
                )
            // "[]" produces a regex character class with no content, which throws
            // PatternSyntaxException → mapNotNull drops it → compiledIncludeGlobs is empty.
            // The include filter is still active (includeGlobs.isNotEmpty()), so all files
            // are excluded (fail-closed) rather than all files passing.
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(pairId, treeUri, includeGlobs = listOf("[]"))

            assertEquals(
                "All files must be excluded when every include pattern is invalid",
                0,
                result.snapshot.size,
            )
        }

    // -------------------------------------------------------------------------
    // Nested directories
    // -------------------------------------------------------------------------

    @Test
    fun `nested directories are walked recursively`() =
        runTest {
            val pairId = insertPair()

            val (fakeTree, bytesMap) =
                buildFakeTree(
                    listOf(
                        Triple("a.txt", 10L, 100L),
                        Triple("sub/b.txt", 20L, 200L),
                        Triple("sub/nested/c.txt", 30L, 300L),
                    ),
                )
            val result = enumeratorWith(fakeTree, FakeFsAccess(bytesMap)).enumerate(pairId, treeUri)

            assertEquals(3, result.snapshot.size)
            val paths = result.snapshot.map { it.relativePath }.toSet()
            assertEquals(setOf("a.txt", "sub/b.txt", "sub/nested/c.txt"), paths)
        }

    @Test
    fun `empty directory produces no entries`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to listOf(dir("emptyDir"), file("root.txt")),
                    "emptyDir" to emptyList<RawDocChild>(),
                )
            val result = enumeratorWith(fakeTree).enumerate(pairId, treeUri)

            assertEquals(1, result.snapshot.size)
            assertEquals("root.txt", result.snapshot.single().relativePath)
        }

    // -------------------------------------------------------------------------
    // Unicode filenames
    // -------------------------------------------------------------------------

    @Test
    fun `unicode filenames are handled correctly`() =
        runTest {
            val pairId = insertPair()

            val unicodeNames = listOf("日本語.txt", "émoji 🎉.png", "Ñoño résumé.docx", "中文文件.pdf")
            val fakeTree =
                mapOf(
                    "root" to unicodeNames.map { file(it, size = 100) },
                )
            val result = enumeratorWith(fakeTree).enumerate(pairId, treeUri)

            assertEquals(unicodeNames.size, result.snapshot.size)
            val snapshotPaths = result.snapshot.map { it.relativePath }.toSet()
            assertTrue(unicodeNames.all { it in snapshotPaths })
        }

    // -------------------------------------------------------------------------
    // Remote columns preservation
    // -------------------------------------------------------------------------

    @Test
    fun `remoteId is preserved for unchanged files after re-enumeration`() =
        runTest {
            val pairId = insertPair()

            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pairId,
                    relativePath = "synced.txt",
                    sizeBytes = 50L,
                    mtimeMs = 999L,
                    contentHash = "knownhash",
                    remoteId = "remote-abc",
                ),
            )

            val fs = FakeFsAccess(mapOf("synced.txt" to "data".toByteArray()))
            enumeratorWith(
                mapOf("root" to listOf(file("synced.txt", size = 50, lastModifiedMs = 999))),
                fs,
            ).enumerate(pairId, treeUri)

            val stored = localIndexDao.getForPair(pairId).single()
            assertEquals("remote-abc", stored.remoteId)
            assertEquals("knownhash", stored.contentHash)
            assertEquals("file not re-read when unchanged", 0, fs.openCount)
        }

    // -------------------------------------------------------------------------
    // Performance: 5 000-file hash reuse
    // -------------------------------------------------------------------------

    @Test
    fun `re-enumerating 5,000 unchanged files reuses all cached hashes`() =
        runTest {
            val pairId = insertPair()

            // Build 5,000 files across a directory tree.
            val files =
                (1..5_000).map { i ->
                    Triple("dir${i % 50}/file_$i.dat", i.toLong(), i.toLong() * 1_000)
                }
            val (fakeTree, bytesMap) = buildFakeTree(files)
            val fs1 = FakeFsAccess(bytesMap)

            // First enumeration: compute hashes for all 5,000 files.
            enumeratorWith(fakeTree, fs1).enumerate(pairId, treeUri)
            assertEquals("all 5,000 files hashed on first pass", 5_000, fs1.openCount)

            // Second enumeration with the same tree: no file changed → zero hash recomputes.
            val fs2 = FakeFsAccess(bytesMap)
            val result2 = enumeratorWith(fakeTree, fs2).enumerate(pairId, treeUri)

            assertEquals(
                "no hash re-computations expected when size+mtime unchanged",
                0,
                fs2.openCount,
            )
            assertTrue("no additions on unchanged re-scan", result2.added.isEmpty())
            assertTrue("no modifications on unchanged re-scan", result2.modified.isEmpty())
            assertTrue("no deletions on unchanged re-scan", result2.deleted.isEmpty())
            assertEquals("snapshot still contains all 5,000 files", 5_000, result2.snapshot.size)
        }

    // -------------------------------------------------------------------------
    // Remote-metadata preservation on modified files
    // (regression test for the repeated-upload bug)
    // -------------------------------------------------------------------------

    /**
     * When a file's mtime changes but its content stays the same, [LocalFsEnumerator]
     * marks it as 'modified' and creates a new [LocalIndexEntity]. Before the fix, the
     * new entity omitted remoteSizeBytes/remoteMtimeMs/remoteEtag (they defaulted to
     * null). On the next sync run those null values prevented the file from appearing
     * in syntheticRemote, making the file look like it had been deleted remotely and
     * triggering a spurious re-upload.
     *
     * After the fix, remote metadata must survive a local mtime-only change.
     */
    @Test
    fun `remote metadata preserved when file mtime changes but content stays same`() =
        runTest {
            val pairId = insertPair()
            val content = "unchanged content".toByteArray()
            val hash = LocalFsEnumerator.sha256Hex(content.inputStream())

            // Seed the index as if the file was previously uploaded (has remote metadata).
            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pairId,
                    relativePath = "notes.txt",
                    sizeBytes = content.size.toLong(),
                    mtimeMs = 1_000L,
                    contentHash = hash,
                    remoteId = "remote-abc",
                    remoteSizeBytes = content.size.toLong(),
                    remoteMtimeMs = 9_000L,
                    remoteEtag = "etag-abc",
                ),
            )

            // File's mtime has changed on disk (same size, same content, different mtime).
            val fs = FakeFsAccess(mapOf("notes.txt" to content))
            enumeratorWith(
                mapOf("root" to listOf(file("notes.txt", size = content.size.toLong(), lastModifiedMs = 2_000L))),
                fs,
            ).enumerate(pairId, treeUri)

            val stored = localIndexDao.getForPair(pairId).single()
            assertEquals("mtime should be updated to the new filesystem value", 2_000L, stored.mtimeMs)
            assertEquals("remoteId must be preserved", "remote-abc", stored.remoteId)
            assertEquals("remoteSizeBytes must be preserved (not wiped)", content.size.toLong(), stored.remoteSizeBytes)
            assertEquals("remoteMtimeMs must be preserved (not wiped)", 9_000L, stored.remoteMtimeMs)
            assertEquals("remoteEtag must be preserved (not wiped)", "etag-abc", stored.remoteEtag)
        }

    @Test
    fun `remote metadata preserved when file size changes`() =
        runTest {
            val pairId = insertPair()

            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pairId,
                    relativePath = "doc.txt",
                    sizeBytes = 10L,
                    mtimeMs = 1_000L,
                    contentHash = null,
                    remoteId = "remote-xyz",
                    remoteSizeBytes = 10L,
                    remoteMtimeMs = 8_000L,
                    remoteEtag = "etag-xyz",
                ),
            )

            // File size changed on disk.
            enumeratorWith(
                mapOf("root" to listOf(file("doc.txt", size = 20L, lastModifiedMs = 2_000L))),
            ).enumerate(pairId, treeUri)

            val stored = localIndexDao.getForPair(pairId).single()
            assertEquals("size must be updated", 20L, stored.sizeBytes)
            assertEquals("remoteId must be preserved", "remote-xyz", stored.remoteId)
            assertEquals("remoteSizeBytes must be preserved", 10L, stored.remoteSizeBytes)
            assertEquals("remoteMtimeMs must be preserved", 8_000L, stored.remoteMtimeMs)
            assertEquals("remoteEtag must be preserved", "etag-xyz", stored.remoteEtag)
        }

    @Test
    fun `new file has null remote metadata`() =
        runTest {
            val pairId = insertPair()

            // No pre-existing index entry — file is brand new.
            enumeratorWith(
                mapOf("root" to listOf(file("new.txt", size = 5L, lastModifiedMs = 1_000L))),
            ).enumerate(pairId, treeUri)

            val stored = localIndexDao.getForPair(pairId).single()
            assertEquals("new.txt", stored.relativePath)
            assertNull("new file has no remoteId", stored.remoteId)
            assertNull("new file has no remoteSizeBytes", stored.remoteSizeBytes)
            assertNull("new file has no remoteMtimeMs", stored.remoteMtimeMs)
            assertNull("new file has no remoteEtag", stored.remoteEtag)
        }

    // -------------------------------------------------------------------------
    // excludeSubfolders
    // -------------------------------------------------------------------------

    @Test
    fun `excludeSubfolders=true returns only root-level files`() =
        runTest {
            val pairId = insertPair()

            val (fakeTree, bytesMap) =
                buildFakeTree(
                    listOf(
                        Triple("root.txt", 10L, 100L),
                        Triple("sub/child.txt", 20L, 200L),
                        Triple("sub/nested/deep.txt", 30L, 300L),
                    ),
                )
            val result =
                enumeratorWith(fakeTree, FakeFsAccess(bytesMap))
                    .enumerate(pairId, treeUri, excludeSubfolders = true)

            assertEquals(1, result.snapshot.size)
            assertEquals("root.txt", result.snapshot.single().relativePath)
        }

    @Test
    fun `excludeSubfolders=false still traverses nested dirs (default behaviour)`() =
        runTest {
            val pairId = insertPair()

            val (fakeTree, bytesMap) =
                buildFakeTree(
                    listOf(
                        Triple("root.txt", 10L, 100L),
                        Triple("sub/child.txt", 20L, 200L),
                    ),
                )
            val result =
                enumeratorWith(fakeTree, FakeFsAccess(bytesMap))
                    .enumerate(pairId, treeUri, excludeSubfolders = false)

            assertEquals(2, result.snapshot.size)
        }

    @Test
    fun `excludeSubfolders=true marks previously-indexed subdirectory files as deleted`() =
        runTest {
            val pairId = insertPair()

            // Seed an entry from a subdirectory that was previously indexed.
            localIndexDao.upsert(
                LocalIndexEntity(pairId, "sub/old.txt", sizeBytes = 10L, mtimeMs = 100L),
            )

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("root.txt", size = 5),
                            dir("sub"),
                        ),
                    "sub" to listOf(file("old.txt", size = 10, lastModifiedMs = 100)),
                )
            val result =
                enumeratorWith(fakeTree).enumerate(pairId, treeUri, excludeSubfolders = true)

            assertTrue(
                "sub/old.txt must appear in deleted set when subfolders are excluded",
                "sub/old.txt" in result.deleted,
            )
            assertEquals(1, result.snapshot.size)
            assertEquals("root.txt", result.snapshot.single().relativePath)
        }

    @Test
    fun `excludeSubfolders=true with empty root returns empty result`() =
        runTest {
            val pairId = insertPair()
            val result =
                enumeratorWith(mapOf("root" to emptyList()))
                    .enumerate(pairId, treeUri, excludeSubfolders = true)

            assertTrue(result.snapshot.isEmpty())
            assertTrue(result.added.isEmpty())
            assertTrue(result.deleted.isEmpty())
        }

    @Test
    fun `excludeSubfolders=true is compatible with ignoreGlobs`() =
        runTest {
            val pairId = insertPair()

            val fakeTree =
                mapOf(
                    "root" to
                        listOf(
                            file("keep.txt", size = 10),
                            file("ignore.tmp", size = 5),
                            dir("sub"),
                        ),
                    "sub" to listOf(file("nested.txt", size = 20)),
                )
            val result =
                enumeratorWith(fakeTree)
                    .enumerate(
                        pairId,
                        treeUri,
                        ignoreGlobs = listOf("*.tmp"),
                        excludeSubfolders = true,
                    )

            assertEquals(1, result.snapshot.size)
            assertEquals("keep.txt", result.snapshot.single().relativePath)
        }

    // -------------------------------------------------------------------------
    // glob helpers
    // -------------------------------------------------------------------------

    @Test
    fun `globToRegex star matches within one component`() {
        val regex = LocalFsEnumerator.globToRegex("*.txt")
        assertTrue(regex.matches("file.txt"))
        assertTrue(!regex.matches("sub/file.txt"))
    }

    @Test
    fun `globToRegex double-star matches across path separators`() {
        val regex = LocalFsEnumerator.globToRegex("**.txt")
        assertTrue(regex.matches("file.txt"))
        assertTrue(regex.matches("sub/file.txt"))
        assertTrue(regex.matches("a/b/c/file.txt"))
    }

    @Test
    fun `globToRegex question mark matches single non-separator char`() {
        val regex = LocalFsEnumerator.globToRegex("file?.txt")
        assertTrue(regex.matches("fileA.txt"))
        assertTrue(!regex.matches("file/txt"))
    }

    @Test
    fun `sha256Hex returns correct digest`() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val hash = LocalFsEnumerator.sha256Hex(ByteArray(0).inputStream())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256Hex of known string matches expected digest`() {
        val content = "hello world".toByteArray()
        val hash1 = LocalFsEnumerator.sha256Hex(content.inputStream())
        val hash2 = LocalFsEnumerator.sha256Hex(content.inputStream())
        assertEquals("same content must produce same hash", hash1, hash2)
        assertEquals("hash must be lowercase hex of 64 chars", 64, hash1.length)
        assertTrue("hash must be lowercase hex", hash1.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `different content produces different hash`() {
        val hash1 = LocalFsEnumerator.sha256Hex("content A".toByteArray().inputStream())
        val hash2 = LocalFsEnumerator.sha256Hex("content B".toByteArray().inputStream())
        assertTrue("different content must yield different hashes", hash1 != hash2)
    }
}
