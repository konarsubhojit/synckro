package com.synckro.domain.telemetry

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection

/**
 * Coarse category attached to every non-fatal telemetry report so crash
 * dashboards can be grouped/filtered without needing the (never-logged)
 * exact file path or account identifier involved.
 */
enum class TelemetryFailureCategory {
    /** Upload or download failed after the configured retry budget was exhausted. */
    TRANSFER_RETRY_EXHAUSTED,

    /** A cloud provider's token refresh failed and/or interactive re-auth was triggered. */
    TOKEN_REFRESH_OR_REAUTH,

    /** The SAF-granted local folder permission was lost/revoked. */
    SAF_PERMISSION_LOST,

    /** A Room database operation failed. */
    ROOM_DATABASE_ERROR,

    /** Applying a conflict resolution (keep local / keep remote / keep both) failed. */
    CONFLICT_RESOLUTION_FAILED,

    /** Anything else that doesn't fit the buckets above. */
    OTHER,
}

/** Where in the app a failure/breadcrumb originated, for triage. */
enum class TelemetryRunContext {
    FOREGROUND_SERVICE,
    BACKGROUND_WORK,
    FOREGROUND_APP,
}

/**
 * Platform-free abstraction over crash reporting + product analytics.
 *
 * Domain and data layers depend only on this interface — never on Firebase
 * types directly — so sync logic stays unit-testable and Firebase can be
 * swapped for [NoOpTelemetry] whenever the user opts out or the app is built
 * without `google-services.json`.
 *
 * ## Privacy contract
 * Implementations (and every call site) must never pass file names, folder
 * names, full file paths, account identifiers, email addresses, tokens, or
 * any other user content into any of these methods. Only structural /
 * categorical metadata (counts, enums, bucketed sizes, booleans) is allowed.
 * See [TelemetrySanitizer] for the scrubbing helper used by [setCustomKey],
 * [log], and [logEvent] parameter values.
 */
interface Telemetry {
    /** Enables/disables Crashlytics crash & non-fatal collection at runtime. */
    fun setCrashlyticsCollectionEnabled(enabled: Boolean)

    /** Enables/disables Analytics event collection at runtime. */
    fun setAnalyticsCollectionEnabled(enabled: Boolean)

    /** Attaches a string custom key to future crash reports. */
    fun setCustomKey(
        key: String,
        value: String,
    )

    /** Attaches a numeric custom key to future crash reports. */
    fun setCustomKey(
        key: String,
        value: Long,
    )

    /** Attaches a boolean custom key to future crash reports. */
    fun setCustomKey(
        key: String,
        value: Boolean,
    )

    /**
     * Leaves a breadcrumb log line that is attached to the *next* crash report
     * (Crashlytics keeps the most recent ~64 KB of these). Use for sync
     * lifecycle events: start, enumeration complete, diff computed, apply
     * start/complete.
     */
    fun log(message: String)

    /**
     * Records a non-fatal exception, tagged with [category] and any
     * structural [extras]. Does not crash the app.
     */
    fun recordNonFatal(
        throwable: Throwable,
        category: TelemetryFailureCategory,
        extras: Map<String, String> = emptyMap(),
    )

    /** Logs a product analytics event with structural/categorical [params] only. */
    fun logEvent(
        name: String,
        params: Map<String, String> = emptyMap(),
    )
}

/** Analytics event names used across the app. Kept centralized to avoid typos/drift. */
object TelemetryEvents {
    const val SYNC_COMPLETED = "sync_completed"
    const val SYNC_FAILED = "sync_failed"
    const val CONFLICT_RESOLVED = "conflict_resolved"
    const val PAIR_CREATED = "pair_created"
    const val ACCOUNT_LINKED = "account_linked"
    const val SCHEDULE_PRESET_CHOSEN = "schedule_preset_chosen"
}

/** Custom-key names attached to crash reports. Kept centralized to avoid typos/drift. */
object TelemetryKeys {
    const val PROVIDER = "provider"
    const val SYNC_DIRECTION = "sync_direction"
    const val CONFLICT_POLICY = "conflict_policy"
    const val PAIR_COUNT_BUCKET = "pair_count_bucket"
    const val FILE_INDEX_SIZE_BUCKET = "file_index_size_bucket"
    const val RUN_CONTEXT = "run_context"
    const val ANDROID_API_LEVEL = "android_api_level"
    const val ON_SD_CARD = "on_sd_card"
}

/** Maps a [CloudProviderType] to the lowercase categorical label used in telemetry. */
fun CloudProviderType.toTelemetryLabel(): String =
    when (this) {
        CloudProviderType.FAKE -> "fake"
        CloudProviderType.ONEDRIVE -> "onedrive"
        CloudProviderType.GOOGLE_DRIVE -> "gdrive"
    }

/** Maps a [SyncDirection] to a stable lowercase telemetry label. */
fun SyncDirection.toTelemetryLabel(): String = name.lowercase()

/** Maps a [ConflictPolicy] to a stable lowercase telemetry label. */
fun ConflictPolicy.toTelemetryLabel(): String = name.lowercase()

/**
 * Buckets exact counts/sizes into coarse ranges so crash reports never carry
 * exact per-user data (e.g. "this user has exactly 137 pairs").
 */
object TelemetryBuckets {
    /** Buckets a count of items (pairs, files, conflicts, ...). */
    fun bucketCount(count: Int): String =
        when {
            count <= 0 -> "0"
            count <= 5 -> "1-5"
            count <= 20 -> "6-20"
            count <= 100 -> "21-100"
            count <= 1000 -> "101-1000"
            else -> "1000+"
        }

    /** Buckets a duration in milliseconds. */
    fun bucketDurationMs(durationMs: Long): String =
        when {
            durationMs <= 0 -> "0"
            durationMs <= 1_000 -> "<1s"
            durationMs <= 10_000 -> "1-10s"
            durationMs <= 60_000 -> "10-60s"
            durationMs <= 5 * 60_000 -> "1-5m"
            else -> ">5m"
        }
}
