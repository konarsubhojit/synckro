package com.synckro.data.watcher

import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncEventTaxonomy
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Normalizes best-effort location hints so hints describing the same file compare equal.
 *
 * SAF document URIs, absolute shared-storage paths, and MediaStore relative paths are reduced to a
 * volume-relative path; anything that cannot be reduced safely keeps its original form so distinct
 * items are never collapsed together.
 *
 * @param primaryStorageRoot Absolute path of the primary shared-storage volume.
 */
class LocalChangeHintNormalizer(
    private val primaryStorageRoot: String,
) {
    fun normalize(hint: String?): String {
        val value = hint?.trim().orEmpty()
        if (value.isEmpty()) return COARSE_KEY

        val root = primaryStorageRoot.trimEnd('/')
        if (root.isNotEmpty() && value.startsWith("$root/")) {
            return value.removePrefix("$root/").trim('/')
        }
        if (value.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY/")) {
            val encodedDocumentId =
                value.substringAfterLast("/document/", "").ifEmpty {
                    value.substringAfterLast("/tree/", "")
                }
            val documentId = PercentDecoder.decode(encodedDocumentId)
            if (documentId != null && documentId.startsWith(PRIMARY_VOLUME_PREFIX)) {
                return documentId.removePrefix(PRIMARY_VOLUME_PREFIX).trim('/')
            }
        }
        return value.trim('/')
    }

    private companion object {
        const val COARSE_KEY = ""
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val PRIMARY_VOLUME_PREFIX = "primary:"
    }
}

/**
 * Suppresses repeated change notifications for the same pair and location within a short window.
 *
 * Opportunistic watchers overlap with the SAF observer by design, so the same write typically
 * produces several notifications. Failures are never suppressed because they end a registration.
 *
 * Instances are safe for concurrent use.
 */
class LocalChangeEventDeduper(
    private val normalizer: LocalChangeHintNormalizer,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val lastSeenAt = mutableMapOf<Pair<Long, String>, Long>()

    /** Returns whether [event] should be delivered, recording it when it should. */
    fun shouldDeliver(event: LocalChangeEvent): Boolean {
        if (event is LocalChangeEvent.Failure) return true
        val hint = (event as LocalChangeEvent.Changed).locationHint
        val key = event.pairId to normalizer.normalize(hint)
        val now = clock()
        return synchronized(lock) {
            lastSeenAt.entries.removeAll { now - it.value >= windowMillis }
            val previous = lastSeenAt.put(key, now)
            previous == null || now - previous >= windowMillis
        }
    }

    /** Forgets recorded notifications for [pairId], for example when its registration ends. */
    fun forget(pairId: Long) {
        synchronized(lock) { lastSeenAt.keys.removeAll { it.first == pairId } }
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 2_000L
    }
}

/**
 * Selects the first available [LocalChangeWatcher] and delivers de-duplicated events.
 *
 * Delegates are ordered by reliability. An unavailable opportunistic watcher falls through to the
 * next source, while a genuine failure is surfaced to the lifecycle coordinator.
 */
class CompositeLocalChangeWatcher(
    private val delegates: List<LocalChangeWatcher>,
    private val deduper: LocalChangeEventDeduper,
    private val eventRepository: SyncEventRepository? = null,
) : LocalChangeWatcher {
    private val loggingScope = CoroutineScope(Dispatchers.IO)
    private val lock = Any()
    private var isShutdown = false

    override val capability: LocalChangeWatcherCapability
        get() =
            if (delegates.any { it.capability is LocalChangeWatcherCapability.Available }) {
                LocalChangeWatcherCapability.Available
            } else {
                delegates
                    .firstNotNullOfOrNull { it.capability as? LocalChangeWatcherCapability.Unavailable }
                    ?: LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN)
            }

    override fun register(
        pairId: Long,
        listener: (LocalChangeEvent) -> Unit,
    ): LocalChangeWatchRegistrationResult {
        synchronized(lock) {
            if (isShutdown) {
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
            }
        }

        var unavailable: LocalChangeWatcherCapability.Unavailable? = null
        delegates.forEach { delegate ->
            val guardedListener: (LocalChangeEvent) -> Unit = { event ->
                if (event is LocalChangeEvent.Failure) deduper.forget(pairId)
                if (deduper.shouldDeliver(event)) listener(event)
            }
            when (val result = delegate.register(pairId, guardedListener)) {
                is LocalChangeWatchRegistrationResult.Registered -> {
                    log(pairId, SyncEventTaxonomy.watchRegistered(delegateName(delegate)))
                    synchronized(lock) {
                        if (isShutdown) {
                            result.registration.unregister()
                            return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
                        }
                    }
                    return LocalChangeWatchRegistrationResult.Registered(
                        LocalChangeWatchRegistration {
                            result.registration.unregister()
                            deduper.forget(pairId)
                        },
                    )
                }
                is LocalChangeWatchRegistrationResult.Failed -> return result
                is LocalChangeWatchRegistrationResult.Unavailable ->
                    unavailable = unavailable ?: result.capability
            }
        }
        log(pairId, SyncEventTaxonomy.watchUnavailable("no_delegate_available"))
        return LocalChangeWatchRegistrationResult.Unavailable(
            unavailable
                ?: LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
        )
    }

    override fun shutdown() {
        synchronized(lock) {
            if (isShutdown) return
            isShutdown = true
        }
        delegates.forEach { it.shutdown() }
    }

    private fun delegateName(delegate: LocalChangeWatcher): String =
        when (delegate) {
            is FileObserverLocalChangeWatcher -> "file_observer"
            is MediaStoreLocalChangeWatcher -> "media_store"
            is SafContentObserverWatcher -> "saf"
            else -> "other"
        }

    private fun log(
        pairId: Long,
        message: String,
    ) {
        eventRepository ?: return
        loggingScope.launch {
            eventRepository.log(pairId, SyncEventLevel.INFO, SyncEventTag.INSTANT_WATCH, message)
        }
    }
}
