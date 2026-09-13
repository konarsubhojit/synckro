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
    const val AUTH = "Auth"
    const val ACCOUNT = "Account"
    const val PAIR_EDITOR = "PairEditor"
    const val SCHEDULER = "Scheduler"
    const val SYNC_WORKER = "SyncWorker"
    const val REMOTE_ENUM = "RemoteEnum"
    const val OP_APPLIER = "OpApplier"
    const val INSTANT_WATCHER = "InstantWatcher"
    const val INSTANT_STABILITY = "InstantStability"
    const val INSTANT_QUEUE = "InstantQueue"
    const val INSTANT_DISPATCH = "InstantDispatch"
    const val INSTANT_OUTCOME = "InstantOutcome"
    const val UI = "UI"
    const val EXPORT = "Export"
}

/**
 * Privacy-safe, searchable event names and message builders for Instant Sync.
 *
 * Messages intentionally include only stable event names and low-cardinality
 * metadata. File paths, SAF URIs, account identifiers, and provider object IDs
 * must stay out of sync events; pair scoping is provided by [SyncEvent.pairId]
 * and category filtering by [SyncEvent.tag].
 */
object SyncEventTaxonomy {
    const val WATCHER_REGISTERED = "instant.watcher.registered"
    const val WATCHER_FALLBACK = "instant.watcher.fallback"
    const val STABILITY_ACCEPTED = "instant.stability.accepted"
    const val STABILITY_DEFERRED = "instant.stability.deferred"
    const val QUEUE_ENQUEUED = "instant.queue.enqueued"
    const val QUEUE_COALESCED = "instant.queue.coalesced"
    const val QUEUE_DROPPED = "instant.queue.dropped"
    const val DISPATCH_ENQUEUED = "instant.dispatch.enqueued"
    const val DISPATCH_QUOTA_FALLBACK = "instant.dispatch.quota_fallback"
    const val OUTCOME_APPLIED = "instant.outcome.applied"
    const val OUTCOME_SKIPPED = "instant.outcome.skipped"
    const val OUTCOME_FAILED = "instant.outcome.failed"

    fun watcherRegistered(source: String): String = format(WATCHER_REGISTERED, "source" to source)

    fun watcherFallback(reason: String): String = format(WATCHER_FALLBACK, "reason" to reason)

    fun stabilityAccepted(changeCount: Int): String = format(STABILITY_ACCEPTED, "changes" to changeCount.toString())

    fun stabilityDeferred(reason: String): String = format(STABILITY_DEFERRED, "reason" to reason)

    fun queueEnqueued(trigger: String): String = format(QUEUE_ENQUEUED, "trigger" to trigger)

    fun queueCoalesced(trigger: String): String = format(QUEUE_COALESCED, "trigger" to trigger)

    fun queueDropped(reason: String): String = format(QUEUE_DROPPED, "reason" to reason)

    fun dispatchEnqueued(runType: String): String = format(DISPATCH_ENQUEUED, "run" to runType)

    fun dispatchQuotaFallback(reason: String): String = format(DISPATCH_QUOTA_FALLBACK, "reason" to reason)

    fun outcomeApplied(operation: String): String = format(OUTCOME_APPLIED, "op" to operation)

    fun outcomeSkipped(
        operation: String,
        reason: String,
    ): String = format(OUTCOME_SKIPPED, "op" to operation, "reason" to reason)

    fun outcomeFailed(
        operation: String,
        reason: String,
    ): String = format(OUTCOME_FAILED, "op" to operation, "reason" to reason)

    fun format(
        event: String,
        vararg fields: Pair<String, String>,
    ): String =
        buildString {
            append(event)
            fields.forEach { (key, value) ->
                append(' ')
                append(normalizeToken(key))
                append('=')
                append(safeValue(key, value))
            }
        }

    fun sanitizeMessage(message: String): String =
        message
            .replace(accountIdEqualsRegex, "$1=<account>")
            .replace(accountIdJsonRegex, "\"$1\":\"<account>\"")
            .replace(emailRegex, "<account>")
            .replace(contentUriRegex, "<uri>")
            .replace(storagePathRegex, "<path>")
            .replace(fileUriRegex, "<uri>")

    private fun safeValue(
        key: String,
        value: String,
    ): String {
        val normalizedKey = key.lowercase()
        if (
            normalizedKey.contains("path") ||
            normalizedKey.contains("uri") ||
            normalizedKey.contains("account") ||
            normalizedKey.contains("email") ||
            normalizedKey.contains("id")
        ) {
            return "<redacted>"
        }
        return normalizeToken(sanitizeMessage(value)).ifBlank { "unknown" }
    }

    private fun normalizeToken(value: String): String =
        value
            .trim()
            .replace(unsafeTokenRegex, "_")
            .trim('_')
            .take(MAX_TOKEN_LENGTH)

    private const val MAX_TOKEN_LENGTH = 64
    private val unsafeTokenRegex = Regex("[^A-Za-z0-9_.:-]+")
    private val storagePathRegex = Regex("(?i)(?:/storage/emulated/\\d+|/sdcard|/mnt/media_rw)/[^\\s,;)\\]\"']*")
    private val fileUriRegex = Regex("(?i)file://[^\\s,;)\\]\"']+")
    private val contentUriRegex = Regex("(?i)content://[^\\s,;)\\]\"']+")
    private val accountIdEqualsRegex = Regex("(?i)\\b(accountId|account_id|account)\\s*=\\s*[^\\s,;)\\]]+")
    private val accountIdJsonRegex = Regex("(?i)\"(accountId|account_id|account)\"\\s*:\\s*\"[^\"]+\"")
    private val emailRegex = Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE)
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
