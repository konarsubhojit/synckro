package com.synckro.data.telemetry

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.synckro.domain.telemetry.Telemetry
import com.synckro.domain.telemetry.TelemetryFailureCategory
import com.synckro.domain.telemetry.TelemetrySanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/**
 * [Telemetry] implementation backed by Firebase Crashlytics + Analytics.
 *
 * Only ever constructed by [com.synckro.di.TelemetryModule] when a default
 * [FirebaseApp] was actually initialized (i.e. `google-services.json` was
 * present at build time). All string values passed through [setCustomKey],
 * [log], and [logEvent] are scrubbed by [TelemetrySanitizer] before being
 * forwarded to Firebase so that a careless call site can never leak a file
 * path, folder name, or email address into a crash report.
 */
class FirebaseTelemetry
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : Telemetry {
        private val crashlytics: FirebaseCrashlytics? =
            runCatching { FirebaseCrashlytics.getInstance() }
                .onFailure { Timber.w(it, "FirebaseTelemetry: Crashlytics unavailable") }
                .getOrNull()

        private val analytics: FirebaseAnalytics? =
            runCatching { FirebaseAnalytics.getInstance(context) }
                .onFailure { Timber.w(it, "FirebaseTelemetry: Analytics unavailable") }
                .getOrNull()

        override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
            runCatching { crashlytics?.setCrashlyticsCollectionEnabled(enabled) }
        }

        override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
            runCatching { analytics?.setAnalyticsCollectionEnabled(enabled) }
        }

        override fun setCustomKey(
            key: String,
            value: String,
        ) {
            runCatching { crashlytics?.setCustomKey(key, TelemetrySanitizer.sanitize(value)) }
        }

        override fun setCustomKey(
            key: String,
            value: Long,
        ) {
            runCatching { crashlytics?.setCustomKey(key, value) }
        }

        override fun setCustomKey(
            key: String,
            value: Boolean,
        ) {
            runCatching { crashlytics?.setCustomKey(key, value) }
        }

        override fun log(message: String) {
            runCatching { crashlytics?.log(TelemetrySanitizer.sanitize(message)) }
        }

        override fun recordNonFatal(
            throwable: Throwable,
            category: TelemetryFailureCategory,
            extras: Map<String, String>,
        ) {
            runCatching {
                crashlytics?.let { c ->
                    c.setCustomKey("failure_category", category.name)
                    TelemetrySanitizer.sanitizeParams(extras).forEach { (key, value) ->
                        c.setCustomKey(key, value)
                    }
                    c.recordException(throwable)
                }
            }
        }

        override fun logEvent(
            name: String,
            params: Map<String, String>,
        ) {
            runCatching {
                analytics?.let { a ->
                    val bundle = android.os.Bundle()
                    TelemetrySanitizer.sanitizeParams(params).forEach { (key, value) ->
                        bundle.putString(key, value)
                    }
                    a.logEvent(name, bundle)
                }
            }
        }
    }
