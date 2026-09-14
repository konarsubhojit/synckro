package com.synckro.data.watcher

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.fs.LocalFolderAccessChecker
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
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
) : LocalChangeWatcher {
    override val capability: LocalChangeWatcherCapability = LocalChangeWatcherCapability.Available

    private val lock = Any()
    private val registrationsByPairId = mutableMapOf<Long, PairRegistration>()
    private var isShutdown = false

    override fun register(
        pairId: Long,
        listener: (LocalChangeEvent) -> Unit,
    ): LocalChangeWatchRegistrationResult {
        val pair =
            runBlocking(Dispatchers.IO) { syncPairDao.getById(pairId) }
                ?: return LocalChangeWatchRegistrationResult.Failed(
                    LocalChangeWatchFailure.Unknown("pair_not_found"),
                )

        if (!pair.direction.allowsUpload() || pair.localTreeUri.isBlank()) {
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

                val existing = registrationsByPairId[pairId]
                if (existing != null && existing.treeUriString != pair.localTreeUri) {
                    registrationsByPairId.remove(pairId)
                    observerRegistry.unregisterContentObserver(existing.observer)
                }

                val registration =
                    registrationsByPairId.getOrPut(pairId) {
                        val treeUri = Uri.parse(pair.localTreeUri)
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
                        PairRegistration(
                            treeUriString = pair.localTreeUri,
                            observer = observer,
                            listeners = mutableListOf(),
                        )
                    }
                registration.listeners.add(RegisteredListener(observerRegistrationToken, listener))
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

    override fun shutdown() {
        synchronized(lock) {
            if (isShutdown) return
            isShutdown = true
            registrationsByPairId.values.forEach { observerRegistry.unregisterContentObserver(it.observer) }
            registrationsByPairId.clear()
        }
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

interface ContentObserverRegistry {
    fun registerContentObserver(
        uri: Uri,
        notifyForDescendants: Boolean,
        observer: ContentObserver,
    )

    fun unregisterContentObserver(observer: ContentObserver)
}

class ContentResolverContentObserverRegistry(
    private val contentResolver: android.content.ContentResolver,
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

private fun SyncDirection.allowsUpload(): Boolean =
    this != SyncDirection.REMOTE_TO_LOCAL && this != SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS
