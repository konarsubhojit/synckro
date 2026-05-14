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
     * Minimum [SyncEventLevel] that should appear in the UI and exports.
     *
     * Anything below this in the enum's natural order is filtered out, regardless
     * of any user-selected level filter.
     */
    @Volatile
    var minVisibleLevel: SyncEventLevel = defaultMinVisibleLevel

    /** True when [level] passes the visibility gate. */
    fun isVisible(level: SyncEventLevel): Boolean = level.ordinal >= minVisibleLevel.ordinal

    /**
     * The set of levels selectable from the UI under the current gate. Levels below
     * [minVisibleLevel] are omitted so users can't pick a chip that would never match.
     */
    fun visibleLevels(): List<SyncEventLevel> =
        SyncEventLevel.entries.filter { it.ordinal >= minVisibleLevel.ordinal }

    /** Resets [minVisibleLevel] to its build-variant default. Intended for tests. */
    @VisibleForTesting
    fun resetForTests() {
        minVisibleLevel = defaultMinVisibleLevel
    }
}
