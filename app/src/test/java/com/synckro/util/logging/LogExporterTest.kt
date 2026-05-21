package com.synckro.util.logging

import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

/**
 * Pure-JVM unit tests for [LogExporter]'s static helpers.
 *
 * [LogExporter.buildExportZip] and [LogExporter.buildCsvBytes] are tested
 * without an Android [Context] or [FileProvider] so the suite can run on the
 * JVM without Robolectric.
 */
class LogExporterTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // buildCsvBytes
    // -------------------------------------------------------------------------

    @Test
    fun `buildCsvBytes includes header row`() {
        val csv = LogExporter.buildCsvBytes(emptyList()).toString(Charsets.UTF_8)
        assertTrue("CSV must start with a header row", csv.startsWith("id,pairId,timestampMs,level,tag,message"))
    }

    @Test
    fun `buildCsvBytes writes one data row per event`() {
        val events = listOf(
            event(id = 1, message = "hello"),
            event(id = 2, message = "world"),
        )
        val csv = LogExporter.buildCsvBytes(events).toString(Charsets.UTF_8)
        val lines = csv.trim().lines()
        // Header + 2 data rows.
        assertEquals(3, lines.size)
    }

    @Test
    fun `buildCsvBytes escapes double-quotes in message`() {
        val events = listOf(event(id = 1, message = "say \"hello\""))
        val csv = LogExporter.buildCsvBytes(events).toString(Charsets.UTF_8)
        // RFC-4180: embedded double-quotes are doubled.
        assertTrue("Embedded quotes must be escaped", csv.contains("\"say \"\"hello\"\"\""))
    }

    @Test
    fun `buildCsvBytes tolerates commas and newlines inside message fields`() {
        val events = listOf(event(id = 1, message = "line 1,\nline 2"))
        val csv = LogExporter.buildCsvBytes(events).toString(Charsets.UTF_8)

        assertTrue(csv.contains("\"line 1,\nline 2\""))
        assertTrue(csv.startsWith("id,pairId,timestampMs,level,tag,message"))
    }

    @Test
    fun `buildCsvBytes writes null pairId as empty`() {
        val events = listOf(event(id = 1, pairId = null))
        val csv = LogExporter.buildCsvBytes(events).toString(Charsets.UTF_8)
        val dataLine = csv.trim().lines()[1]
        // Format: id,,timestampMs,...
        assertTrue("Null pairId should be serialized as empty", dataLine.startsWith("1,,"))
    }

    @Test
    fun `buildCsvBytes writes non-null pairId`() {
        val events = listOf(event(id = 5, pairId = 42))
        val csv = LogExporter.buildCsvBytes(events).toString(Charsets.UTF_8)
        val dataLine = csv.trim().lines()[1]
        assertTrue("PairId should be written as '42'", dataLine.startsWith("5,42,"))
    }

    @Test
    fun `buildCsvBytes applies export redaction config`() {
        val events = listOf(event(id = 1, message = "path=/storage/emulated/0/Pictures/cat.jpg accountId=abc123"))

        val csv =
            LogExporter.buildCsvBytes(
                events = events,
                config = LogExportConfig(redactPaths = true, redactAccountIds = true),
            ).toString(Charsets.UTF_8)

        assertTrue(csv.contains("<path>"))
        assertTrue(csv.contains("accountId=<account>"))
        assertFalse(csv.contains("/storage/emulated/0/Pictures/cat.jpg"))
        assertFalse(csv.contains("accountId=abc123"))
    }

    // -------------------------------------------------------------------------
    // buildExportZip
    // -------------------------------------------------------------------------

    @Test
    fun `buildExportZip produces a zip with events csv entry`() {
        val events = listOf(event(id = 1, message = "test"))
        val outputDir = tmpFolder.newFolder("output")

        val zipFile = LogExporter.buildExportZip(events, emptyList(), outputDir, "20240101-120000")

        assertTrue("Zip file must exist", zipFile.exists())
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertTrue(
                "Zip must contain events CSV",
                entries.any { it.startsWith("synckro-events-") && it.endsWith(".csv") },
            )
        }
    }

    @Test
    fun `buildExportZip events csv parses back to N rows`() {
        val n = 7
        val events = (1..n).map { event(id = it.toLong(), message = "msg-$it") }
        val outputDir = tmpFolder.newFolder("output")

        val zipFile = LogExporter.buildExportZip(events, emptyList(), outputDir, "20240101-120000")

        ZipFile(zipFile).use { zip ->
            val csvEntry = zip.entries().toList().first { it.name.endsWith(".csv") }
            val csvText = zip.getInputStream(csvEntry).bufferedReader().readText()
            val dataLines = csvText.trim().lines().drop(1) // drop header
            assertEquals("CSV must contain exactly $n data rows", n, dataLines.size)
        }
    }

    @Test
    fun `buildExportZip includes log file when present`() {
        val logDir = tmpFolder.newFolder("logs")
        val logFile = logDir.resolve("synckro-debug.log").also { it.writeText("log content here") }
        val outputDir = tmpFolder.newFolder("output")

        val zipFile = LogExporter.buildExportZip(emptyList(), listOf(logFile), outputDir, "20240101-120000")

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertTrue("Zip must contain the Timber log file", entries.contains("synckro-debug.log"))
        }
    }

    @Test
    fun `buildExportZip includes all backup log files`() {
        val logDir = tmpFolder.newFolder("logs")
        val primary = logDir.resolve("synckro-debug.log").also { it.writeText("primary") }
        val backup1 = logDir.resolve("synckro-debug.log.1").also { it.writeText("backup1") }
        val backup2 = logDir.resolve("synckro-debug.log.2").also { it.writeText("backup2") }
        val outputDir = tmpFolder.newFolder("output")

        val zipFile = LogExporter.buildExportZip(
            events = emptyList(),
            logFiles = listOf(primary, backup1, backup2),
            outputDir = outputDir,
            timestamp = "20240101-120000",
        )

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertTrue(entries.contains("synckro-debug.log"))
            assertTrue(entries.contains("synckro-debug.log.1"))
            assertTrue(entries.contains("synckro-debug.log.2"))
        }
    }

    @Test
    fun `buildExportZip log file contents are preserved`() {
        val logDir = tmpFolder.newFolder("logs")
        val expectedContent = "line 1\nline 2\nline 3"
        val logFile = logDir.resolve("synckro-debug.log").also { it.writeText(expectedContent) }
        val outputDir = tmpFolder.newFolder("output")

        val zipFile = LogExporter.buildExportZip(emptyList(), listOf(logFile), outputDir, "ts")

        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("synckro-debug.log")
            val content = zip.getInputStream(entry).bufferedReader().readText()
            assertEquals("Log file content must be preserved verbatim", expectedContent, content)
        }
    }

    @Test
    fun `buildExportZip applies redaction to log files`() {
        val logDir = tmpFolder.newFolder("logs")
        val logFile =
            logDir.resolve("synckro-debug.log").also {
                it.writeText("read /storage/emulated/0/Pictures/cat.jpg for accountId=abc123")
            }
        val outputDir = tmpFolder.newFolder("output")

        val zipFile =
            LogExporter.buildExportZip(
                events = emptyList(),
                logFiles = listOf(logFile),
                outputDir = outputDir,
                timestamp = "ts",
                config = LogExportConfig(redactPaths = true, redactAccountIds = true),
            )

        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("synckro-debug.log")
            val content = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue(content.contains("<path>"))
            assertTrue(content.contains("accountId=<account>"))
            assertFalse(content.contains("/storage/emulated/0/Pictures/cat.jpg"))
            assertFalse(content.contains("accountId=abc123"))
        }
    }

    @Test
    fun `buildExportZip with no events produces an empty csv (header only)`() {
        val outputDir = tmpFolder.newFolder("output")

        val zipFile = LogExporter.buildExportZip(emptyList(), emptyList(), outputDir, "20240101-120000")

        ZipFile(zipFile).use { zip ->
            val csvEntry = zip.entries().toList().first { it.name.endsWith(".csv") }
            val csvText = zip.getInputStream(csvEntry).bufferedReader().readText()
            val lines = csvText.trim().lines()
            // Only the header row, no data rows.
            assertEquals(1, lines.size)
            assertFalse("Must not be blank", lines.first().isBlank())
        }
    }

    // -------------------------------------------------------------------------
    // filterVisibleForExport (build-variant gate)
    // -------------------------------------------------------------------------

    @Test
    fun `filterVisibleForExport drops DEBUG entries when min visible level is INFO`() {
        val original = com.synckro.util.logging.LogVisibilityConfig.minVisibleLevel
        try {
            com.synckro.util.logging.LogVisibilityConfig.minVisibleLevel = SyncEventLevel.INFO
            val events = listOf(
                event(id = 1).copy(level = SyncEventLevel.DEBUG),
                event(id = 2).copy(level = SyncEventLevel.INFO),
                event(id = 3).copy(level = SyncEventLevel.WARN),
                event(id = 4).copy(level = SyncEventLevel.ERROR),
            )

            val filtered = LogExporter.filterVisibleForExport(events)

            assertEquals(listOf(2L, 3L, 4L), filtered.map { it.id })
            assertTrue("DEBUG entries must not appear in release-build exports",
                filtered.none { it.level == SyncEventLevel.DEBUG })
        } finally {
            com.synckro.util.logging.LogVisibilityConfig.minVisibleLevel = original
        }
    }

    @Test
    fun `filterVisibleForExport keeps DEBUG entries when min visible level is DEBUG`() {
        val original = com.synckro.util.logging.LogVisibilityConfig.minVisibleLevel
        try {
            com.synckro.util.logging.LogVisibilityConfig.minVisibleLevel = SyncEventLevel.DEBUG
            val events = listOf(
                event(id = 1).copy(level = SyncEventLevel.DEBUG),
                event(id = 2).copy(level = SyncEventLevel.INFO),
            )

            val filtered = LogExporter.filterVisibleForExport(events)

            assertEquals(listOf(1L, 2L), filtered.map { it.id })
        } finally {
            com.synckro.util.logging.LogVisibilityConfig.minVisibleLevel = original
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun event(
        id: Long = 1L,
        pairId: Long? = null,
        message: String = "test",
    ) = SyncEvent(
        id = id,
        pairId = pairId,
        timestampMs = 1_000L * id,
        level = SyncEventLevel.INFO,
        tag = "Test",
        message = message,
    )
}
