package com.synckro.util.notification

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerCompletionAggregatorTest {
    private val dispatcher = StandardTestDispatcher()

    private fun pair(
        id: Long,
        name: String,
    ) = SyncPair(
        id = id,
        displayName = name,
        localTreeUri = "content://local/$id",
        provider = CloudProviderType.GOOGLE_DRIVE,
        accountId = "account-$id",
        remoteFolderId = "remote-$id",
        direction = SyncDirection.BIDIRECTIONAL,
        conflictPolicy = ConflictPolicy.NEWEST_WINS,
        wifiOnly = false,
        autoSyncEnabled = true,
        scheduleIntervalMinutes = 60,
    )

    @Test
    fun `record coalesces completions that arrive within the aggregation window`() =
        runTest(dispatcher) {
            val aggregator = WorkerCompletionAggregator(backgroundScope)
            val flushed = mutableListOf<List<WorkerCompletionAggregator.Completion>>()

            aggregator.record(pair(1L, "Docs"), 3) { flushed += it }
            aggregator.record(pair(2L, "Photos"), 2) { flushed += it }

            advanceTimeBy(WorkerCompletionAggregator.DEFAULT_WINDOW_MS - 1)
            advanceUntilIdle()
            assertTrue(flushed.isEmpty())

            advanceTimeBy(1)
            advanceUntilIdle()

            assertEquals(1, flushed.size)
            assertEquals(listOf(1L, 2L), flushed.single().map { it.pair.id })
            assertEquals(listOf(3, 2), flushed.single().map { it.transferredFiles })
        }

    @Test
    fun `record merges repeated completions for the same pair before flush`() =
        runTest(dispatcher) {
            val aggregator = WorkerCompletionAggregator(backgroundScope)
            val flushed = mutableListOf<List<WorkerCompletionAggregator.Completion>>()

            aggregator.record(pair(1L, "Docs"), 1) { flushed += it }
            aggregator.record(pair(1L, "Docs"), 4) { flushed += it }
            advanceTimeBy(WorkerCompletionAggregator.DEFAULT_WINDOW_MS)
            advanceUntilIdle()

            assertEquals(1, flushed.size)
            assertEquals(1, flushed.single().size)
            assertEquals(5, flushed.single().single().transferredFiles)
        }
}
