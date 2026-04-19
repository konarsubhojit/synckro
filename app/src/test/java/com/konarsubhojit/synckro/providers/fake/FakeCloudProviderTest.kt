package com.konarsubhojit.synckro.providers.fake

import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeCloudProviderTest {

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

    @Test
    fun `uploadNew then download round-trips bytes`() = runTest {
        val provider = FakeCloudProvider()
        val payload = bytes("hello world")

        val uploaded = provider.uploadNew(
            parentId = "root",
            name = "hello.txt",
            content = ByteArrayInputStream(payload),
            size = payload.size.toLong(),
            mimeType = "text/plain",
        )

        val downloaded = provider.download(uploaded.id).use { it.readBytes() }

        assertTrue(provider.ensureAuthenticated())
        assertEquals(payload.toList(), downloaded.toList())
    }

    @Test
    fun `uploadNew then list returns file in parent`() = runTest {
        val provider = FakeCloudProvider()
        provider.uploadNew("parent", "child.txt", ByteArrayInputStream(bytes("data")), 4, "text/plain")

        val listed = provider.list("parent")

        assertEquals(1, listed.size)
        assertEquals("child.txt", listed.single().name)
        assertEquals("parent", listed.single().parentId)
        assertFalse(listed.single().isFolder)
    }

    @Test
    fun `uploadNew then getMetadata returns stored metadata`() = runTest {
        val provider = FakeCloudProvider()
        val uploaded = provider.uploadNew("root", "meta.txt", ByteArrayInputStream(bytes("meta")), 4, "text/plain")

        val metadata = provider.getMetadata(uploaded.id)

        assertEquals(uploaded, metadata)
        assertEquals(4L, metadata.size)
        assertEquals("text/plain", metadata.mimeType)
    }

    @Test
    fun `updateContent replaces bytes and changes etag`() = runTest {
        val provider = FakeCloudProvider()
        val original = provider.uploadNew("root", "file.txt", ByteArrayInputStream(bytes("old")), 3, "text/plain")

        val updated = provider.updateContent(
            id = original.id,
            content = ByteArrayInputStream(bytes("new content")),
            size = 11,
            mimeType = "text/markdown",
        )
        val downloaded = provider.download(original.id).use { it.readBytes().toString(Charsets.UTF_8) }

        assertEquals("new content", downloaded)
        assertEquals(11L, updated.size)
        assertEquals("text/markdown", updated.mimeType)
        assertNotEquals(original.eTag, updated.eTag)
    }

    @Test
    fun `createFolder then list returns folder metadata`() = runTest {
        val provider = FakeCloudProvider()
        val folder = provider.createFolder("root", "photos")

        val listed = provider.list("root")

        assertEquals(1, listed.size)
        assertEquals(folder, listed.single())
        assertTrue(listed.single().isFolder)
        assertEquals(null, listed.single().size)
    }

    @Test
    fun `delete removes file from list`() = runTest {
        val provider = FakeCloudProvider()
        val uploaded = provider.uploadNew("root", "gone.txt", ByteArrayInputStream(bytes("gone")), 4, "text/plain")

        provider.delete(uploaded.id)

        assertTrue(provider.list("root").isEmpty())
    }

    @Test
    fun `changesSince null returns empty page with initial token`() = runTest {
        val provider = FakeCloudProvider()
        provider.uploadNew("root", "seed.txt", ByteArrayInputStream(bytes("seed")), 4, "text/plain")

        val page = provider.changesSince(null)

        assertTrue(page.changes.isEmpty())
        assertEquals("1", page.nextToken)
        assertFalse(page.hasMore)
    }

    @Test
    fun `changesSince token returns only changes after token`() = runTest {
        val provider = FakeCloudProvider()
        val initial = provider.changesSince(null)
        val first = provider.uploadNew("root", "first.txt", ByteArrayInputStream(bytes("1")), 1, "text/plain")
        val second = provider.uploadNew("root", "second.txt", ByteArrayInputStream(bytes("2")), 1, "text/plain")
        provider.delete(first.id)

        val page = provider.changesSince(initial.nextToken)

        assertEquals(3, page.changes.size)
        assertEquals("first.txt", page.changes[0].file?.name)
        assertEquals("second.txt", page.changes[1].file?.name)
        assertEquals(first.id, page.changes[2].removedId)
        assertEquals("3", page.nextToken)
        assertFalse(page.hasMore)
        assertEquals(second.id, page.changes[1].file?.id)
    }

    @Test
    fun `download on folder throws IllegalArgumentException`() = runTest {
        val provider = FakeCloudProvider()
        val folderId = provider.createFolder("root", "docs").id

        try {
            provider.download(folderId)
            fail("Expected download(folderId) to throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains(folderId) == true)
        }
    }

    @Test
    fun `getMetadata on missing id throws IllegalStateException`() = runTest {
        val provider = FakeCloudProvider()

        try {
            provider.getMetadata("missing")
            fail("Expected getMetadata(missing) to throw IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("missing") == true)
        }
    }
}
