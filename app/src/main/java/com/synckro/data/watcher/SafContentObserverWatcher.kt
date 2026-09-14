package com.synckro.data.watcher

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalFolderAccessChecker
import com.synckro.domain.model.allowsUpload
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import com.synckro.domain.sync.LocalChangeWatcherRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * SAF-backed [LocalChangeWatcher] that registers one [ContentObserver] per pair tree URI.
 */
class SafContentObserverWatcher(
    private val syncPairDao: SyncPairDao,
    private val localFolderAccessChecker: LocalFolderAccessChecker,
    private val observerRegistry: ContentObserverRegistry,
    private val observerHandler: Handler = Handler(Looper.getMainLooper()),
) : LocalChangeWatcher,
    LocalChangeWatcherRefresher {
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
            } ?: return LocalChangeWatchRegistrationResult.Failed(
                LocalChangeWatchFailure.Unknown("pair_not_found"),
            )

        if (!pair.hasWatchableSource()) {
            return unavailableResult()
        }

        if (!localFolderAccessChecker.hasReadWriteAccess(pair.localTreeUri)) {
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
        } catch (_: SecurityException) {
            return unavailableResult()
        }

        var isUnregistered = false
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
        val listenersAndEvent =
            synchronized(lock) {
                val registration = registrationsByPairId[pairId] ?: return
                if (!localFolderAccessChecker.hasReadWriteAccess(registration.treeUriString)) {
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

    private fun SyncPairEntity.hasWatchableSource(): Boolean =
        direction.allowsUpload && localTreeUri.isNotBlank() && autoSyncEnabled

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
