package com.synckro.ui.screens.logs

import androidx.lifecycle.SavedStateHandle
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.ui.screens.logs.TimeWindow
import com.synckro.util.logging.LogExporter
import com.synckro.util.logging.LogExportConfig
import com.synckro.util.logging.LogVisibilityConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM unit tests covering the build-variant log-visibility gate applied
 * inside [LogsViewModel.matches] (see [LogVisibilityConfig]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var eventsFlow: MutableStateFlow<List<SyncEvent>>
    private lateinit var pairsFlow: MutableStateFlow<List<SyncPairEntity>>
    private lateinit var syncEventRepository: SyncEventRepository
    private lateinit var logExporter: LogExporter
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncPairDao: SyncPairDao

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        eventsFlow = MutableStateFlow(emptyList())
        pairsFlow = MutableStateFlow(emptyList())
        syncEventRepository = mockk(relaxed = true)
        logExporter = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        syncPairDao = mockk(relaxed = true)
        every { syncEventRepository.observeAll(any()) } returns eventsFlow
        every { accountRepository.observeAll() } returns emptyFlow()
        every { syncPairDao.observeAll() } returns pairsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LogVisibilityConfig.resetForTests()
    }

    private fun newViewModel(clock: () -> Long = System::currentTimeMillis): LogsViewModel =
        LogsViewModel(
            savedStateHandle = SavedStateHandle(),
            syncEventRepository = syncEventRepository,
            logExporter = logExporter,
            accountRepository = accountRepository,
            syncPairDao = syncPairDao,
        ).also { it.clock = clock }

    private fun event(id: Long, level: SyncEventLevel): SyncEvent =
        SyncEvent(
            id = id,
            pairId = null,
            timestampMs = id,
            level = level,
            tag = "T",
            message = "msg-$id",
        )

    @Test
    fun `release variant hides DEBUG entries even when DEBUG filter is set`() =
        runTest(dispatcher) {
            LogVisibilityConfig.minVisibleLevel = SyncEventLevel.INFO
            eventsFlow.value = listOf(
                event(1, SyncEventLevel.DEBUG),
                event(2, SyncEventLevel.INFO),
                event(3, SyncEventLevel.WARN),
                event(4, SyncEventLevel.ERROR),
            )
            val vm = newViewModel()
            // Subscribe so the StateFlow starts collecting (WhileSubscribed).
            backgroundScope.launch { vm.state.collect {} }

            // Explicitly select DEBUG — should still surface zero DEBUG events.
            vm.setLevelFilter(SyncEventLevel.DEBUG)
            advanceUntilIdle()
            val filteredOnDebug = vm.state.value.events
            assertTrue(
                "DEBUG selection in release must never surface DEBUG rows",
                filteredOnDebug.none { it.level == SyncEventLevel.DEBUG },
            )
            assertTrue("DEBUG selection should return no rows in release", filteredOnDebug.isEmpty())

            // With no level filter the visible rows are INFO/WARN/ERROR only.
            vm.setLevelFilter(null)
            advanceUntilIdle()
            val unfiltered = vm.state.value.events
            assertEquals(listOf(2L, 3L, 4L), unfiltered.map { it.id })
        }

    @Test
    fun `debug variant surfaces DEBUG entries`() =
        runTest(dispatcher) {
            LogVisibilityConfig.minVisibleLevel = SyncEventLevel.DEBUG
            eventsFlow.value = listOf(
                event(1, SyncEventLevel.DEBUG),
                event(2, SyncEventLevel.INFO),
            )
            val vm = newViewModel()
            backgroundScope.launch { vm.state.collect {} }

            advanceUntilIdle()
            assertEquals(listOf(1L, 2L), vm.state.value.events.map { it.id })

            vm.setLevelFilter(SyncEventLevel.DEBUG)
            advanceUntilIdle()
            assertEquals(listOf(1L), vm.state.value.events.map { it.id })
        }

    @Test
    fun `LAST_HOUR filter hides events older than 1 hour`() =
        runTest(dispatcher) {
            // Fix "now" at 2 hours (in ms). Cutoff = now - 1h = 3_600_000.
            val now = 2 * 60 * 60 * 1_000L
            eventsFlow.value = listOf(
                event(1, SyncEventLevel.INFO).copy(timestampMs = 0L),              // 2h ago → excluded
                event(2, SyncEventLevel.INFO).copy(timestampMs = 3_599_999L),      // 1ms before cutoff → excluded
                event(3, SyncEventLevel.INFO).copy(timestampMs = 3_600_000L),      // exactly at cutoff → included
                event(4, SyncEventLevel.INFO).copy(timestampMs = now),              // now → included
            )
            val vm = newViewModel(clock = { now })
            backgroundScope.launch { vm.state.collect {} }

            vm.setTimeWindowFilter(TimeWindow.LAST_HOUR)
            advanceUntilIdle()

            assertEquals(listOf(3L, 4L), vm.state.value.events.map { it.id })
            assertEquals(TimeWindow.LAST_HOUR, vm.state.value.timeWindowFilter)
            assertTrue(vm.state.value.hasActiveFilters)
        }

    @Test
    fun `LAST_HOUR filter toggles off on re-selection`() =
        runTest(dispatcher) {
            val now = 2 * 60 * 60 * 1_000L  // 2h; cutoff = 3_600_000
            eventsFlow.value = listOf(
                event(1, SyncEventLevel.INFO).copy(timestampMs = 0L),          // older than 1h → excluded when active
                event(2, SyncEventLevel.INFO).copy(timestampMs = now),          // recent → included
            )
            val vm = newViewModel(clock = { now })
            backgroundScope.launch { vm.state.collect {} }

            vm.setTimeWindowFilter(TimeWindow.LAST_HOUR)
            advanceUntilIdle()
            assertEquals(TimeWindow.LAST_HOUR, vm.state.value.timeWindowFilter)
            assertEquals(listOf(2L), vm.state.value.events.map { it.id })

            // Re-tap the same chip — caller passes null to toggle off
            vm.setTimeWindowFilter(null)
            advanceUntilIdle()
            assertEquals(null, vm.state.value.timeWindowFilter)
            assertEquals(listOf(1L, 2L), vm.state.value.events.map { it.id })
        }

    @Test
    fun `LAST_24H and LAST_7D filters apply correct durations`() =
        runTest(dispatcher) {
            val now = 8 * 24 * 60 * 60 * 1_000L  // 8 days in ms
            val oneHourAgo = now - 60 * 60 * 1_000L
            val twentyFiveHoursAgo = now - 25 * 60 * 60 * 1_000L
            val sixDaysAgo = now - 6 * 24 * 60 * 60 * 1_000L
            val eightDaysAgo = now - 8 * 24 * 60 * 60 * 1_000L

            eventsFlow.value = listOf(
                event(1, SyncEventLevel.INFO).copy(timestampMs = eightDaysAgo),    // 8d ago
                event(2, SyncEventLevel.INFO).copy(timestampMs = twentyFiveHoursAgo), // 25h ago
                event(3, SyncEventLevel.INFO).copy(timestampMs = sixDaysAgo),       // 6d ago
                event(4, SyncEventLevel.INFO).copy(timestampMs = oneHourAgo),       // 1h ago
            )
            val vm = newViewModel(clock = { now })
            backgroundScope.launch { vm.state.collect {} }

            vm.setTimeWindowFilter(TimeWindow.LAST_24H)
            advanceUntilIdle()
            assertEquals(listOf(4L), vm.state.value.events.map { it.id })

            vm.setTimeWindowFilter(TimeWindow.LAST_7D)
            advanceUntilIdle()
            assertEquals(listOf(2L, 3L, 4L), vm.state.value.events.map { it.id })
        }

    @Test
    fun `clearFilters resets time-window filter to null`() =
        runTest(dispatcher) {
            val now = 2 * 60 * 60 * 1_000L  // 2h; cutoff for LAST_HOUR = 3_600_000
            eventsFlow.value = listOf(
                event(1, SyncEventLevel.INFO).copy(timestampMs = 0L),          // older than 1h → excluded when active
                event(2, SyncEventLevel.INFO).copy(timestampMs = now),          // recent → included
            )
            val vm = newViewModel(clock = { now })
            backgroundScope.launch { vm.state.collect {} }

            vm.setTimeWindowFilter(TimeWindow.LAST_HOUR)
            advanceUntilIdle()
            assertTrue(vm.state.value.hasActiveFilters)
            assertEquals(listOf(2L), vm.state.value.events.map { it.id })

            vm.clearFilters()
            advanceUntilIdle()
            assertEquals(null, vm.state.value.timeWindowFilter)
            assertEquals(false, vm.state.value.hasActiveFilters)
            assertEquals(listOf(1L, 2L), vm.state.value.events.map { it.id })
        }

    @Test
    fun `time-window and level filters are AND-ed`() =
        runTest(dispatcher) {
            val now = 2 * 60 * 60 * 1_000L  // 2 hours
            eventsFlow.value = listOf(
                event(1, SyncEventLevel.INFO).copy(timestampMs = 0L),          // old INFO
                event(2, SyncEventLevel.WARN).copy(timestampMs = 0L),          // old WARN
                event(3, SyncEventLevel.INFO).copy(timestampMs = now),          // recent INFO
                event(4, SyncEventLevel.WARN).copy(timestampMs = now),          // recent WARN
            )
            val vm = newViewModel(clock = { now })
            backgroundScope.launch { vm.state.collect {} }

            vm.setTimeWindowFilter(TimeWindow.LAST_HOUR)
            vm.setLevelFilter(SyncEventLevel.WARN)
            advanceUntilIdle()

            // Only recent WARN events should survive both filters
            assertEquals(listOf(4L), vm.state.value.events.map { it.id })
        }

    @Test
    fun `export redaction toggles update export config state`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            backgroundScope.launch { vm.state.collect {} }

            assertEquals(LogExportConfig(), vm.exportConfig.value)

            vm.setExportRedactPaths(true)
            vm.setExportRedactAccountIds(true)
            advanceUntilIdle()

            assertEquals(
                LogExportConfig(redactPaths = true, redactAccountIds = true),
                vm.exportConfig.value,
            )

            vm.setExportRedactPaths(false)
            advanceUntilIdle()
            assertEquals(
                LogExportConfig(redactPaths = false, redactAccountIds = true),
                vm.exportConfig.value,
            )
        }
}
