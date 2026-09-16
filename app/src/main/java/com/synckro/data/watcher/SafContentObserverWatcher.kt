package com.synckro.data.watcher

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalFolderAccessChecker
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncEventTaxonomy
import com.synckro.domain.model.allowsUpload
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import com.synckro.domain.sync.LocalChangeWatcherRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * SAF-backed [LocalChangeWatcher] that registers one [ContentObserver] per pair tree URI.
 */
class SafContentObserverWatcher(
    private val syncPairDao: SyncPairDao,
    private val localFolderAccessChecker: LocalFolderAccessChecker,
    private val observerRegistry: ContentObserverRegistry,
    private val observerHandler: Handler = Handler(Looper.getMainLooper()),
    private val eventRepository: SyncEventRepository? = null,
) : LocalChangeWatcher,
    LocalChangeWatcherRefresher {
    private val loggingScope = CoroutineScope(Dispatchers.IO)
    override val capability: LocalChangeWatcherCapability = LocalChangeWatcherCapability.Available

    private val lock = Any()
    private val registrationsByPairId = mutableMapOf<Long, PairRegistration>()
    private var isShutdown = false

    override fun register(
        pairId: Long,
        listener: (LocalChangeEvent) -> Unit,
    ): LocalChangeWatchRegistrationResult {
        // register() is synchronous by contract. Resolve pair metadata on IO to avoid main-thread
        // Room access, then continue with thread-safe observer registration.
        val pair =
            runBlocking(Dispatchers.IO) {
                syncPairDao.getById(pairId)
            } ?: run {
                Timber.i("instant.watch.register.failed pairId=%d reason=pair_not_found", pairId)
                log(pairId, SyncEventTaxonomy.watchUnavailable("pair_not_found"))
                return LocalChangeWatchRegistrationResult.Failed(
                    LocalChangeWatchFailure.Unknown("pair_not_found"),
                )
            }

        if (!pair.hasWatchableSource()) {
            Timber.i(
                "instant.watch.register.unavailable pairId=%d reasons=%s",
                pairId,
                pair.watchabilityFailures().joinToString(","),
            )
            log(pairId, SyncEventTaxonomy.watchUnavailable("pair_not_watchable"))
            return unavailableResult()
        }

        if (!localFolderAccessChecker.hasReadWriteAccess(pair.localTreeUri)) {
            Timber.i("instant.watch.register.unavailable pairId=%d reasons=saf_access_lost", pairId)
            log(pairId, SyncEventTaxonomy.watchUnavailable("saf_access_lost"))
            return unavailableResult()
        }

        val observerRegistrationToken = Any()
        try {
            synchronized(lock) {
                if (isShutdown) {
                    return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
                }

                val registration = registrationsByPairId[pairId]
                if (registration == null) {
                    registrationsByPairId[pairId] = createRegistration(pairId, pair.localTreeUri)
                } else if (registration.treeUriString != pair.localTreeUri) {
                    val existingListeners = registration.listeners.toList()
                    observerRegistry.unregisterContentObserver(registration.observer)
                    val updated = createRegistration(pairId, pair.localTreeUri)
                    updated.listeners.addAll(existingListeners)
                    registrationsByPairId[pairId] = updated
                }

                registrationsByPairId.getValue(pairId).listeners.add(
                    RegisteredListener(observerRegistrationToken, listener),
                )
            }
        } catch (e: SecurityException) {
            Timber.i("instant.watch.register.unavailable pairId=%d reasons=security_exception", pairId)
            log(pairId, SyncEventTaxonomy.watchUnavailable("security_exception"))
            Timber.d(e, "instant.watch.register.security_exception pairId=%d", pairId)
            return unavailableResult()
        }

        var isUnregistered = false
        Timber.i("instant.watch.registered pairId=%d", pairId)
        log(pairId, SyncEventTaxonomy.watchRegistered("saf"))
        return LocalChangeWatchRegistrationResult.Registered(
            LocalChangeWatchRegistration {
                synchronized(lock) {
                    if (isUnregistered) return@LocalChangeWatchRegistration
                    val registration = registrationsByPairId[pairId] ?: return@LocalChangeWatchRegistration
                    registration.listeners.removeAll { it.token === observerRegistrationToken }
                    if (registration.listeners.isEmpty()) {
                        registrationsByPairId.remove(pairId)
                        observerRegistry.unregisterContentObserver(registration.observer)
                    }
                    isUnregistered = true
                }
            },
        )
    }

    /**
     * Reconciles a registration already owned by the lifecycle coordinator.
     * Initial registration remains the coordinator's responsibility.
     */
    override suspend fun refresh(pairId: Long) {
        val pair = syncPairDao.getById(pairId)
        val canWatch =
            pair?.let {
                it.instantSyncEnabled &&
                    it.hasWatchableSource() &&
                    localFolderAccessChecker.hasReadWriteAccess(it.localTreeUri)
            } == true
        val current = synchronized(lock) { registrationsByPairId[pairId] } ?: return
        if (!canWatch) {
            val removed =
                synchronized(lock) {
                    if (registrationsByPairId[pairId] === current) {
                        registrationsByPairId.remove(pairId)
                        true
                    } else {
                        false
                    }
                }
            if (removed) observerRegistry.unregisterContentObserver(current.observer)
            return
        }
        checkNotNull(pair)
        if (current.treeUriString == pair.localTreeUri) return

        val replacement =
            try {
                createRegistration(pairId, pair.localTreeUri)
            } catch (_: SecurityException) {
                return
            }
        val replaced =
            synchronized(lock) {
                if (isShutdown || registrationsByPairId[pairId] !== current) {
                    false
                } else {
                    replacement.listeners.addAll(current.listeners)
                    registrationsByPairId[pairId] = replacement
                    true
                }
            }
        observerRegistry.unregisterContentObserver(
            if (replaced) current.observer else replacement.observer,
        )
    }

    override fun shutdown() {
        synchronized(lock) {
            if (isShutdown) return
            isShutdown = true
            registrationsByPairId.values.forEach { observerRegistry.unregisterContentObserver(it.observer) }
            registrationsByPairId.clear()
        }
    }

    private fun createRegistration(
        pairId: Long,
        treeUriString: String,
    ): PairRegistration {
        val treeUri = Uri.parse(treeUriString)
        val observer =
            object : ContentObserver(observerHandler) {
                override fun onChange(selfChange: Boolean) {
                    onPairChanged(pairId, null)
                }

                override fun onChange(
                    selfChange: Boolean,
                    uri: Uri?,
                ) {
                    onPairChanged(pairId, uri)
                }
            }
        observerRegistry.registerContentObserver(treeUri, true, observer)
        return PairRegistration(
            treeUriString = treeUriString,
            observer = observer,
            listeners = mutableListOf(),
        )
    }

    private fun onPairChanged(
        pairId: Long,
        uri: Uri?,
    ) {
        val registeredTreeUri = synchronized(lock) { registrationsByPairId[pairId]?.treeUriString }
        Timber.i(
            "instant.watch.callback pairId=%d uriNull=%s authority=%s treeRoot=%s pathSegments=%d",
            pairId,
            uri == null,
            uri?.authority ?: "none",
            registeredTreeUri != null && uri?.toString() == registeredTreeUri,
            uri?.pathSegments?.size ?: 0,
        )
        log(
            pairId,
            SyncEventTaxonomy.watchCallback(
                authority = uri?.authority ?: "none",
                uriNull = uri == null,
                treeRoot = registeredTreeUri != null && uri?.toString() == registeredTreeUri,
                segmentCount = uri?.pathSegments?.size ?: 0,
            ),
        )
        val listenersAndEvent =
            synchronized(lock) {
                val registration = registrationsByPairId[pairId] ?: return
                if (!localFolderAccessChecker.hasReadWriteAccess(registration.treeUriString)) {
                    Timber.i("instant.watch.registration.ended pairId=%d reason=saf_access_lost", pairId)
                    registrationsByPairId.remove(pairId)
                    observerRegistry.unregisterContentObserver(registration.observer)
                    return@synchronized registration.listeners.toList() to
                        LocalChangeEvent.Failure(
                            pairId = pairId,
                            failure = LocalChangeWatchFailure.PermissionDenied,
                        )
                }

                registration.listeners.toList() to
                    LocalChangeEvent.Changed(
                        pairId = pairId,
                        locationHint = uri?.toString()?.takeIf { it.isNotBlank() },
                    )
            }

        listenersAndEvent.first.forEach { it.listener(listenersAndEvent.second) }
    }

    private fun unavailableResult(): LocalChangeWatchRegistrationResult.Unavailable =
        LocalChangeWatchRegistrationResult.Unavailable(
            LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
        )

    private fun log(
        pairId: Long,
        message: String,
    ) {
        eventRepository ?: return
        loggingScope.launch {
            eventRepository.log(pairId, SyncEventLevel.INFO, SyncEventTag.INSTANT_WATCH, message)
        }
    }

    private fun SyncPairEntity.hasWatchableSource(): Boolean =
        watchabilityFailures().isEmpty()

    private fun SyncPairEntity.watchabilityFailures(): List<String> =
        buildList {
            if (!direction.allowsUpload) add("direction_not_upload_capable")
            if (localTreeUri.isBlank()) add("blank_tree_uri")
            if (!autoSyncEnabled) add("auto_sync_off")
            if (!instantSyncEnabled) add("instant_sync_off")
        }

    private data class PairRegistration(
        val treeUriString: String,
        val observer: ContentObserver,
        val listeners: MutableList<RegisteredListener>,
    )

    private data class RegisteredListener(
        val token: Any,
        val listener: (LocalChangeEvent) -> Unit,
    )
}

/**
 * Thin abstraction over ContentResolver's observer APIs so watcher behavior can be unit-tested
 * without a live DocumentsProvider.
 */
interface ContentObserverRegistry {
    fun registerContentObserver(
        uri: Uri,
        notifyForDescendants: Boolean,
        observer: ContentObserver,
    )

    fun unregisterContentObserver(observer: ContentObserver)
}

class ContentResolverContentObserverRegistry(
    private val contentResolver: ContentResolver,
) : ContentObserverRegistry {
    override fun registerContentObserver(
        uri: Uri,
        notifyForDescendants: Boolean,
        observer: ContentObserver,
    ) {
        contentResolver.registerContentObserver(uri, notifyForDescendants, observer)
    }

    override fun unregisterContentObserver(observer: ContentObserver) {
        contentResolver.unregisterContentObserver(observer)
    }
}
