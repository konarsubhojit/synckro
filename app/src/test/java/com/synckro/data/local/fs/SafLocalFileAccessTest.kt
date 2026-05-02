package com.synckro.data.local.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.scanner.DocumentChildrenQuery
import com.synckro.data.scanner.RawDocChild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SafLocalFileAccess].
 *
 * [DocumentChildrenQuery] is replaced with a pure in-memory fake to keep tests
 * independent of a live SAF ContentProvider.  Stream I/O (openRead / write) is
 * exercised indirectly via the [SyncEngineRealIntegrationTest] integration suite.
 *
 * Tree URI used throughout: `content://com.example.saftest/tree/root`
 * Root document ID (from [DocumentsContract.getTreeDocumentId]): `"root"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafLocalFileAccessTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val treeUri = Uri.parse("content://com.example.saftest/tree/root")

    /** Builds a [SafLocalFileAccess] wired to an in-memory [DocumentChildrenQuery]. */
    private fun accessWith(tree: Map<String, List<RawDocChild>>): SafLocalFileAccess {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeQuery = DocumentChildrenQuery { _, _, parentDocId -> tree[parentDocId] ?: emptyList() }
        return SafLocalFileAccess(context.contentResolver, treeUri, fakeQuery)
    }

    private fun file(
        name: String,
        docId: String = name,
        size: Long = 100L,
        lastModifiedMs: Long = 1_000L,
        mimeType: String = "application/octet-stream",
    ) = RawDocChild(docId = docId, name = name, size = size, lastModifiedMs = lastModifiedMs, mimeType = mimeType)

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

    // -------------------------------------------------------------------------
    // stat
    // -------------------------------------------------------------------------

    @Test
    fun `stat returns metadata for a flat file`() {
        val access = accessWith(mapOf("root" to listOf(file("hello.txt", size = 42L, lastModifiedMs = 9_000L, mimeType = "text/plain"))))

        val stat = access.stat("hello.txt")

        assertNotNull(stat)
        assertEquals(42L, stat!!.sizeBytes)
        assertEquals(9_000L, stat.mtimeMs)
        assertEquals("text/plain", stat.mimeType)
    }

    @Test
    fun `stat returns null for absent flat file`() {
        val access = accessWith(mapOf("root" to emptyList()))

        assertNull(access.stat("missing.txt"))
    }

    @Test
    fun `stat resolves nested path`() {
        val access =
            accessWith(
                mapOf(
                    "root" to listOf(dir("subdir")),
                    "subdir" to listOf(file("nested.txt", docId = "subdir/nested.txt", size = 77L, lastModifiedMs = 2_000L)),
                ),
            )

        val stat = access.stat("subdir/nested.txt")

        assertNotNull(stat)
        assertEquals(77L, stat!!.sizeBytes)
        assertEquals(2_000L, stat.mtimeMs)
    }

    @Test
    fun `stat returns null when intermediate directory is absent`() {
        val access = accessWith(mapOf("root" to emptyList()))

        assertNull(access.stat("missing/nested.txt"))
    }

    @Test
    fun `stat handles deeply nested path`() {
        val access =
            accessWith(
                mapOf(
                    "root" to listOf(dir("a")),
                    "a" to listOf(dir("b")),
                    "b" to listOf(file("deep.txt", docId = "a/b/deep.txt", size = 55L, lastModifiedMs = 3_000L)),
                ),
            )

        val stat = access.stat("a/b/deep.txt")

        assertNotNull(stat)
        assertEquals(55L, stat!!.sizeBytes)
    }

    // -------------------------------------------------------------------------
    // openRead
    // -------------------------------------------------------------------------

    @Test
    fun `openRead returns null when file is absent`() {
        val access = accessWith(mapOf("root" to emptyList()))

        // findDocId will return null → openRead must return null without throwing
        assertNull(access.openRead("absent.txt"))
    }

    @Test
    fun `openRead returns null when nested path is absent`() {
        val access = accessWith(mapOf("root" to listOf(dir("subdir")), "subdir" to emptyList()))

        assertNull(access.openRead("subdir/missing.txt"))
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    fun `delete returns false when file is absent`() {
        val access = accessWith(mapOf("root" to emptyList()))

        assertFalse(access.delete("absent.txt"))
    }

    @Test
    fun `delete returns false when intermediate directory is absent`() {
        val access = accessWith(mapOf("root" to emptyList()))

        assertFalse(access.delete("no/such/file.txt"))
    }
}
