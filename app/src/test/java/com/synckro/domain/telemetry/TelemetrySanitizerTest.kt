package com.synckro.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the telemetry privacy contract: nothing resembling a file path,
 * content URI, or email address is ever allowed through unscrubbed.
 */
class TelemetrySanitizerTest {
    @Test
    fun `safe categorical values pass through unchanged`() {
        val safeValues =
            listOf(
                "gdrive",
                "onedrive",
                "fake",
                "MIRROR",
                "1-5",
                "10-60s",
                "true",
                "21-100",
                TelemetryFailureCategory.ROOM_DATABASE_ERROR.name,
            )
        for (value in safeValues) {
            assertTrue("expected '$value' to be safe", TelemetrySanitizer.isSafe(value))
            assertEquals(value, TelemetrySanitizer.sanitize(value))
        }
    }

    @Test
    fun `email addresses are rejected and redacted`() {
        val emails =
            listOf(
                "user@example.com",
                "some.person+tag@sub.domain.co",
                "Failed to refresh token for user@example.com",
            )
        for (value in emails) {
            assertFalse("expected '$value' to look like an email", TelemetrySanitizer.isSafe(value))
            assertTrue(TelemetrySanitizer.looksLikeEmail(value))
            assertEquals(TelemetrySanitizer.REDACTED, TelemetrySanitizer.sanitize(value))
        }
    }

    @Test
    fun `file paths and content uris are rejected and redacted`() {
        val paths =
            listOf(
                "/storage/emulated/0/Documents/report.pdf",
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM",
                "file:///data/user/0/com.synckro/cache/foo.txt",
                "C:\\Users\\alice\\Documents\\budget.xlsx",
                "some/relative/path",
            )
        for (value in paths) {
            assertFalse("expected '$value' to look like a path", TelemetrySanitizer.isSafe(value))
            assertTrue(TelemetrySanitizer.looksLikePath(value))
            assertEquals(TelemetrySanitizer.REDACTED, TelemetrySanitizer.sanitize(value))
        }
    }

    @Test
    fun `sanitizeParams scrubs unsafe values while preserving keys and safe values`() {
        val params =
            mapOf(
                "provider" to "gdrive",
                "reason" to "token refresh failed for user@example.com",
                "path" to "/tree/primary:Documents",
                "bucket" to "1-5",
            )

        val result = TelemetrySanitizer.sanitizeParams(params)

        assertEquals("gdrive", result["provider"])
        assertEquals(TelemetrySanitizer.REDACTED, result["reason"])
        assertEquals(TelemetrySanitizer.REDACTED, result["path"])
        assertEquals("1-5", result["bucket"])
        assertEquals(params.keys, result.keys)
    }

    @Test
    fun `sanitize never throws for arbitrary input`() {
        val inputs = listOf("", " ", "\n", "😀", "a".repeat(10_000), "user@@@weird")
        for (value in inputs) {
            TelemetrySanitizer.sanitize(value)
        }
    }
}
