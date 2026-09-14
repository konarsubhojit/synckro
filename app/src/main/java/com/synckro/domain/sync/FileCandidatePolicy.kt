package com.synckro.domain.sync

/**
 * Fail-safe eligibility policy for local file candidates.
 *
 * Temporary-name checks are case-insensitive, take precedence over the general hidden-name rule,
 * and are evaluated from the current leaf name on every call. A later event for a renamed final
 * file is therefore evaluated independently.
 */
object FileCandidatePolicy {
    fun evaluate(
        pathOrName: String?,
        mediaStorePendingState: MediaStorePendingState = MediaStorePendingState.NOT_APPLICABLE,
    ): FileCandidateDecision {
        val fileName = pathOrName?.substringAfterLast('/')
        if (fileName.isNullOrEmpty()) {
            return FileCandidateDecision.Inconclusive(FileCandidateInconclusiveReason.NAME_UNAVAILABLE)
        }
        if (fileName.hasTemporaryName()) {
            return FileCandidateDecision.Excluded(FileCandidateExclusionReason.TEMPORARY_NAME)
        }
        if (fileName.startsWith('.')) {
            return FileCandidateDecision.Excluded(FileCandidateExclusionReason.HIDDEN_NAME)
        }

        return when (mediaStorePendingState) {
            MediaStorePendingState.PENDING ->
                FileCandidateDecision.Excluded(FileCandidateExclusionReason.MEDIASTORE_PENDING)
            MediaStorePendingState.UNAVAILABLE ->
                FileCandidateDecision.Inconclusive(
                    FileCandidateInconclusiveReason.MEDIASTORE_PENDING_UNAVAILABLE,
                )
            MediaStorePendingState.NOT_PENDING,
            MediaStorePendingState.NOT_APPLICABLE,
            -> FileCandidateDecision.Eligible
        }
    }

    private fun String.hasTemporaryName(): Boolean =
        endsWith(".part", ignoreCase = true) ||
            endsWith(".crdownload", ignoreCase = true) ||
            endsWith(".tmp", ignoreCase = true) ||
            startsWith(".pending-", ignoreCase = true) ||
            startsWith("~$")
}

enum class MediaStorePendingState {
    NOT_APPLICABLE,
    NOT_PENDING,
    PENDING,
    UNAVAILABLE,
}

sealed interface FileCandidateDecision {
    data object Eligible : FileCandidateDecision

    data class Excluded(
        val reason: FileCandidateExclusionReason,
    ) : FileCandidateDecision

    data class Inconclusive(
        val reason: FileCandidateInconclusiveReason,
    ) : FileCandidateDecision
}

enum class FileCandidateExclusionReason {
    HIDDEN_NAME,
    TEMPORARY_NAME,
    MEDIASTORE_PENDING,
}

enum class FileCandidateInconclusiveReason {
    NAME_UNAVAILABLE,
    MEDIASTORE_PENDING_UNAVAILABLE,
}
