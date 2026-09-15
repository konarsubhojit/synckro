package com.synckro.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEventTaxonomyTest {
    @Test
    fun `apply start event is not reported as a queue enqueue`() {
        assertEquals("instant.apply.started", SyncEventTaxonomy.applyStarted())
    }
}
