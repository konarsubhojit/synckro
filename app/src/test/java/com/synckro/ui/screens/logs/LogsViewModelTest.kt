package com.synckro.ui.screens.logs

import androidx.lifecycle.SavedStateHandle
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.util.logging.LogExporter
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

    private fun newViewModel(): LogsViewModel =
        LogsViewModel(
            savedStateHandle = SavedStateHandle(),
            syncEventRepository = syncEventRepository,
            logExporter = logExporter,
            accountRepository = accountRepository,
            syncPairDao = syncPairDao,
        )

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
}
