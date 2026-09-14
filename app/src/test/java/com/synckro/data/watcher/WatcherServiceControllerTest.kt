package com.synckro.data.watcher

import com.synckro.domain.sync.WatcherLifecycleAction
import com.synckro.domain.sync.WatcherLifecyclePolicy
import com.synckro.domain.sync.WatcherLifecycleTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WatcherServiceControllerTest {
    private val starter = FakeStarter()

    @Test
    fun `starts host when a pair is watchable`() =
        runTest {
            val controller = controller(watchablePairIds = setOf(1L), sdkInt = 34)

            val action = controller.evaluate(WatcherLifecycleTrigger.APP_FOREGROUND)

            assertEquals(WatcherLifecycleAction.START, action)
            assertEquals(1, starter.startCount)
        }

    @Test
    fun `stops a running host when no pair is watchable`() =
        runTest {
            val controller = controller(watchablePairIds = emptySet(), sdkInt = 34)
            controller.onHostStarted()

            val action = controller.evaluate(WatcherLifecycleTrigger.APP_FOREGROUND)

            assertEquals(WatcherLifecycleAction.STOP, action)
            assertEquals(1, starter.stopCount)
            assertEquals(0, starter.startCount)
        }

    @Test
    fun `does not stop a host that is not running`() =
        runTest {
            val controller = controller(watchablePairIds = emptySet(), sdkInt = 34)

            controller.evaluate(WatcherLifecycleTrigger.BOOT_COMPLETED)

            assertEquals(0, starter.stopCount)
        }

    @Test
    fun `boot on android 15 defers instead of starting`() =
        runTest {
            val controller = controller(watchablePairIds = setOf(1L), sdkInt = 35)

            val action = controller.evaluate(WatcherLifecycleTrigger.BOOT_COMPLETED)

            assertEquals(WatcherLifecycleAction.DEFER_UNTIL_APP_FOREGROUND, action)
            assertEquals(0, starter.startCount)
        }

    @Test
    fun `refused start is reported as deferred`() =
        runTest {
            starter.startSucceeds = false
            val controller = controller(watchablePairIds = setOf(1L), sdkInt = 34)

            val action = controller.evaluate(WatcherLifecycleTrigger.BOOT_COMPLETED)

            assertEquals(WatcherLifecycleAction.DEFER_UNTIL_APP_FOREGROUND, action)
            assertEquals(1, starter.startCount)
        }

    private fun controller(
        watchablePairIds: Set<Long>,
        sdkInt: Int,
    ) = WatcherServiceController(
        watchablePairs =
            object : WatchablePairs {
                override fun observe(): Flow<Set<Long>> = flowOf(watchablePairIds)

                override suspend fun current(): Set<Long> = watchablePairIds
            },
        starter = starter,
        policy = WatcherLifecyclePolicy(sdkInt),
    )

    private class FakeStarter : WatcherServiceStarter {
        var startSucceeds = true
        var startCount = 0
        var stopCount = 0

        override fun start(): Boolean {
            startCount++
            return startSucceeds
        }

        override fun stop() {
            stopCount++
        }
    }
}
