package com.synckro.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTreeBrowserImplTest {
    @Test
    fun `pathSegments ignores leading trailing and repeated separators`() {
        assertEquals(listOf("Camera", "2024"), pathSegments("/Camera//2024/"))
        assertEquals(emptyList<String>(), pathSegments(""))
    }

    @Test
    fun `mergeChildFolders unions both endpoints and flags where each folder exists`() {
        val merged =
            mergeChildFolders(
                parentRelativePath = "Camera",
                localNames = setOf("2024", "Screenshots"),
                remoteNames = setOf("2024", "Backups"),
            )

        assertEquals(listOf("Camera/2024", "Camera/Backups", "Camera/Screenshots"), merged.map { it.relativePath })
        assertEquals(listOf("2024", "Backups", "Screenshots"), merged.map { it.name })
        assertEquals(listOf(true, false, true), merged.map { it.existsLocally })
        assertEquals(listOf(true, true, false), merged.map { it.existsRemotely })
    }

    @Test
    fun `mergeChildFolders sorts case-insensitively and keys root children by name`() {
        val merged =
            mergeChildFolders(
                parentRelativePath = "",
                localNames = setOf("beta", "Alpha"),
                remoteNames = emptySet(),
            )

        assertEquals(listOf("Alpha", "beta"), merged.map { it.relativePath })
    }
}
