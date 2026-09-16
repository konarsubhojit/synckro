package com.synckro.domain.model

import com.synckro.util.logging.LogVisibilityConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEventTaxonomyTest {
    @Test
    fun `apply start event is not reported as a queue enqueue`() {
        assertEquals("instant.apply.started", SyncEventTaxonomy.applyStarted())
        assertTrue(SyncEventTag.INSTANT_APPLY in LogVisibilityConfig.userFacingTags)
    }

    @Test
    fun `instant watch events are user facing and privacy safe`() {
        val callback = SyncEventTaxonomy.watchCallback("authority", false, true, 3)
        val rescan = SyncEventTaxonomy.watchRescan(5)
        val delegateFailed = SyncEventTaxonomy.watchDelegateFailed("file_observer", "permission_denied")

        assertTrue(SyncEventTag.INSTANT_WATCH in LogVisibilityConfig.userFacingTags)
        assertTrue(callback.contains(SyncEventTaxonomy.WATCH_CALLBACK))
        assertTrue('/' !in callback)
        assertTrue(rescan.contains("candidates=5"))
        assertTrue('/' !in rescan)
        assertTrue(delegateFailed.contains(SyncEventTaxonomy.WATCH_DELEGATE_FAILED))
        assertTrue('/' !in delegateFailed)
    }
}
