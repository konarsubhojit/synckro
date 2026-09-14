package com.synckro.domain.sync

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import org.junit.Assert.assertEquals
import org.junit.Test

class WatcherLifecyclePolicyTest {
    private val android14 = WatcherLifecyclePolicy(sdkInt = 34)
    private val android15 = WatcherLifecyclePolicy(sdkInt = 35)

    @Test
    fun `stops host when nothing is watchable`() {
        WatcherLifecycleTrigger.entries.forEach { trigger ->
            assertEquals(
                WatcherLifecycleAction.STOP,
                android15.decide(trigger, hasWatchablePairs = false, isHostRunning = true),
            )
        }
    }

    @Test
    fun `app foreground always starts host`() {
        assertEquals(
            WatcherLifecycleAction.START,
            android15.decide(WatcherLifecycleTrigger.APP_FOREGROUND, hasWatchablePairs = true),
        )
    }

    @Test
    fun `boot starts host below android 15`() {
        assertEquals(
            WatcherLifecycleAction.START,
            android14.decide(WatcherLifecycleTrigger.BOOT_COMPLETED, hasWatchablePairs = true),
        )
    }

    @Test
    fun `boot defers on android 15 because dataSync start is forbidden`() {
        assertEquals(
            WatcherLifecycleAction.DEFER_UNTIL_APP_FOREGROUND,
            android15.decide(WatcherLifecycleTrigger.BOOT_COMPLETED, hasWatchablePairs = true),
        )
    }

    @Test
    fun `package replaced starts host on every api level`() {
        listOf(android14, android15).forEach { policy ->
            assertEquals(
                WatcherLifecycleAction.START,
                policy.decide(WatcherLifecycleTrigger.PACKAGE_REPLACED, hasWatchablePairs = true),
            )
        }
    }

    @Test
    fun `configuration change only starts host when it already runs`() {
        assertEquals(
            WatcherLifecycleAction.DEFER_UNTIL_APP_FOREGROUND,
            android15.decide(WatcherLifecycleTrigger.CONFIGURATION_CHANGED, hasWatchablePairs = true),
        )
        assertEquals(
            WatcherLifecycleAction.START,
            android15.decide(
                WatcherLifecycleTrigger.CONFIGURATION_CHANGED,
                hasWatchablePairs = true,
                isHostRunning = true,
            ),
        )
    }

    @Test
    fun `running host keeps a deferred boot start alive`() {
        assertEquals(
            WatcherLifecycleAction.START,
            android15.decide(
                WatcherLifecycleTrigger.BOOT_COMPLETED,
                hasWatchablePairs = true,
                isHostRunning = true,
            ),
        )
    }
}

class WatcherPairSelectionTest {
    @Test
    fun `selects only opted-in upload-capable linked pairs`() {
        val pairs =
            listOf(
                pair(id = 1),
                pair(id = 2, instantSyncEnabled = false),
                pair(id = 3, autoSyncEnabled = false),
                pair(id = 4, direction = SyncDirection.REMOTE_TO_LOCAL),
                pair(id = 5, needsReLink = true),
                pair(id = 6, localTreeUri = "  "),
            )

        val selected =
            WatcherPairSelection.selectWatchablePairIds(
                pairs,
                globalAutoSyncEnabled = true,
                globalInstantSyncEnabled = true,
            )

        assertEquals(setOf(1L), selected)
    }

    @Test
    fun `global toggles gate every pair`() {
        val pairs = listOf(pair(id = 1))

        assertEquals(
            emptySet<Long>(),
            WatcherPairSelection.selectWatchablePairIds(pairs, globalAutoSyncEnabled = false, globalInstantSyncEnabled = true),
        )
        assertEquals(
            emptySet<Long>(),
            WatcherPairSelection.selectWatchablePairIds(pairs, globalAutoSyncEnabled = true, globalInstantSyncEnabled = false),
        )
    }

    private fun pair(
        id: Long,
        instantSyncEnabled: Boolean = true,
        autoSyncEnabled: Boolean = true,
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        needsReLink: Boolean = false,
        localTreeUri: String = "content://tree/$id",
    ) = SyncPair(
        id = id,
        displayName = "pair-$id",
        localTreeUri = localTreeUri,
        provider = CloudProviderType.FAKE,
        remoteFolderId = "remote-$id",
        direction = direction,
        autoSyncEnabled = autoSyncEnabled,
        instantSyncEnabled = instantSyncEnabled,
        needsReLink = needsReLink,
    )
}
