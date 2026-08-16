package com.synckro.domain.telemetry

import org.junit.Test

/**
 * [NoOpTelemetry] must be a completely inert implementation: every method is
 * a safe no-op that never throws, regardless of what's passed in. This is
 * the fallback used when Firebase is unconfigured or the user opts out.
 */
class NoOpTelemetryTest {
    private val telemetry: Telemetry = NoOpTelemetry()

    @Test
    fun `all methods are safe no-ops`() {
        telemetry.setCrashlyticsCollectionEnabled(true)
        telemetry.setCrashlyticsCollectionEnabled(false)
        telemetry.setAnalyticsCollectionEnabled(true)
        telemetry.setAnalyticsCollectionEnabled(false)
        telemetry.setCustomKey("provider", "gdrive")
        telemetry.setCustomKey("pair_count_bucket", 5L)
        telemetry.setCustomKey("on_sd_card", true)
        telemetry.log("sync step=1/8: enumerate local complete")
        telemetry.recordNonFatal(RuntimeException("boom"), TelemetryFailureCategory.OTHER)
        telemetry.recordNonFatal(
            RuntimeException("boom"),
            TelemetryFailureCategory.TRANSFER_RETRY_EXHAUSTED,
            mapOf("provider" to "onedrive"),
        )
        telemetry.logEvent(TelemetryEvents.SYNC_COMPLETED)
        telemetry.logEvent(TelemetryEvents.SYNC_FAILED, mapOf("category" to "other"))
        // Reaching this line means nothing above threw.
    }
}
