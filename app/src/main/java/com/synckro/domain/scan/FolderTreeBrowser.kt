package com.synckro.domain.scan

import com.synckro.domain.model.CloudProviderType

/**
 * Endpoints of a sync pair that the folder tree is browsed against.
 *
 * @param localTreeUri SAF tree URI of the pair's local root; blank when the user
 *   has not picked a folder yet.
 * @param provider Cloud provider of the pair's remote root.
 * @param accountId Cloud account the pair is bound to; `null` disables the
 *   remote half of the mirror.
 * @param remoteFolderId Provider id of the pair's remote root; `null` or blank
 *   disables the remote half of the mirror.
 */
data class FolderTreeTarget(
    val localTreeUri: String,
    val provider: CloudProviderType,
    val accountId: String? = null,
    val remoteFolderId: String? = null,
)

/**
 * A single sub-folder of a sync pair, mirrored across both endpoints.
 *
 * @param relativePath Path of this folder relative to the pair's sync root,
 *   using `/` separators and without leading or trailing separators.
 * @param name Display name (the last segment of [relativePath]).
 * @param existsLocally True when the folder is present under the local root.
 * @param existsRemotely True when the folder is present under the remote root.
 */
data class FolderTreeNode(
    val relativePath: String,
    val name: String,
    val existsLocally: Boolean,
    val existsRemotely: Boolean,
)

/**
 * Lazily browses the folder tree of a sync pair, one directory level at a time,
 * so the pair editor can present a selective-sync tree without walking the whole
 * hierarchy up-front.
 *
 * Implementations are best-effort: an unreachable endpoint (revoked SAF grant,
 * offline provider, …) contributes no children rather than failing the whole
 * listing, so the reachable side of the mirror stays browsable.
 */
interface FolderTreeBrowser {
    /**
     * Lists the direct sub-folders of [relativePath] (empty string for the sync
     * root), merged across the local and remote endpoints of [target] and sorted
     * case-insensitively by name.
     */
    suspend fun listChildFolders(
        target: FolderTreeTarget,
        relativePath: String,
    ): List<FolderTreeNode>
}
