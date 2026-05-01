package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection
import com.konarsubhojit.synckro.domain.model.SyncPair
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val engine = SyncEngine()

    private fun pair(provider: CloudProviderType) = SyncPair(
        id = 1L,
        displayName = "Test",
        localTreeUri = "content://test",
        provider = provider,
        remoteFolderId = "root",
        direction = SyncDirection.BIDIRECTIONAL,
        conflictPolicy = ConflictPolicy.NEWEST_WINS,
    )

    @Test
    fun `FAKE provider returns Success`() = runTest {
        val result = engine.runOnce(pair(CloudProviderType.FAKE))
        assertTrue("Expected Success for FAKE provider", result is SyncEngine.Result.Success)
    }

    @Test
    fun `FAKE provider success has zero applied and conflicts`() = runTest {
        val result = engine.runOnce(pair(CloudProviderType.FAKE)) as SyncEngine.Result.Success
        assertEquals(0, result.applied)
        assertEquals(0, result.conflicts)
    }

    @Test
    fun `ONEDRIVE provider returns Terminal (not yet implemented)`() = runTest {
        val result = engine.runOnce(pair(CloudProviderType.ONEDRIVE))
        assertTrue("Expected Terminal for ONEDRIVE provider", result is SyncEngine.Result.Terminal)
    }

    @Test
    fun `GOOGLE_DRIVE provider returns Terminal (not yet implemented)`() = runTest {
        val result = engine.runOnce(pair(CloudProviderType.GOOGLE_DRIVE))
        assertTrue(
            "Expected Terminal for GOOGLE_DRIVE provider",
            result is SyncEngine.Result.Terminal,
        )
    }
}
