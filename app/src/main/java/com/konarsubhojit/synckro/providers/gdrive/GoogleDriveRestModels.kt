package com.konarsubhojit.synckro.providers.gdrive

import kotlinx.serialization.Serializable
import java.time.Instant

internal const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

/** Fields requested when fetching a single file or a list of files. */
internal const val DRIVE_FILE_FIELDS =
    "id,name,parents,mimeType,size,modifiedTime,md5Checksum"

/** Fields parameter used in files.list responses. */
internal const val DRIVE_LIST_FIELDS = "nextPageToken,files($DRIVE_FILE_FIELDS)"

/**
 * Fields parameter used in changes.list responses.
 * Includes `trashed` so that files moved to the trash can be treated as deletions.
 */
internal const val DRIVE_CHANGES_FILE_FIELDS =
    "$DRIVE_FILE_FIELDS,trashed"

internal const val DRIVE_CHANGES_FIELDS =
    "nextPageToken,newStartPageToken,changes(fileId,removed,file($DRIVE_CHANGES_FILE_FIELDS))"

// ---------------------------------------------------------------------------
// Drive v3 resource models
// ---------------------------------------------------------------------------

/** Drive v3 File resource. */
@Serializable
internal data class DriveFile(
    val id: String = "",
    val name: String = "",
    val parents: List<String>? = null,
    val mimeType: String? = null,
    /** Drive returns file size as a decimal string (files can exceed Int range). */
    val size: String? = null,
    val modifiedTime: String? = null,
    val md5Checksum: String? = null,
    val trashed: Boolean? = null,
)

/** Paged list response from files.list. */
@Serializable
internal data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

/** A single entry in a changes.list response. */
@Serializable
internal data class DriveChange(
    val fileId: String? = null,
    val file: DriveFile? = null,
    /** `true` when the item was permanently deleted or removed from the drive. */
    val removed: Boolean? = null,
    val time: String? = null,
)

/** Full response body from changes.list. */
@Serializable
internal data class DriveChangesListResponse(
    val changes: List<DriveChange> = emptyList(),
    val nextPageToken: String? = null,
    /** Present only on the last page; use this as the token for the next poll. */
    val newStartPageToken: String? = null,
)

/** Response from changes/startPageToken. */
@Serializable
internal data class DriveStartPageTokenResponse(
    val startPageToken: String,
)

// ---------------------------------------------------------------------------
// Request bodies
// ---------------------------------------------------------------------------

/**
 * Metadata sent when initiating a resumable upload session.
 *
 * Null fields are omitted from the serialised JSON via the [Json] instance
 * configured with `explicitNulls = false` in [GoogleDriveRestClient], so only
 * the fields that are explicitly set will appear in the request body.
 */
@Serializable
internal data class DriveUploadMetadata(
    val name: String? = null,
    val parents: List<String>? = null,
    val mimeType: String? = null,
)

/** Request body for creating a folder via files.create. */
@Serializable
internal data class DriveCreateFolderRequest(
    val name: String,
    val parents: List<String>,
    val mimeType: String,
)

/** Request body for trashing a file via files.update. */
@Serializable
internal data class DriveTrashRequest(
    val trashed: Boolean,
)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Parses an ISO-8601 UTC timestamp (e.g. "2024-03-15T10:30:00Z") to epoch millis, or null. */
internal fun parseIso8601(dateTime: String): Long? =
    runCatching { Instant.parse(dateTime).toEpochMilli() }.getOrNull()
