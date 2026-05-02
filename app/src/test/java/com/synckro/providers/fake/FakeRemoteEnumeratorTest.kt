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
}
