package com.synckro.domain.telemetry

import javax.inject.Inject

/**
 * No-op [Telemetry] implementation used when Firebase is unconfigured (no
 * `google-services.json` at build time) or the user has opted out of both
 * crash reporting and analytics. Every call is a cheap discard — safe to use
 * as the default in tests and previews without any Android/Firebase
 * dependency.
 */
class NoOpTelemetry
    @Inject
    constructor() : Telemetry {
        override fun setCrashlyticsCollectionEnabled(enabled: Boolean) = Unit

        override fun setAnalyticsCollectionEnabled(enabled: Boolean) = Unit

        override fun setCustomKey(
            key: String,
            value: String,
        ) = Unit

        override fun setCustomKey(
            key: String,
            value: Long,
        ) = Unit

        override fun setCustomKey(
            key: String,
            value: Boolean,
        ) = Unit

        override fun log(message: String) = Unit

        override fun recordNonFatal(
            throwable: Throwable,
            category: TelemetryFailureCategory,
            extras: Map<String, String>,
        ) = Unit

        override fun logEvent(
            name: String,
            params: Map<String, String>,
        ) = Unit
    }
