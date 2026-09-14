package com.synckro.data.watcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTreeWatchSourceResolverTest {
    @Test
    fun `validated primary volume tree resolves to a direct path`() {
        val resolver = resolver(observable = setOf("/storage/emulated/0/DCIM/Camera"))

        assertEquals(
            LocalTreeWatchSource.DirectPath("/storage/emulated/0/DCIM/Camera"),
            resolver.resolve("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"),
        )
    }

    @Test
    fun `percent encoded names are decoded without form encoding rules`() {
        val resolver = resolver(observable = setOf("/storage/emulated/0/My Docs/a+b"))

        assertEquals(
            LocalTreeWatchSource.DirectPath("/storage/emulated/0/My Docs/a+b"),
            resolver.resolve("content://com.android.externalstorage.documents/tree/primary%3AMy%20Docs%2Fa+b"),
        )
    }

    @Test
    fun `unreadable media tree falls back to a pair scoped MediaStore mapping`() {
        val resolver = resolver(observable = emptySet())

        assertEquals(
            LocalTreeWatchSource.MediaStoreTree(
                relativePathPrefix = "DCIM/Camera/",
                collections = setOf(MediaCollection.IMAGES, MediaCollection.VIDEO),
            ),
            resolver.resolve("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"),
        )
    }

    @Test
    fun `audio trees resolve to the audio collection`() {
        val resolver = resolver(observable = emptySet())

        assertEquals(
            LocalTreeWatchSource.MediaStoreTree("Music/", setOf(MediaCollection.AUDIO)),
            resolver.resolve("content://com.android.externalstorage.documents/tree/primary%3AMusic"),
        )
    }

    @Test
    fun `non media trees without a readable path are unsupported`() {
        val resolver = resolver(observable = emptySet())

        assertEquals(
            LocalTreeWatchSource.Unsupported,
            resolver.resolve("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FSynckro"),
        )
    }

    @Test
    fun `paths are never guessed from arbitrary providers or volumes`() {
        val resolver = resolver(observable = setOf("/storage/emulated/0/DCIM", "/storage/emulated/0"))

        val unsupportedTrees =
            listOf(
                "content://com.google.android.apps.docs.storage/tree/encoded%3Aabc",
                "content://com.android.providers.downloads.documents/tree/downloads",
                "content://com.android.externalstorage.documents/tree/1234-5678%3ADCIM",
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2F..%2F..%2Fdata",
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2",
                "file:///storage/emulated/0/DCIM",
                "",
            )

        unsupportedTrees.forEach { treeUri ->
            assertEquals(treeUri, LocalTreeWatchSource.Unsupported, resolver.resolve(treeUri))
        }
    }

    @Test
    fun `volume root resolves to the storage root without a media mapping`() {
        val rootTree = "content://com.android.externalstorage.documents/tree/primary%3A"

        assertEquals(
            LocalTreeWatchSource.DirectPath("/storage/emulated/0"),
            resolver(observable = setOf("/storage/emulated/0")).resolve(rootTree),
        )
        assertEquals(
            LocalTreeWatchSource.Unsupported,
            resolver(observable = emptySet()).resolve(rootTree),
        )
    }

    private fun resolver(observable: Set<String>) =
        LocalTreeWatchSourceResolver(
            primaryStorageRoot = "/storage/emulated/0",
            directPathValidator = { path -> path in observable },
        )
}
