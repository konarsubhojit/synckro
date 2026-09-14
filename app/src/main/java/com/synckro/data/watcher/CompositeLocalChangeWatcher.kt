package com.synckro.data.watcher

import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback

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
 * Fans registrations out to several [LocalChangeWatcher]s and delivers de-duplicated events.
 *
 * This is how the opportunistic `FileObserver` and MediaStore watchers combine with the SAF
 * observer: whichever source notices a write first wins, and the redundant notifications from the
 * other sources are suppressed by [LocalChangeEventDeduper].
 *
 * A delegate failure only ends the composite registration when no other delegate is still
 * observing the pair, so losing an opportunistic source does not disable the remaining ones.
 */
class CompositeLocalChangeWatcher(
    private val delegates: List<LocalChangeWatcher>,
    private val deduper: LocalChangeEventDeduper,
) : LocalChangeWatcher {
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

        val fanOut = FanOutRegistration(pairId, listener)
        var firstFailure: LocalChangeWatchFailure? = null
        var unavailable: LocalChangeWatcherCapability.Unavailable? = null
        delegates.forEach { delegate ->
            when (val result = delegate.register(pairId, fanOut::onEvent)) {
                is LocalChangeWatchRegistrationResult.Registered -> fanOut.add(result.registration)
                is LocalChangeWatchRegistrationResult.Failed ->
                    firstFailure = firstFailure ?: result.failure
                is LocalChangeWatchRegistrationResult.Unavailable ->
                    unavailable = unavailable ?: result.capability
            }
        }

        if (!fanOut.hasDelegates()) {
            val failure = firstFailure
            return when {
                failure != null -> LocalChangeWatchRegistrationResult.Failed(failure)
                else ->
                    LocalChangeWatchRegistrationResult.Unavailable(
                        unavailable
                            ?: LocalChangeWatcherCapability.Unavailable(
                                LocalChangeWatcherFallback.PERIODIC_SCAN,
                            ),
                    )
            }
        }

        synchronized(lock) {
            if (isShutdown) {
                fanOut.unregister()
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
            }
        }
        return LocalChangeWatchRegistrationResult.Registered(fanOut)
    }

    override fun shutdown() {
        synchronized(lock) {
            if (isShutdown) return
            isShutdown = true
        }
        delegates.forEach { it.shutdown() }
    }

    private inner class FanOutRegistration(
        private val pairId: Long,
        private val listener: (LocalChangeEvent) -> Unit,
    ) : LocalChangeWatchRegistration {
        private val registrationLock = Any()
        private val registrations = mutableListOf<LocalChangeWatchRegistration>()
        private var activeDelegates = 0
        private var isActive = true

        fun add(registration: LocalChangeWatchRegistration) {
            val stopImmediately =
                synchronized(registrationLock) {
                    registrations += registration
                    activeDelegates++
                    !isActive
                }
            if (stopImmediately) registration.unregister()
        }

        fun hasDelegates(): Boolean = synchronized(registrationLock) { registrations.isNotEmpty() }

        fun onEvent(event: LocalChangeEvent) {
            if (event is LocalChangeEvent.Failure) {
                val ended =
                    synchronized(registrationLock) {
                        if (!isActive) return
                        activeDelegates = (activeDelegates - 1).coerceAtLeast(0)
                        if (activeDelegates == 0) isActive = false
                        activeDelegates == 0
                    }
                if (!ended) return
                deduper.forget(pairId)
                listener(event)
                return
            }

            synchronized(registrationLock) { if (!isActive) return }
            if (deduper.shouldDeliver(event)) listener(event)
        }

        override fun unregister() {
            val pending =
                synchronized(registrationLock) {
                    if (!isActive) return
                    isActive = false
                    registrations.toList().also { registrations.clear() }
                }
            pending.forEach { it.unregister() }
            deduper.forget(pairId)
        }
    }
}
