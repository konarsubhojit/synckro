package com.synckro.util.logging

import androidx.annotation.VisibleForTesting
import com.synckro.BuildConfig
import com.synckro.domain.model.SyncEventLevel

/**
 * Build-variant-aware visibility gate for [SyncEventLevel] log entries.
 *
 * On debug builds every severity is visible. On release builds [SyncEventLevel.DEBUG]
 * entries are hidden from the UI and from exported log bundles to avoid leaking
 * internal detail and to keep signal high for end users.
 *
 * Persistence is unaffected: DEBUG events are still written to the `sync_event`
 * table in every variant so they can be surfaced later via a developer-mode toggle.
 *
 * Exposed as a mutable property so unit tests can simulate either variant without
 * needing to rebuild against a different `BuildConfig`. Tests must restore
 * [minVisibleLevel] to [defaultMinVisibleLevel] in their teardown.
 */
object LogVisibilityConfig {
    /** Default min-visible level resolved at process start from [BuildConfig.DEBUG]. */
    val defaultMinVisibleLevel: SyncEventLevel =
        if (BuildConfig.DEBUG) SyncEventLevel.DEBUG else SyncEventLevel.INFO

    /**
     * Tags considered "user-facing sync activity" — what users expect to see
     * in the Sync history tab. Other tags (Account / PairEditor / Scheduler
     * / UI / Export / internal infra) are still persisted to the `sync_event`
     * table for diagnostics and are included in feedback exports via
     * [com.synckro.util.logging.LogExporter], but they are not surfaced in the
     * Sync history list to keep it focused on:
     *   - autosync / manual sync started / completed
     *   - per-file upload / download / delete with success/failure indicator
     *   - sync errors (those also pass via the level rule below)
     */
    val userFacingTags: Set<String> =
        setOf(
            // SyncWorker emits sync-started / completed / retry / failed events.
            com.synckro.domain.model.SyncEventTag.SYNC_WORKER,
            // SyncOpApplier emits one INFO per file: "Uploaded/Downloaded/
            // Updated/Deleted ... <relativePath>" and one ERROR per failed op.
            "SyncOpApplier",
            // auth-tagged events surface "needs re-link" prompts to the user.
            "auth",
        )

    /**
     * Levels that bypass the [userFacingTags] whitelist so that any error in
     * the system — regardless of which internal subsystem produced it — is
     * still visible to the user in the Sync history tab.
     */
    val tagBypassMinLevel: SyncEventLevel = SyncEventLevel.WARN

    /**
     * Minimum [SyncEventLevel] that should appear in the UI and exports.
     *
     * Anything below this in the enum's natural order is filtered out, regardless
     * of any user-selected level filter.
     */
    @Volatile
    var minVisibleLevel: SyncEventLevel = defaultMinVisibleLevel

    @Volatile
    private var exportConfig: LogExportConfig = LogExportConfig()

    /** True when [level] passes the visibility gate. */
    fun isVisible(level: SyncEventLevel): Boolean = level.ordinal >= minVisibleLevel.ordinal

    /**
     * True when an event with the given [level] / [tag] is appropriate for the
     * Sync history list. Errors / warnings are always visible; lower-severity
     * events are visible only when their tag is in [userFacingTags].
     */
    fun isUserFacing(
        level: SyncEventLevel,
        tag: String,
    ): Boolean {
        if (!isVisible(level)) return false
        if (level.ordinal >= tagBypassMinLevel.ordinal) return true
        return userFacingTags.any { it.equals(tag, ignoreCase = true) }
    }

    /**
     * The set of levels selectable from the UI under the current gate. Levels below
     * [minVisibleLevel] are omitted so users can't pick a chip that would never match.
     */
    fun visibleLevels(): List<SyncEventLevel> =
        SyncEventLevel.entries.filter { it.ordinal >= minVisibleLevel.ordinal }

    fun currentExportConfig(): LogExportConfig = exportConfig

    fun setExportConfig(config: LogExportConfig) {
        exportConfig = config
    }

    /** Resets [minVisibleLevel] to its build-variant default. Intended for tests. */
    @VisibleForTesting
    fun resetForTests() {
        minVisibleLevel = defaultMinVisibleLevel
        exportConfig = LogExportConfig()
    }

    /** Applies optional export redactions to [text]. */
    fun redactForExport(
        text: String,
        config: LogExportConfig,
    ): String {
        var redacted = text
        if (config.redactPaths) {
            redacted = storagePathRegex.replace(redacted, "<path>")
        }
        if (config.redactAccountIds) {
            redacted = accountIdEqualsRegex.replace(redacted, "$1=<account>")
            redacted = accountIdJsonRegex.replace(redacted, "\"$1\":\"<account>\"")
        }
        return redacted
    }

    private val storagePathRegex = Regex("/storage/emulated/\\d+/[^\\s,;)\\]\"']*")
    private val accountIdEqualsRegex = Regex("(?i)\\b(accountId|account_id)\\s*=\\s*[^\\s,;)\\]]+")
    private val accountIdJsonRegex = Regex("(?i)\"(accountId|account_id)\"\\s*:\\s*\"[^\"]+\"")
}

data class LogExportConfig(
    val redactPaths: Boolean = false,
    val redactAccountIds: Boolean = false,
)
