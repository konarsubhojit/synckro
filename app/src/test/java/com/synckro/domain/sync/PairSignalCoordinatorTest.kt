package com.synckro.domain.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `pair can be signaled again after dispatch`() =
        runTest(dispatcher) {
            val coordinator = PairSignalCoordinator(this, debounceMs = 100L, minDispatchIntervalMs = 0L)
            val dispatched = mutableListOf<Long>()

            coordinator.signal(1L, dispatched::add)
            advanceTimeBy(100L)
            runCurrent()
            coordinator.signal(1L, dispatched::add)
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(listOf(1L, 1L), dispatched)
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

    @Test
    fun `zero debounce dispatches without locking an eager coroutine`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = PairSignalCoordinator(this, debounceMs = 0L)
            val dispatched = mutableListOf<Long>()

            coordinator.signal(1L, dispatched::add)

            assertEquals(listOf(1L), dispatched)
        }

    @Test
    fun `negative debounce is rejected`() =
        runTest(dispatcher) {
            val scope = this

            assertThrows(IllegalArgumentException::class.java) {
                PairSignalCoordinator(scope = scope, debounceMs = -1L)
            }
        }

    @Test
    fun `negative dispatch interval is rejected`() =
        runTest(dispatcher) {
            val scope = this

            assertThrows(IllegalArgumentException::class.java) {
                PairSignalCoordinator(scope = scope, minDispatchIntervalMs = -1L)
            }
        }

    @Test
    fun `repeat dispatch is delayed to the window boundary instead of dropped`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val dispatched = mutableListOf<Long>()

            coordinator.signal(1L, dispatched::add)
            runCurrent()
            assertEquals(listOf(1L), dispatched)

            advanceTimeBy(1_000L)
            coordinator.signal(1L, dispatched::add)

            advanceTimeBy(PairSignalCoordinator.DEFAULT_MIN_DISPATCH_INTERVAL_MS - 1_001L)
            runCurrent()
            assertEquals(listOf(1L), dispatched)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(1L, 1L), dispatched)
            assertEquals(
                PairSignalCoordinator.DEFAULT_MIN_DISPATCH_INTERVAL_MS,
                testScheduler.currentTime,
            )
        }

    @Test
    fun `signal after the window elapsed dispatches without extra delay`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val dispatched = mutableListOf<Long>()

            coordinator.signal(1L, dispatched::add)
            runCurrent()

            advanceTimeBy(PairSignalCoordinator.DEFAULT_MIN_DISPATCH_INTERVAL_MS)
            coordinator.signal(1L, dispatched::add)
            runCurrent()

            assertEquals(listOf(1L, 1L), dispatched)
        }

    @Test
    fun `burst inside the window collapses into a single delayed dispatch`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val dispatched = mutableListOf<Long>()

            coordinator.signal(1L, dispatched::add)
            runCurrent()

            repeat(5) {
                advanceTimeBy(1_000L)
                coordinator.signal(1L, dispatched::add)
                runCurrent()
            }

            advanceTimeBy(PairSignalCoordinator.DEFAULT_MIN_DISPATCH_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf(1L, 1L), dispatched)
        }

    @Test
    fun `rate limit windows are per pair`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val dispatched = mutableListOf<Long>()

            coordinator.signal(1L, dispatched::add)
            runCurrent()
            coordinator.signal(1L, dispatched::add)
            coordinator.signal(2L, dispatched::add)
            runCurrent()

            assertEquals(listOf(1L, 2L), dispatched)
        }

    @Test
    fun `restart cannot amplify dispatch when history is persisted`() =
        runTest(dispatcher) {
            val history = InMemoryPairDispatchHistory()
            val dispatched = mutableListOf<Long>()

            coordinator(history).signal(1L, dispatched::add)
            runCurrent()
            assertEquals(listOf(1L), dispatched)

            val restarted = coordinator(history)
            restarted.signal(1L, dispatched::add)
            advanceTimeBy(PairSignalCoordinator.DEFAULT_MIN_DISPATCH_INTERVAL_MS - 1L)
            runCurrent()
            assertEquals(listOf(1L), dispatched)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(1L, 1L), dispatched)
        }

    @Test
    fun `dispatch time in the future is repaired and waits one full window`() =
        runTest(dispatcher) {
            val history = InMemoryPairDispatchHistory()
            history.recordDispatch(1L, PairSignalCoordinator.DEFAULT_MIN_DISPATCH_INTERVAL_MS * 10)
            val coordinator = coordinator(history)
            val dispatched = mutableListOf<Long>()

            coordinator.signal(1L, dispatched::add)
            advanceTimeBy(PairSignalCoordinator.DEFAULT_MIN_DISPATCH_INTERVAL_MS - 1L)
            runCurrent()
            assertTrue(dispatched.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(1L), dispatched)
        }

    /**
     * Builds a coordinator whose clock follows virtual time so window boundaries are deterministic.
     */
    private fun TestScope.coordinator(history: PairDispatchHistory = InMemoryPairDispatchHistory()) =
        PairSignalCoordinator(
            scope = this,
            debounceMs = 0L,
            dispatchHistory = history,
            clock = { testScheduler.currentTime },
        )
}
