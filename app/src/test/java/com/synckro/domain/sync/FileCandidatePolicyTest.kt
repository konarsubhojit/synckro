package com.synckro.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCandidatePolicyTest {
    @Test
    fun `temporary and hidden names are excluded case-insensitively`() {
        val excludedNames =
            listOf(
                "download.part",
                "download.PART",
                "archive.crdownload",
                "archive.CRDOWNLOAD",
                "scratch.tmp",
                "scratch.TMP",
                ".pending-photo.jpg",
                ".PENDING-photo.jpg",
                "~\$report.xlsx",
                ".hidden",
                "nested/.hidden",
            )

        excludedNames.forEach { path ->
            assertExcluded(path)
        }
    }

    @Test
    fun `temporary markers must match a complete prefix or suffix`() {
        listOf(
            "partial",
            "file.part.txt",
            "file.tmp2",
            "pending-photo.jpg",
            "report~\$draft.xlsx",
        ).forEach { path ->
            assertEquals(path, FileCandidateDecision.Eligible, FileCandidatePolicy.evaluate(path))
        }
    }

    @Test
    fun `renamed final name is evaluated independently`() {
        assertExcluded("camera/photo.jpg.part")
        assertEquals(
            FileCandidateDecision.Eligible,
            FileCandidatePolicy.evaluate("camera/photo.jpg"),
        )
    }

    @Test
    fun `MediaStore pending state fails closed`() {
        assertEquals(
            FileCandidateDecision.Excluded(FileCandidateExclusionReason.MEDIASTORE_PENDING),
            FileCandidatePolicy.evaluate("photo.jpg", MediaStorePendingState.PENDING),
        )
        assertEquals(
            FileCandidateDecision.Inconclusive(
                FileCandidateInconclusiveReason.MEDIASTORE_PENDING_UNAVAILABLE,
            ),
            FileCandidatePolicy.evaluate("photo.jpg", MediaStorePendingState.UNAVAILABLE),
        )
        assertEquals(
            FileCandidateDecision.Eligible,
            FileCandidatePolicy.evaluate("photo.jpg", MediaStorePendingState.NOT_PENDING),
        )
        assertEquals(
            FileCandidateDecision.Eligible,
            FileCandidatePolicy.evaluate("photo.jpg", MediaStorePendingState.NOT_APPLICABLE),
        )
    }

    @Test
    fun `unknown name is inconclusive`() {
        assertEquals(
            FileCandidateDecision.Inconclusive(FileCandidateInconclusiveReason.NAME_UNAVAILABLE),
            FileCandidatePolicy.evaluate(null),
        )
        assertEquals(
            FileCandidateDecision.Inconclusive(FileCandidateInconclusiveReason.NAME_UNAVAILABLE),
            FileCandidatePolicy.evaluate("folder/"),
        )
    }

    private fun assertExcluded(relativePath: String) {
        val decision = FileCandidatePolicy.evaluate(relativePath)
        assertTrue(
            "Expected '$relativePath' to be excluded, but was $decision",
            decision is FileCandidateDecision.Excluded,
        )
    }
}
