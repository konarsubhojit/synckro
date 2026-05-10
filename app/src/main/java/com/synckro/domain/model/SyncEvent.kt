package com.synckro.domain.model

/** Severity levels for a structured sync-event log entry. */
enum class SyncEventLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Short alphanumeric source labels used to tag [SyncEvent] entries.
 *
 * Keeping all tags in one place makes it easy to filter rows in the logs screen
 * and ensures that searches in exported CSVs are stable across refactors.
 */
object SyncEventTag {
    const val Auth = "Auth"
    const val Account = "Account"
    const val PairEditor = "PairEditor"
    const val Scheduler = "Scheduler"
    const val SyncWorker = "SyncWorker"
    const val RemoteEnum = "RemoteEnum"
    const val OpApplier = "OpApplier"
    const val UI = "UI"
    const val Export = "Export"
}

/**
 * A single log entry associated with a sync-pair run.
 *
 * @param id Auto-generated primary key.
 * @param pairId The id of the [SyncPair] that produced this event,
 *   or `null` for events not tied to a specific pair.
 * @param timestampMs Epoch-milliseconds when the event was created.
 * @param level Severity of the event.
 * @param tag Short alphanumeric label (e.g. "SyncWorker", "Retry").
 * @param message Human-readable description.
 */
data class SyncEvent(
    val id: Long = 0,
    val pairId: Long?,
    val timestampMs: Long,
    val level: SyncEventLevel,
    val tag: String,
    val message: String,
)
