package com.synckro.ui.screens.syncpreview

import com.synckro.domain.sync.SyncOp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure plan-grouping logic behind the sync dry-run preview screen.
 */
class SyncPreviewGroupingTest {
    @Test
    fun `groupOps buckets ops by user-facing kind and drops empty groups`() {
        val groups =
            SyncPreviewViewModel.groupOps(
                listOf(
                    SyncOp.UploadNew("a.txt"),
                    SyncOp.DownloadNew("b.txt"),
                    SyncOp.UploadNew("c.txt"),
                    SyncOp.DeleteLocal("d.txt"),
                ),
            )

        assertEquals(
            listOf(PlanGroupKind.UPLOAD, PlanGroupKind.DOWNLOAD, PlanGroupKind.DELETE_LOCAL),
            groups.map { it.kind },
        )
        assertEquals(listOf("a.txt", "c.txt"), groups.first().paths)
    }

    @Test
    fun `groupOps folds retention deletes into the matching delete bucket`() {
        val groups =
            SyncPreviewViewModel.groupOps(
                listOf(
                    SyncOp.DeleteLocal("old.txt"),
                    SyncOp.DeleteLocalRetention("archived.txt"),
                    SyncOp.DeleteRemoteRetention("remote-archived.txt"),
                ),
            )

        assertEquals(
            listOf(PlanGroupKind.DELETE_REMOTE, PlanGroupKind.DELETE_LOCAL),
            groups.map { it.kind },
        )
        assertEquals(listOf("old.txt", "archived.txt"), groups.last().paths)
    }

    @Test
    fun `groupOps renders moves as a from-to transition`() {
        val groups =
            SyncPreviewViewModel.groupOps(
                listOf(SyncOp.MoveLocal(fromRelativePath = "old/name.txt", relativePath = "new/name.txt")),
            )

        assertEquals(listOf(PlanGroupKind.MOVE), groups.map { it.kind })
        assertEquals(listOf("old/name.txt → new/name.txt"), groups.single().paths)
    }

    @Test
    fun `groupOps returns no groups for an empty plan`() {
        assertEquals(emptyList<SyncPreviewViewModel.PlanGroup>(), SyncPreviewViewModel.groupOps(emptyList()))
    }
}
