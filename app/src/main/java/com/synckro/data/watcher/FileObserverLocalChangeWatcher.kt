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
import java.io.File

/** A raw signal delivered by a directory observer for one watched directory. */
sealed interface DirectoryWatchSignal {
    /** Content below the watched directory changed. [childName] is a best-effort hint only. */
    data class ContentChanged(
        val childName: String?,
        val isDirectory: Boolean? = null,
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
    private val directoryTreeReader: DirectoryTreeReader = FileSystemDirectoryTreeReader,
    private val maxWatches: Int = DEFAULT_MAX_WATCHES,
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

        val registration =
            RecursiveDirectoryRegistration(
                pairId = pairId,
                rootPath = source.path,
                includeSubfolders = !sourceProvider.excludeSubfolders(pairId),
                listener = listener,
            )
        try {
            registration.start()
        } catch (_: WatchLimitExceeded) {
            registration.unregister()
            return UNAVAILABLE
        } catch (e: SecurityException) {
            registration.unregister()
            Timber.w(e, "FileObserverLocalChangeWatcher: denied observing pair %d", pairId)
            return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.PermissionDenied)
        } catch (e: RuntimeException) {
            registration.unregister()
            Timber.w(e, "FileObserverLocalChangeWatcher: cannot observe pair %d", pairId)
            return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Unknown(e.message))
        }
        synchronized(lock) {
            if (isShutdown) {
                registration.unregister()
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
            }
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

    private inner class RecursiveDirectoryRegistration(
        private val pairId: Long,
        private val rootPath: String,
        private val includeSubfolders: Boolean,
        private val listener: (LocalChangeEvent) -> Unit,
    ) : LocalChangeWatchRegistration {
        private val registrationLock = Any()
        private val handlesByPath = mutableMapOf<String, DirectoryWatchHandle>()
        private val visitedCanonicalPaths = mutableSetOf<String>()
        private var isActive = true

        fun start() {
            watchDirectory(rootPath)
            if (includeSubfolders) {
                directoryTreeReader.subdirectories(rootPath).forEach(::watchRecursively)
            }
        }

        private fun watchRecursively(path: String) {
            watchDirectory(path)
            directoryTreeReader.subdirectories(path).forEach(::watchRecursively)
        }

        private fun watchDirectory(path: String) {
            val canonicalPath = directoryTreeReader.canonicalPath(path) ?: return
            synchronized(registrationLock) {
                if (!isActive || !visitedCanonicalPaths.add(canonicalPath)) return
                if (handlesByPath.size >= maxWatches) throw WatchLimitExceeded()
            }
            val handle = observerFactory.start(path) { signal -> onSignal(path, signal) }
            val stopImmediately =
                synchronized(registrationLock) {
                    if (!isActive) {
                        true
                    } else {
                        handlesByPath[path] = handle
                        false
                    }
                }
            if (stopImmediately) {
                handle.stop()
            } else {
                synchronized(lock) { handles += handle }
            }
        }

        fun onSignal(
            watchedPath: String,
            signal: DirectoryWatchSignal,
        ) {
            if (
                includeSubfolders &&
                signal is DirectoryWatchSignal.ContentChanged &&
                signal.isDirectory == true &&
                signal.childName != null
            ) {
                runCatching { watchRecursively("$watchedPath/${signal.childName}") }
            }
            val event =
                when (signal) {
                    is DirectoryWatchSignal.ContentChanged ->
                        LocalChangeEvent.Changed(
                            pairId = pairId,
                            locationHint = signal.childName?.let { "$watchedPath/$it" } ?: watchedPath,
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
            stopHandles()
        }

        private fun stopHandle() = stopHandles()

        private fun stopHandles() {
            val started =
                synchronized(registrationLock) {
                    handlesByPath.values.toList().also { handlesByPath.clear() }
                }
            started.forEach {
                it.stop()
                synchronized(lock) { handles.remove(it) }
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_WATCHES = 512
        val UNAVAILABLE =
            LocalChangeWatchRegistrationResult.Unavailable(
                LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
            )
    }
}

/** Lists real child directories and canonicalizes them to avoid symlink watch loops. */
fun interface DirectoryTreeReader {
    fun subdirectories(path: String): List<String>

    fun canonicalPath(path: String): String? = runCatching { File(path).canonicalPath }.getOrNull()
}

object FileSystemDirectoryTreeReader : DirectoryTreeReader {
    override fun subdirectories(path: String): List<String> =
        File(path).listFiles()?.filter(File::isDirectory)?.map(File::getPath).orEmpty()
}

private class WatchLimitExceeded : RuntimeException()

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
                                DirectoryWatchSignal.ContentChanged(
                                    childPath,
                                    childPath?.let { File(path, it).isDirectory },
                                )
                            else -> return
                        }
                    onSignal(signal)
                }
            }
        observer.startWatching()
        return DirectoryWatchHandle { observer.stopWatching() }
    }
}
