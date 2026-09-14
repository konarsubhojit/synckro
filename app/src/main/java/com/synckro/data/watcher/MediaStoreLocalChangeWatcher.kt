package com.synckro.data.watcher

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.synckro.domain.sync.FileCandidateDecision
import com.synckro.domain.sync.FileCandidatePolicy
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import com.synckro.domain.sync.MediaStorePendingState
import timber.log.Timber

/** The outcome of reading the scoping metadata of a changed MediaStore item. */
sealed interface MediaItemLookup {
    /**
     * Metadata was read. [relativePath] follows the `MediaStore.MediaColumns.RELATIVE_PATH`
     * convention and is `null` when the provider did not report one; [pendingState] mirrors
     * `MediaStore.MediaColumns.IS_PENDING` where the column applies.
     * [displayName] is the item's file name when the provider reported one.
     */
    data class Found(
        val relativePath: String?,
        val pendingState: MediaStorePendingState,
        val displayName: String? = null,
    ) : MediaItemLookup

    /** The item no longer exists, or metadata was inconclusive, so it cannot be scoped. */
    data object Unknown : MediaItemLookup

    /** Reading metadata was denied; the pair must fall back instead of observing blindly. */
    data object PermissionDenied : MediaItemLookup
}

/** Reads pair-scoping metadata for a changed MediaStore item URI. */
fun interface MediaItemMetadataReader {
    fun read(itemUri: String): MediaItemLookup
}

/** A started MediaStore observation that can be stopped more than once. */
fun interface MediaObservationHandle {
    fun stop()
}

/**
 * Starts observation of the given MediaStore [MediaCollection]s.
 *
 * The callback receives the changed item URI, or `null` when the provider reported a change
 * without one.
 */
fun interface MediaCollectionObserverFactory {
    fun start(
        collections: Set<MediaCollection>,
        onChange: (String?) -> Unit,
    ): MediaObservationHandle
}

/**
 * Opportunistic [LocalChangeWatcher] backed by MediaStore notifications.
 *
 * Registration only succeeds for pairs whose tree was resolved to a
 * [LocalTreeWatchSource.MediaStoreTree]. MediaStore notifies for a whole collection, so every
 * notification is re-scoped against the pair's `RELATIVE_PATH` prefix before it is reported, and
 * items still marked `IS_PENDING` are ignored because they are not completely written yet.
 * Notifications that cannot be scoped are dropped rather than widened to the whole pair.
 */
class MediaStoreLocalChangeWatcher(
    private val sourceProvider: LocalTreeWatchSourceProvider,
    private val observerFactory: MediaCollectionObserverFactory,
    private val metadataReader: MediaItemMetadataReader,
) : LocalChangeWatcher {
    private val lock = Any()
    private val handles = mutableSetOf<MediaObservationHandle>()
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
        if (source !is LocalTreeWatchSource.MediaStoreTree) return UNAVAILABLE

        val registration = MediaTreeRegistration(pairId, source.relativePathPrefix, listener)
        val handle =
            try {
                observerFactory.start(source.collections, registration::onChange)
            } catch (e: SecurityException) {
                Timber.w(e, "MediaStoreLocalChangeWatcher: denied observing pair %d", pairId)
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.PermissionDenied)
            } catch (e: RuntimeException) {
                Timber.w(e, "MediaStoreLocalChangeWatcher: cannot observe pair %d", pairId)
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

    private fun forget(handle: MediaObservationHandle) {
        synchronized(lock) { handles.remove(handle) }
    }

    private inner class MediaTreeRegistration(
        private val pairId: Long,
        private val relativePathPrefix: String,
        private val listener: (LocalChangeEvent) -> Unit,
    ) : LocalChangeWatchRegistration {
        private val registrationLock = Any()
        private var handle: MediaObservationHandle? = null
        private var isActive = true

        fun attach(started: MediaObservationHandle) {
            val stopImmediately =
                synchronized(registrationLock) {
                    handle = started
                    !isActive
                }
            if (stopImmediately) started.stop()
        }

        fun onChange(itemUri: String?) {
            synchronized(registrationLock) { if (!isActive) return }
            if (itemUri == null) return

            when (val lookup = metadataReader.read(itemUri)) {
                MediaItemLookup.PermissionDenied -> emitFailure(LocalChangeWatchFailure.PermissionDenied)
                MediaItemLookup.Unknown -> Unit
                is MediaItemLookup.Found -> {
                    if (!isInPairScope(lookup.relativePath)) return
                    if (
                        FileCandidatePolicy.evaluate(
                            relativePath = lookup.displayName,
                            mediaStorePendingState = lookup.pendingState,
                        ) != FileCandidateDecision.Eligible
                    ) {
                        return
                    }
                    val deliver = synchronized(registrationLock) { isActive }
                    if (deliver) listener(LocalChangeEvent.Changed(pairId, locationHint(lookup, itemUri)))
                }
            }
        }

        /**
         * Prefers the item's shared-storage relative path so hints from this watcher can be
         * deduplicated against SAF and `FileObserver` hints for the same file.
         */
        private fun locationHint(
            lookup: MediaItemLookup.Found,
            itemUri: String,
        ): String {
            val relativePath = lookup.relativePath?.trim('/').orEmpty()
            val displayName = lookup.displayName
            return if (relativePath.isEmpty() || displayName.isNullOrEmpty()) {
                itemUri
            } else {
                "$relativePath/$displayName"
            }
        }

        private fun isInPairScope(relativePath: String?): Boolean {
            if (relativePath.isNullOrEmpty()) return false
            val normalized = if (relativePath.endsWith('/')) relativePath else "$relativePath/"
            return normalized.startsWith(relativePathPrefix)
        }

        private fun emitFailure(failure: LocalChangeWatchFailure) {
            synchronized(registrationLock) {
                if (!isActive) return
                isActive = false
            }
            stopHandle()
            listener(LocalChangeEvent.Failure(pairId, failure))
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
 * Production [MediaCollectionObserverFactory] registering a `ContentObserver` per collection.
 *
 * Only the shared external collections are observed; no raw-storage permission is requested.
 */
class ContentResolverMediaCollectionObserverFactory(
    private val resolver: ContentResolver,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : MediaCollectionObserverFactory {
    override fun start(
        collections: Set<MediaCollection>,
        onChange: (String?) -> Unit,
    ): MediaObservationHandle {
        val onItemChange = onChange
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(
                    selfChange: Boolean,
                    uri: Uri?,
                ) {
                    onItemChange(uri?.toString())
                }
            }
        try {
            collections.forEach { collection ->
                resolver.registerContentObserver(collection.externalContentUri(), true, observer)
            }
        } catch (e: RuntimeException) {
            resolver.unregisterContentObserver(observer)
            throw e
        }
        return MediaObservationHandle { resolver.unregisterContentObserver(observer) }
    }
}

/**
 * Production [MediaItemMetadataReader] reading `RELATIVE_PATH` and `IS_PENDING` for one item.
 *
 * `IS_PENDING` only exists from API 29, so older platforms report items as not pending and rely on
 * the stability gate instead.
 */
class ContentResolverMediaItemMetadataReader(
    private val resolver: ContentResolver,
) : MediaItemMetadataReader {
    override fun read(itemUri: String): MediaItemLookup {
        val supportsPending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection =
            if (supportsPending) {
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.IS_PENDING,
                )
            } else {
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
            }
        return try {
            resolver.query(Uri.parse(itemUri), projection, null, null, null).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return MediaItemLookup.Unknown
                val relativePathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val relativePath =
                    if (relativePathIndex >= 0) cursor.getString(relativePathIndex) else null
                val pendingState =
                    if (supportsPending) {
                        val pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                        if (pendingIndex < 0 || cursor.isNull(pendingIndex)) {
                            MediaStorePendingState.UNAVAILABLE
                        } else if (cursor.getInt(pendingIndex) == 1) {
                            MediaStorePendingState.PENDING
                        } else {
                            MediaStorePendingState.NOT_PENDING
                        }
                    } else {
                        MediaStorePendingState.NOT_APPLICABLE
                    }
                val displayNameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val displayName = if (displayNameIndex >= 0) cursor.getString(displayNameIndex) else null
                MediaItemLookup.Found(
                    relativePath = relativePath,
                    pendingState = pendingState,
                    displayName = displayName,
                )
            }
        } catch (e: SecurityException) {
            Timber.w(e, "ContentResolverMediaItemMetadataReader: metadata access denied")
            MediaItemLookup.PermissionDenied
        } catch (e: RuntimeException) {
            Timber.w(e, "ContentResolverMediaItemMetadataReader: metadata unavailable")
            MediaItemLookup.Unknown
        }
    }
}

/** Maps a [MediaCollection] to its shared external-storage content URI. */
internal fun MediaCollection.externalContentUri(): Uri =
    when (this) {
        MediaCollection.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaCollection.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaCollection.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
