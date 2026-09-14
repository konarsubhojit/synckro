package com.synckro.data.watcher

import android.os.FileObserver
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import timber.log.Timber

/** A raw signal delivered by a directory observer for one watched directory. */
sealed interface DirectoryWatchSignal {
    /** Content below the watched directory changed. [childName] is a best-effort hint only. */
    data class ContentChanged(
        val childName: String?,
    ) : DirectoryWatchSignal

    /** The kernel dropped events; callers must rescan instead of trusting individual hints. */
    data object Overflow : DirectoryWatchSignal

    /** The backing volume disappeared. */
    data object VolumeUnmounted : DirectoryWatchSignal

    /** The watch was removed (for example the directory was deleted or moved). */
    data object WatchInvalidated : DirectoryWatchSignal
}

/** A started directory observation that can be stopped more than once. */
fun interface DirectoryWatchHandle {
    fun stop()
}

/**
 * Starts observation of a single directory.
 *
 * Implementations must only observe creation and completed-write style signals, never emit from
 * the calling thread synchronously, and may throw on failure so callers can report it.
 */
fun interface DirectoryObserverFactory {
    fun start(
        path: String,
        onSignal: (DirectoryWatchSignal) -> Unit,
    ): DirectoryWatchHandle
}

/**
 * Opportunistic [LocalChangeWatcher] backed by `FileObserver`.
 *
 * Registration only succeeds for pairs whose tree was resolved to a
 * [LocalTreeWatchSource.DirectPath]; anything else is reported as unavailable so the caller uses
 * its declared fallback. Events are coarse rescan prompts, and hints are best-effort paths that
 * may already be stale by the time the listener runs.
 */
class FileObserverLocalChangeWatcher(
    private val sourceProvider: LocalTreeWatchSourceProvider,
    private val observerFactory: DirectoryObserverFactory = AndroidDirectoryObserverFactory,
) : LocalChangeWatcher {
    private val lock = Any()
    private val handles = mutableSetOf<DirectoryWatchHandle>()
    private var isShutdown = false

    override val capability: LocalChangeWatcherCapability = LocalChangeWatcherCapability.Available

    override fun register(
        pairId: Long,
        listener: (LocalChangeEvent) -> Unit,
    ): LocalChangeWatchRegistrationResult {
        synchronized(lock) {
            if (isShutdown) {
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
            }
        }

        val source = sourceProvider.sourceFor(pairId)
        if (source !is LocalTreeWatchSource.DirectPath) return UNAVAILABLE

        val registration = SingleDirectoryRegistration(pairId, source.path, listener)
        val handle =
            try {
                observerFactory.start(source.path, registration::onSignal)
            } catch (e: SecurityException) {
                Timber.w(e, "FileObserverLocalChangeWatcher: denied observing pair %d", pairId)
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.PermissionDenied)
            } catch (e: RuntimeException) {
                Timber.w(e, "FileObserverLocalChangeWatcher: cannot observe pair %d", pairId)
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Unknown(e.message))
            }

        registration.attach(handle)
        synchronized(lock) {
            if (isShutdown) {
                registration.unregister()
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
            }
            handles += handle
        }
        return LocalChangeWatchRegistrationResult.Registered(registration)
    }

    override fun shutdown() {
        val pending =
            synchronized(lock) {
                if (isShutdown) return
                isShutdown = true
                handles.toList().also { handles.clear() }
            }
        pending.forEach { it.stop() }
    }

    private fun forget(handle: DirectoryWatchHandle) {
        synchronized(lock) { handles.remove(handle) }
    }

    private inner class SingleDirectoryRegistration(
        private val pairId: Long,
        private val path: String,
        private val listener: (LocalChangeEvent) -> Unit,
    ) : LocalChangeWatchRegistration {
        private val registrationLock = Any()
        private var handle: DirectoryWatchHandle? = null
        private var isActive = true

        fun attach(started: DirectoryWatchHandle) {
            val stopImmediately =
                synchronized(registrationLock) {
                    handle = started
                    !isActive
                }
            if (stopImmediately) started.stop()
        }

        fun onSignal(signal: DirectoryWatchSignal) {
            val event =
                when (signal) {
                    is DirectoryWatchSignal.ContentChanged ->
                        LocalChangeEvent.Changed(
                            pairId = pairId,
                            locationHint = signal.childName?.let { "$path/$it" } ?: path,
                        )
                    DirectoryWatchSignal.Overflow -> LocalChangeEvent.Changed(pairId)
                    DirectoryWatchSignal.VolumeUnmounted ->
                        LocalChangeEvent.Failure(pairId, LocalChangeWatchFailure.VolumeUnavailable)
                    DirectoryWatchSignal.WatchInvalidated ->
                        LocalChangeEvent.Failure(
                            pairId,
                            LocalChangeWatchFailure.Unknown("file observer watch was removed"),
                        )
                }
            synchronized(registrationLock) {
                if (!isActive) return
                // A failure ends this registration, so stop observing before delivering it.
                if (event is LocalChangeEvent.Failure) isActive = false
            }
            if (event is LocalChangeEvent.Failure) stopHandle()
            listener(event)
        }

        override fun unregister() {
            synchronized(registrationLock) {
                if (!isActive) return
                isActive = false
            }
            stopHandle()
        }

        private fun stopHandle() {
            val started = synchronized(registrationLock) { handle.also { handle = null } } ?: return
            started.stop()
            forget(started)
        }
    }

    private companion object {
        val UNAVAILABLE =
            LocalChangeWatchRegistrationResult.Unavailable(
                LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
            )
    }
}

/**
 * Production [DirectoryObserverFactory] backed by `FileObserver`.
 *
 * The mask is limited to `CREATE | CLOSE_WRITE | MOVED_TO` so partially written files do not
 * generate a signal for every intermediate write.
 */
object AndroidDirectoryObserverFactory : DirectoryObserverFactory {
    private const val WATCH_MASK = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
    private const val EVENT_MASK = 0x0000ffff
    private const val IN_UNMOUNT = 0x00002000
    private const val IN_Q_OVERFLOW = 0x00004000
    private const val IN_IGNORED = 0x00008000

    override fun start(
        path: String,
        onSignal: (DirectoryWatchSignal) -> Unit,
    ): DirectoryWatchHandle {
        @Suppress("DEPRECATION")
        val observer =
            // inotify always reports IN_UNMOUNT, IN_Q_OVERFLOW and IN_IGNORED regardless of the
            // requested mask, so they are handled below without being requested here.
            object : FileObserver(path, WATCH_MASK) {
                override fun onEvent(
                    event: Int,
                    childPath: String?,
                ) {
                    val signal =
                        when {
                            event and IN_UNMOUNT != 0 -> DirectoryWatchSignal.VolumeUnmounted
                            event and IN_Q_OVERFLOW != 0 -> DirectoryWatchSignal.Overflow
                            event and IN_IGNORED != 0 -> DirectoryWatchSignal.WatchInvalidated
                            event and EVENT_MASK and WATCH_MASK != 0 ->
                                DirectoryWatchSignal.ContentChanged(childPath)
                            else -> return
                        }
                    onSignal(signal)
                }
            }
        observer.startWatching()
        return DirectoryWatchHandle { observer.stopWatching() }
    }
}
