package com.synckro.data.watcher

import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultLocalTreeWatchSourceProviderTest {
    private val dao = mockk<SyncPairDao>()
    private val resolver = mockk<LocalTreeWatchSourceResolver>()
    private val provider = DefaultLocalTreeWatchSourceProvider(dao, resolver)

    @Test
    fun `delegates persisted tree URI outcomes to resolver`() {
        val pair = mockk<SyncPairEntity>()
        coEvery { dao.getById(7) } returns pair
        every { pair.localTreeUri } returns "content://tree"

        val outcomes =
            listOf(
                LocalTreeWatchSource.DirectPath("/root"),
                LocalTreeWatchSource.MediaStoreTree("DCIM/", setOf(MediaCollection.IMAGES)),
                LocalTreeWatchSource.Unsupported,
            )
        outcomes.forEach { outcome ->
            every { resolver.resolve("content://tree") } returns outcome
            assertEquals(outcome, provider.sourceFor(7))
        }
    }
}
