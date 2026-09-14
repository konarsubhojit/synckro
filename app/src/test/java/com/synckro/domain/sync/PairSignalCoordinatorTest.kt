package com.synckro.domain.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairSignalCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `burst emits once after the trailing quiet period`() =
        runTest(dispatcher) {
            val coordinator = PairSignalCoordinator(this)
            val callbacks = mutableListOf<String>()

            coordinator.signal(1L) { callbacks += "first" }
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS - 1_000L)
            coordinator.signal(1L) { callbacks += "latest" }
            runCurrent()

            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS - 1L)
            runCurrent()
            assertTrue(callbacks.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("latest"), callbacks)
        }

    @Test
    fun `pair timers are independent`() =
        runTest(dispatcher) {
            val coordinator = PairSignalCoordinator(this)
            val dispatched = mutableListOf<Long>()

            coordinator.signal(1L, dispatched::add)
            advanceTimeBy(2_000L)
            coordinator.signal(2L, dispatched::add)

            advanceTimeBy(3_000L)
            runCurrent()
            assertEquals(listOf(1L), dispatched)

            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(listOf(1L, 2L), dispatched)
        }

    @Test
    fun `cancellation and restart do not consume durable rows`() =
        runTest(dispatcher) {
            val durableRows = mutableSetOf(1L, 2L)
            val firstCoordinator = PairSignalCoordinator(this)
            val dispatchedPairs = mutableListOf<Long>()
            val dispatch: suspend (Long) -> Unit = {
                durableRows.remove(it)
                dispatchedPairs += it
            }

            firstCoordinator.signal(1L, dispatch)
            firstCoordinator.signal(2L, dispatch)
            advanceTimeBy(1_000L)
            firstCoordinator.cancelPendingSignals()
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            assertTrue(dispatchedPairs.isEmpty())
            assertEquals(setOf(1L, 2L), durableRows)

            val restartedCoordinator = PairSignalCoordinator(this)
            restartedCoordinator.signal(1L, dispatch)
            restartedCoordinator.signal(2L, dispatch)
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            assertEquals(listOf(1L, 2L), dispatchedPairs)
            assertTrue(durableRows.isEmpty())
        }
}
