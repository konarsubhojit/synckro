package com.synckro.data.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.scan.FolderTreeBrowser
import com.synckro.domain.scan.FolderTreeNode
import com.synckro.domain.scan.FolderTreeTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [FolderTreeBrowser] that mirrors the SAF document tree of a pair's
 * local root against the folder listing of its remote root.
 *
 * Each endpoint is resolved independently and failures are contained: when the
 * local grant was revoked or the provider call fails, that side simply
 * contributes no children so the user can still browse the other side.
 */
@Singleton
class FolderTreeBrowserImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val providerFactories: Map<CloudProviderType, @JvmSuppressWildcards CloudProviderFactory>,
    ) : FolderTreeBrowser {
        override suspend fun listChildFolders(
            target: FolderTreeTarget,
            relativePath: String,
        ): List<FolderTreeNode> {
            val segments = pathSegments(relativePath)
            val localNames = withContext(Dispatchers.IO) { localChildFolders(target.localTreeUri, segments) }
            val remoteNames = remoteChildFolders(target, segments)
            return mergeChildFolders(segments.joinToString("/"), localNames, remoteNames)
        }

        private fun localChildFolders(
            localTreeUri: String,
            segments: List<String>,
        ): Set<String> {
            if (localTreeUri.isBlank()) return emptySet()
            return try {
                var dir = DocumentFile.fromTreeUri(context, Uri.parse(localTreeUri)) ?: return emptySet()
                for (segment in segments) {
                    dir = dir.listFiles().firstOrNull { it.isDirectory && it.name == segment } ?: return emptySet()
                }
                dir
                    .listFiles()
                    .filter { it.isDirectory }
                    .mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                    .toSet()
            } catch (e: Exception) {
                Timber.w(e, "FolderTreeBrowser: local listing failed for uri=%s", localTreeUri)
                emptySet()
            }
        }

        private suspend fun remoteChildFolders(
            target: FolderTreeTarget,
            segments: List<String>,
        ): Set<String> {
            val accountId = target.accountId?.takeIf { it.isNotBlank() } ?: return emptySet()
            val factory = providerFactories[target.provider] ?: return emptySet()
            var folderId = target.remoteFolderId?.takeIf { it.isNotBlank() } ?: return emptySet()
            return try {
                val provider = factory.providerFor(accountId)
                for (segment in segments) {
                    folderId =
                        provider
                            .list(folderId)
                            .firstOrNull { it.isFolder && it.name == segment }
                            ?.id ?: return emptySet()
                }
                provider
                    .list(folderId)
                    .filter { it.isFolder }
                    .map { it.name.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Timber.w(e, "FolderTreeBrowser: remote listing failed for accountId=%s", accountId)
                emptySet()
            }
        }
    }

/**
 * Splits [relativePath] into its non-empty path segments, tolerating leading,
 * trailing and repeated `/` separators.
 */
internal fun pathSegments(relativePath: String): List<String> = relativePath.split('/').filter { it.isNotBlank() }

/**
 * Merges the local and remote child-folder names of [parentRelativePath] into a
 * single case-insensitively sorted list, flagging on which endpoint(s) each
 * folder exists. Folder names are matched case-sensitively because both SAF and
 * the supported providers treat them as distinct.
 */
internal fun mergeChildFolders(
    parentRelativePath: String,
    localNames: Set<String>,
    remoteNames: Set<String>,
): List<FolderTreeNode> =
    (localNames + remoteNames)
        .sortedBy { it.lowercase() }
        .map { name ->
            FolderTreeNode(
                relativePath = if (parentRelativePath.isEmpty()) name else "$parentRelativePath/$name",
                name = name,
                existsLocally = name in localNames,
                existsRemotely = name in remoteNames,
            )
        }
