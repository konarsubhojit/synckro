package com.synckro.ui.screens.logs

import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-JVM unit tests for [buildLogExportText], which powers the LogsScreen
 * copy / share actions.  Verifies the truncation behaviour added for issue #93.
 */
class LogsScreenExportTest {
    private val dateFormat: SimpleDateFormat =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun event(
        id: Long,
        message: String = "msg-$id",
    ) = SyncEvent(
        id = id,
        pairId = null,
        timestampMs = 0L,
        level = SyncEventLevel.INFO,
        tag = "T",
        message = message,
    )

    @Test
    fun `small histories are exported in full without truncation marker`() {
        val events = (1L..5L).map { event(it) }

        val text = buildLogExportText(events, dateFormat)

        assertEquals(5, text.lineSequence().count())
        assertFalse("No truncation marker expected for small histories", text.startsWith("…"))
        assertTrue(text.contains("msg-1"))
        assertTrue(text.contains("msg-5"))
    }

    @Test
    fun `histories at the cap are exported in full`() {
        val events = (1L..MAX_COPY_SHARE_ENTRIES.toLong()).map { event(it) }

        val text = buildLogExportText(events, dateFormat)

        assertEquals(MAX_COPY_SHARE_ENTRIES, text.lineSequence().count())
        assertFalse(text.startsWith("…"))
    }

    @Test
    fun `histories above the cap are truncated to the most recent entries`() {
        val total = MAX_COPY_SHARE_ENTRIES + 250
        val events = (1L..total.toLong()).map { event(it) }

        val text = buildLogExportText(events, dateFormat)

        val lines = text.lines()
        // 1 header line + MAX_COPY_SHARE_ENTRIES retained entries.
        assertEquals(MAX_COPY_SHARE_ENTRIES + 1, lines.size)
        assertTrue("First line should announce truncation", lines.first().startsWith("…"))
        assertTrue("Header should mention how many entries were omitted", lines.first().contains("250"))
        // The very oldest entries must be gone, the newest must be present.
        assertFalse("Oldest entry must be omitted", text.contains("msg-1 "))
        assertTrue("Newest entry must be present", text.contains("msg-$total"))
    }
}
