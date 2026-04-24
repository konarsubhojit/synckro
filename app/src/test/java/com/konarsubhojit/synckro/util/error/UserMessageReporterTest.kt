package com.konarsubhojit.synckro.util.error

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageReporterTest {

    @Test
    fun `report emits to messages flow`() = runTest {
        val reporter = UserMessageReporter()
        reporter.messages.test {
            reporter.report(UserMessage("hello", UserMessage.Severity.INFO))
            val msg = awaitItem()
            assertEquals("hello", msg.text)
            assertEquals(UserMessage.Severity.INFO, msg.severity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reportError sets severity to ERROR`() = runTest {
        val reporter = UserMessageReporter()
        reporter.messages.test {
            reporter.reportError("boom")
            val msg = awaitItem()
            assertEquals(UserMessage.Severity.ERROR, msg.severity)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
