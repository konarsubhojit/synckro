package com.synckro.providers.fake

import com.synckro.domain.sync.RemoteChangeType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for [FakeRemoteEnumerator] driving [FakeCloudProvider]'s
 * in-memory change log. Verifies the same baseline + incremental + multi-event
 * contract used by the OneDrive and Google Drive enumerators.
 */
class FakeRemoteEnumeratorTest {
    private fun bytes(s: String) = ByteArrayInputStream(s.toByteArray())

    @Test
    fun `enumerate null returns empty baseline with current log size as token`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            // Pre-populate so the baseline token is non-zero and we can verify it.
            provider.uploadNew("root", "a.txt", bytes("hello"), 5, "text/plain")

            val snapshot = enumerator.enumerate(deltaToken = null)

            assertTrue(snapshot.changes.isEmpty())
            // Baseline token equals the change log index after the upload.
            assertEquals("1", snapshot.newDeltaToken)
        }

    @Test
    fun `enumerate after baseline returns subsequent upserts as MODIFY`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            val baseline = enumerator.enumerate(deltaToken = null)

            val file = provider.uploadNew("root", "notes.txt", bytes("hi"), 2, "text/plain")
            val folder = provider.createFolder("root", "photos")

            val snapshot = enumerator.enumerate(deltaToken = baseline.newDeltaToken)

            assertEquals(2, snapshot.changes.size)
            val first = snapshot.changes[0]
            assertEquals(RemoteChangeType.MODIFY, first.type)
            assertEquals(file.id, first.remoteId)
            assertEquals("notes.txt", first.relativePath)
            assertEquals(2L, first.sizeBytes)

            val second = snapshot.changes[1]
            assertEquals(RemoteChangeType.MODIFY, second.type)
            assertEquals(folder.id, second.remoteId)
            assertEquals("photos", second.relativePath)
        }

    @Test
    fun `enumerate emits DELETE when a file is removed`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            val baseline = enumerator.enumerate(deltaToken = null)
            val file = provider.uploadNew("root", "tmp.txt", bytes("x"), 1, "text/plain")
            provider.delete(file.id)

            val snapshot = enumerator.enumerate(deltaToken = baseline.newDeltaToken)

            assertEquals(2, snapshot.changes.size)
            assertEquals(RemoteChangeType.MODIFY, snapshot.changes[0].type)
            assertEquals(RemoteChangeType.DELETE, snapshot.changes[1].type)
            assertEquals(file.id, snapshot.changes[1].remoteId)
        }

    @Test
    fun `enumerate is incremental — successive calls only return new entries`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            val token0 = enumerator.enumerate(deltaToken = null).newDeltaToken
            provider.uploadNew("root", "a.txt", bytes("a"), 1, "text/plain")

            val first = enumerator.enumerate(deltaToken = token0)
            assertEquals(1, first.changes.size)

            // Without any further changes the next enumerate must yield nothing.
            val empty = enumerator.enumerate(deltaToken = first.newDeltaToken)
            assertTrue(empty.changes.isEmpty())
            assertEquals(first.newDeltaToken, empty.newDeltaToken)
        }

    // -------------------------------------------------------------------------
    // enumerateFull — full initial listing for brand-new pairs
    // -------------------------------------------------------------------------

    @Test
    fun `enumerateFull returns all existing files as MODIFY changes`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            provider.uploadNew("root", "a.txt", bytes("aaa"), 3, "text/plain")
            provider.uploadNew("root", "b.txt", bytes("bb"), 2, "text/plain")

            val snapshot = enumerator.enumerateFull(rootFolderId = "root")

            assertEquals(2, snapshot.changes.size)
            snapshot.changes.forEach { assertEquals(RemoteChangeType.MODIFY, it.type) }
            val paths = snapshot.changes.map { it.relativePath }.toSet()
            assertTrue("a.txt should be in enumerateFull results", "a.txt" in paths)
            assertTrue("b.txt should be in enumerateFull results", "b.txt" in paths)
        }

    @Test
    fun `enumerateFull token equals current change log size`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            provider.uploadNew("root", "x.txt", bytes("x"), 1, "text/plain")
            provider.uploadNew("root", "y.txt", bytes("y"), 1, "text/plain")

            val snapshot = enumerator.enumerateFull(rootFolderId = "root")

            // changeLog.size = 2 at the time of the call
            assertEquals("2", snapshot.newDeltaToken)
        }

    @Test
    fun `enumerateFull on empty folder returns empty changes with baseline token`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            val snapshot = enumerator.enumerateFull(rootFolderId = "root")

            assertTrue("Empty folder should produce no changes", snapshot.changes.isEmpty())
            assertEquals("0", snapshot.newDeltaToken)
        }

    @Test
    fun `enumerateFull with empty rootFolderId falls back to baseline enumerate`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            provider.uploadNew("root", "file.txt", bytes("content"), 7, "text/plain")

            // With empty rootFolderId the impl falls back to enumerate(null) → empty changes.
            val snapshot = enumerator.enumerateFull(rootFolderId = "")

            assertTrue("Fallback to enumerate(null) should return empty changes", snapshot.changes.isEmpty())
        }

    @Test
    fun `enumerateFull recurses into sub-folders`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            val subFolder = provider.createFolder("root", "docs")
            provider.uploadNew("root", "root-file.txt", bytes("r"), 1, "text/plain")
            provider.uploadNew(subFolder.id, "nested.txt", bytes("n"), 1, "text/plain")

            val snapshot = enumerator.enumerateFull(rootFolderId = "root")

            // Should find root-file.txt and docs/nested.txt but NOT the folder itself.
            val filePaths = snapshot.changes.map { it.relativePath }.toSet()
            assertEquals(2, snapshot.changes.size)
            assertTrue("root-level file should be included", "root-file.txt" in filePaths)
            assertTrue("nested file should be included with full path", "docs/nested.txt" in filePaths)
            snapshot.changes.forEach { assertEquals(RemoteChangeType.MODIFY, it.type) }
        }

    @Test
    fun `enumerateFull token is compatible with subsequent enumerate for incremental sync`() =
        runTest {
            val provider = FakeCloudProvider()
            val enumerator = FakeRemoteEnumerator(provider)

            provider.uploadNew("root", "existing.txt", bytes("ex"), 2, "text/plain")

            val fullSnapshot = enumerator.enumerateFull(rootFolderId = "root")
            assertEquals("1", fullSnapshot.newDeltaToken)

            // Add a new file after the full scan.
            provider.uploadNew("root", "new.txt", bytes("new"), 3, "text/plain")

            val deltaSnapshot = enumerator.enumerate(deltaToken = fullSnapshot.newDeltaToken)
            assertEquals(1, deltaSnapshot.changes.size)
            assertEquals("new.txt", deltaSnapshot.changes[0].relativePath)
            assertEquals(RemoteChangeType.MODIFY, deltaSnapshot.changes[0].type)
        }
}
