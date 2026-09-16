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
}
