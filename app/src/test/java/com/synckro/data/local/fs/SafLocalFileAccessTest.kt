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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

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

    // -------------------------------------------------------------------------
    // SAF permission / query failure → LocalStorageException
    // -------------------------------------------------------------------------

    /**
     * Builds a [SafLocalFileAccess] whose [DocumentChildrenQuery] throws the
     * provided [cause] for every call, simulating a revoked SAF permission.
     */
    private fun accessWithThrowingQuery(cause: Exception): SafLocalFileAccess {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val throwingQuery = DocumentChildrenQuery { _, _, _ -> throw cause }
        return SafLocalFileAccess(context.contentResolver, treeUri, throwingQuery)
    }

    @Test
    fun `openRead throws LocalStorageException when query fails`() {
        val access = accessWithThrowingQuery(SecurityException("Permission denied"))

        try {
            access.openRead("any.txt")
            fail("Expected LocalStorageException")
        } catch (e: LocalStorageException) {
            assertTrue("cause must be the original SecurityException", e.cause is SecurityException)
        }
    }

    @Test
    fun `stat throws LocalStorageException when query fails`() {
        val access = accessWithThrowingQuery(SecurityException("Permission denied"))

        try {
            access.stat("any.txt")
            fail("Expected LocalStorageException")
        } catch (e: LocalStorageException) {
            assertTrue("cause must be the original SecurityException", e.cause is SecurityException)
        }
    }

    @Test
    fun `delete throws LocalStorageException when query fails`() {
        val access = accessWithThrowingQuery(SecurityException("Permission denied"))

        try {
            access.delete("any.txt")
            fail("Expected LocalStorageException")
        } catch (e: LocalStorageException) {
            assertTrue("cause must be the original SecurityException", e.cause is SecurityException)
        }
    }

    // -------------------------------------------------------------------------
    // TargetedSafMetadataSampler
    // -------------------------------------------------------------------------

    private fun sampler(
        tree: Map<String, List<RawDocChild>> = emptyMap(),
        metadata: Map<String, SafDocumentMetadata> = emptyMap(),
        readProbe: SafReadProbe = SafReadProbe { _, _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
        pendingQuery: MediaStorePendingStateQuery? = null,
    ): TargetedSafMetadataSampler {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TargetedSafMetadataSampler(
            resolver = context.contentResolver,
            treeUri = treeUri,
            childrenQuery = DocumentChildrenQuery { _, _, parentDocId -> tree[parentDocId] ?: emptyList() },
            metadataQuery = SafDocumentMetadataQuery { _, _, documentId -> metadata[documentId] },
            readProbe = readProbe,
            mediaStorePendingStateQuery = pendingQuery,
        )
    }

    @Test
    fun `sampler resolves nested relative path without a tree walk`() {
        val sample =
            sampler(
                tree =
                    mapOf(
                        "root" to listOf(dir("subdir")),
                        "subdir" to listOf(file("nested.txt", docId = "nested-id")),
                    ),
                metadata = mapOf("nested-id" to SafDocumentMetadata(77L, 2_000L, "text/plain")),
            ).sample(relativePath = "subdir/nested.txt")

        assertEquals(
            TargetedSafMetadataSample.Available("nested-id", 77L, 2_000L, "text/plain", true, null),
            sample,
        )
    }

    @Test
    fun `sampler reports a deleted document ID as missing`() {
        assertEquals(TargetedSafMetadataSample.Missing, sampler().sample(documentId = "deleted-id"))
    }

    @Test
    fun `sampler reports a missing relative path as missing`() {
        assertEquals(TargetedSafMetadataSample.Missing, sampler().sample(relativePath = "missing/file.txt"))
    }

    @Test
    fun `sampler reports a blank relative path as missing`() {
        assertEquals(TargetedSafMetadataSample.Missing, sampler().sample(relativePath = ""))
    }

    @Test
    fun `sampler requires a path or document ID`() {
        try {
            sampler().sample()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `sampler requires exactly one identifier`() {
        try {
            sampler().sample(relativePath = "file.txt", documentId = "id")
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `sampler returns inconclusive when path resolution loses permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampler =
            TargetedSafMetadataSampler(
                context.contentResolver,
                treeUri,
                childrenQuery = DocumentChildrenQuery { _, _, _ -> throw SecurityException("Permission denied") },
            )

        assertEquals(
            TargetedSafMetadataSample.Inconclusive(TargetedSafMetadataSample.Inconclusive.Reason.PROVIDER_FAILURE),
            sampler.sample(relativePath = "nested/file.txt"),
        )
    }

    @Test
    fun `sampler returns inconclusive when metadata is unknown`() {
        val sample =
            sampler(metadata = mapOf("id" to SafDocumentMetadata(sizeBytes = null, mtimeMs = 2_000L, mimeType = "text/plain")))
                .sample(documentId = "id")

        assertEquals(
            TargetedSafMetadataSample.Inconclusive(TargetedSafMetadataSample.Inconclusive.Reason.METADATA_UNAVAILABLE),
            sample,
        )
    }

    @Test
    fun `sampler returns inconclusive when metadata provider throws`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampler =
            TargetedSafMetadataSampler(
                context.contentResolver,
                treeUri,
                metadataQuery = SafDocumentMetadataQuery { _, _, _ -> throw SecurityException("Permission denied") },
            )

        assertEquals(
            TargetedSafMetadataSample.Inconclusive(TargetedSafMetadataSample.Inconclusive.Reason.PROVIDER_FAILURE),
            sampler.sample(documentId = "id"),
        )
    }

    @Test
    fun `sampler represents an unopenable document safely`() {
        val sample =
            sampler(
                metadata = mapOf("id" to SafDocumentMetadata(77L, 2_000L, "text/plain")),
                readProbe = SafReadProbe { _, _, _ -> null },
            ).sample(documentId = "id")

        assertEquals(
            TargetedSafMetadataSample.Available("id", 77L, 2_000L, "text/plain", false, null),
            sample,
        )
    }

    @Test
    fun `sampler returns optional MediaStore pending state`() {
        val sample =
            sampler(
                metadata = mapOf("id" to SafDocumentMetadata(77L, 2_000L, "text/plain")),
                pendingQuery = MediaStorePendingStateQuery { _, _, _ -> true },
            ).sample(documentId = "id")

        assertEquals(
            TargetedSafMetadataSample.Available("id", 77L, 2_000L, "text/plain", true, true),
            sample,
        )
    }

    @Test
    fun `sampler is inconclusive when required MediaStore pending state is unavailable`() {
        val sample =
            sampler(
                metadata = mapOf("id" to SafDocumentMetadata(77L, 2_000L, "text/plain")),
                pendingQuery = MediaStorePendingStateQuery { _, _, _ -> null },
            ).sample(documentId = "id")

        assertEquals(
            TargetedSafMetadataSample.Inconclusive(
                TargetedSafMetadataSample.Inconclusive.Reason.METADATA_UNAVAILABLE,
            ),
            sample,
        )
    }
}
