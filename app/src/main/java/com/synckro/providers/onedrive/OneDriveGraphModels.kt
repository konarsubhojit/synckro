package com.synckro.providers.onedrive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Microsoft Graph API DriveItem as returned by `/me/drive` endpoints. */
@Serializable
internal data class GraphDriveItem(
    val id: String = "",
    val name: String = "",
    val parentReference: GraphParentReference? = null,
    val file: GraphFileInfo? = null,
    val folder: GraphFolderInfo? = null,
    val size: Long? = null,
    val lastModifiedDateTime: String? = null,
    val eTag: String? = null,
    val deleted: GraphDeletedInfo? = null,
    @SerialName("@microsoft.graph.downloadUrl") val downloadUrl: String? = null,
)

@Serializable
internal data class GraphParentReference(
    val id: String? = null,
    val path: String? = null,
)

@Serializable
internal data class GraphFileInfo(val mimeType: String? = null)

@Serializable
internal data class GraphFolderInfo(val childCount: Int? = null)

/** Present on a DriveItem when it has been deleted in a delta response. */
@Serializable
internal data class GraphDeletedInfo(val state: String? = null)

/** Paged collection of DriveItems (e.g. from `/children`). */
@Serializable
internal data class GraphItemCollection(
    val value: List<GraphDriveItem>,
    @SerialName("@odata.nextLink") val nextLink: String? = null,
)

/** Response from the OneDrive delta endpoint. */
@Serializable
internal data class GraphDeltaResponse(
    val value: List<GraphDriveItem>,
    @SerialName("@odata.deltaLink") val deltaLink: String? = null,
    @SerialName("@odata.nextLink") val nextLink: String? = null,
)

/** Request body for creating a folder under `/children`. */
@Serializable
internal data class GraphCreateFolderRequest(
    val name: String,
    val folder: GraphFolderInfo = GraphFolderInfo(),
    @SerialName("@microsoft.graph.conflictBehavior") val conflictBehavior: String = "fail",
)

/** Request body wrapping conflict-behavior hint for upload session creation. */
@Serializable
internal data class GraphUploadSessionRequest(
    val item: GraphUploadItemProperties? = null,
)

@Serializable
internal data class GraphUploadItemProperties(
    @SerialName("@microsoft.graph.conflictBehavior") val conflictBehavior: String = "replace",
)

/** Upload session returned by `createUploadSession`. */
@Serializable
internal data class GraphUploadSession(
    val uploadUrl: String,
    val expirationDateTime: String? = null,
)

/** Status returned by GET on an active upload session URL. */
@Serializable
internal data class GraphUploadStatus(
    val nextExpectedRanges: List<String> = emptyList(),
    val expirationDateTime: String? = null,
)

/** Parses an ISO-8601 UTC timestamp (e.g. "2024-03-15T10:30:00Z") to epoch millis, or null. */
internal fun parseIso8601(dateTime: String): Long? =
    runCatching { Instant.parse(dateTime).toEpochMilli() }.getOrNull()
