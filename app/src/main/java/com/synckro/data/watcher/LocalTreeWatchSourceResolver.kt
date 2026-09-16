package com.synckro.data.watcher

import java.io.File

/**
 * An opportunistic observation source resolved for a pair's persisted local tree.
 *
 * Resolution is deliberately conservative: a SAF tree is only mapped to a real path or to a
 * MediaStore scope when the mapping is provably safe. Arbitrary provider URIs never produce a
 * guessed filesystem path.
 */
sealed interface LocalTreeWatchSource {
    /**
     * The tree maps to a readable directory that can be observed with `FileObserver`.
     *
     * [path] has been validated as an existing, readable directory by the resolver's
     * [DirectPathValidator]; no raw-storage permission is requested to obtain it.
     */
    data class DirectPath(
        val path: String,
    ) : LocalTreeWatchSource

    /**
     * The tree maps to a media collection subtree that can be observed through MediaStore.
     *
     * [relativePathPrefix] always ends with `/` and keeps observation scoped to the pair's
     * subtree, matching the `MediaStore.MediaColumns.RELATIVE_PATH` convention.
     */
    data class MediaStoreTree(
        val relativePathPrefix: String,
        val collections: Set<MediaCollection>,
    ) : LocalTreeWatchSource

    /** The tree cannot be observed opportunistically; callers must use their declared fallback. */
    data object Unsupported : LocalTreeWatchSource
}

/** MediaStore collections that can carry notifications for a mappable media tree. */
enum class MediaCollection { IMAGES, VIDEO, AUDIO }

/**
 * Confirms that a candidate path is an existing, readable directory for the current process.
 *
 * Implementations must not attempt to acquire additional storage permissions.
 */
fun interface DirectPathValidator {
    fun isObservableDirectory(path: String): Boolean
}

/**
 * Supplies the opportunistic watch source resolved for a sync pair.
 *
 * Implementations typically look up the pair's persisted tree URI and delegate to
 * [LocalTreeWatchSourceResolver]. Returning [LocalTreeWatchSource.Unsupported] is always safe.
 */
fun interface LocalTreeWatchSourceProvider {
    fun sourceFor(pairId: Long): LocalTreeWatchSource

    /** Whether recursive directory watches are disabled for this pair. */
    fun excludeSubfolders(pairId: Long): Boolean = false
}

/**
 * Maps persisted SAF tree URIs to opportunistic [LocalTreeWatchSource]s.
 *
 * Only `com.android.externalstorage.documents` trees on the primary volume are considered
 * mappable, because their document ids (`primary:<relative>`) are the one documented case in
 * which a SAF tree corresponds to a stable shared-storage location. Every other authority,
 * volume, or malformed document id resolves to [LocalTreeWatchSource.Unsupported] so callers fall
 * back cleanly instead of watching a guessed path.
 *
 * @param primaryStorageRoot Absolute path of the primary shared-storage volume, typically
 *   `Environment.getExternalStorageDirectory().absolutePath`.
 * @param directPathValidator Confirms readability before a direct path is offered.
 */
class LocalTreeWatchSourceResolver(
    private val primaryStorageRoot: String,
    private val directPathValidator: DirectPathValidator,
) {
    fun resolve(treeUri: String): LocalTreeWatchSource {
        val relativePath = primaryVolumeRelativePath(treeUri) ?: return LocalTreeWatchSource.Unsupported
        val root = primaryStorageRoot.trimEnd('/')
        val candidatePath = if (relativePath.isEmpty()) root else "$root/$relativePath"
        if (directPathValidator.isObservableDirectory(candidatePath)) {
            return LocalTreeWatchSource.DirectPath(candidatePath)
        }

        val collections = MEDIA_COLLECTIONS_BY_DIRECTORY[relativePath.substringBefore('/')]
        return if (collections == null) {
            LocalTreeWatchSource.Unsupported
        } else {
            LocalTreeWatchSource.MediaStoreTree(
                relativePathPrefix = "$relativePath/",
                collections = collections,
            )
        }
    }

    /**
     * Returns the primary-volume relative path of [treeUri], or `null` when the URI is not a
     * safely mappable external-storage tree. The root of the volume maps to an empty string.
     */
    private fun primaryVolumeRelativePath(treeUri: String): String? {
        val treePrefix = "content://$EXTERNAL_STORAGE_AUTHORITY/tree/"
        if (!treeUri.startsWith(treePrefix)) return null
        val encodedDocumentId = treeUri.removePrefix(treePrefix).substringBefore("/document/")
        val documentId = PercentDecoder.decode(encodedDocumentId) ?: return null
        if (!documentId.startsWith(PRIMARY_VOLUME_PREFIX)) return null

        val relativePath = documentId.removePrefix(PRIMARY_VOLUME_PREFIX).trim('/')
        if (relativePath.isEmpty()) return ""
        val isSafe =
            relativePath.split('/').all { segment ->
                segment.isNotEmpty() && segment != "." && segment != ".." && !segment.contains('\u0000')
            }
        return relativePath.takeIf { isSafe }
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val PRIMARY_VOLUME_PREFIX = "primary:"

        val MEDIA_COLLECTIONS_BY_DIRECTORY =
            mapOf(
                "DCIM" to setOf(MediaCollection.IMAGES, MediaCollection.VIDEO),
                "Pictures" to setOf(MediaCollection.IMAGES, MediaCollection.VIDEO),
                "Movies" to setOf(MediaCollection.VIDEO),
                "Music" to setOf(MediaCollection.AUDIO),
                "Podcasts" to setOf(MediaCollection.AUDIO),
                "Audiobooks" to setOf(MediaCollection.AUDIO),
                "Recordings" to setOf(MediaCollection.AUDIO),
            )
    }
}

/**
 * Decodes percent escapes without the `application/x-www-form-urlencoded` `+`-to-space rule,
 * which would corrupt names that legitimately contain `+`.
 */
internal object PercentDecoder {
    /** Returns the decoded value, or `null` when [value] contains a malformed escape. */
    fun decode(value: String): String? {
        if (!value.contains('%')) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                if (index + 2 >= value.length) return null
                val byte = value.substring(index + 1, index + 3).toIntOrNull(radix = 16) ?: return null
                bytes.add(byte.toByte())
                index += 3
            } else {
                char.toString().toByteArray(Charsets.UTF_8).forEach(bytes::add)
                index++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}

/**
 * Production [DirectPathValidator] backed by `java.io.File`.
 *
 * A path is only observable when it already exists as a directory the process can read, which is
 * exactly the case in which `FileObserver` works without any raw-storage permission.
 */
object FileSystemDirectPathValidator : DirectPathValidator {
    override fun isObservableDirectory(path: String): Boolean =
        try {
            val file = File(path)
            file.isDirectory && file.canRead()
        } catch (e: SecurityException) {
            false
        }
}
