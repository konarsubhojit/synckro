package com.synckro.ui.screens.conflictinbox

import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.synckro.R
import com.synckro.domain.model.ConflictRecord
import com.synckro.ui.components.EmptyState
import com.synckro.ui.components.LoadingState
import java.text.DateFormat
import java.util.Date

/**
 * Displays the list of unresolved sync conflicts. Each conflict card explains
 * what changed on both sides and offers three resolution actions with clear
 * descriptions:
 *
 * - **Keep local** (↑ Upload) — the local version wins; overwrites cloud copy on next sync.
 * - **Keep remote** (↓ Download) — the cloud version wins; overwrites local copy on next sync.
 * - **Keep both** (⎘ Copy) — both versions are preserved with a conflict-copy name.
 *
 * Resolved conflicts are automatically removed after the next sync run.
 *
 * Long-pressing a conflict row enters **selection mode**. While in selection mode
 * the top app bar swaps to a contextual bar showing the selection count and bulk
 * resolution actions (Keep local / Keep remote / Keep both / Cancel).  A system
 * back gesture exits selection mode without popping the screen.
 *
 * @param onBack Called when the user presses the back / up button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictInboxScreen(
    onBack: (() -> Unit)? = null,
    viewModel: ConflictInboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Intercept system back while in selection mode to exit without popping the screen.
    BackHandler(enabled = state.isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    Scaffold(
        topBar = {
            if (state.isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                R.string.conflict_inbox_selection_count_format,
                                state.selectedCount,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitSelectionMode() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.conflict_inbox_cancel_selection),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.bulkKeepLocal() },
                            enabled = state.selectedCount > 0,
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = stringResource(R.string.conflict_inbox_keep_local),
                            )
                        }
                        IconButton(
                            onClick = { viewModel.bulkKeepRemote() },
                            enabled = state.selectedCount > 0,
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = stringResource(R.string.conflict_inbox_keep_remote),
                            )
                        }
                        IconButton(
                            onClick = { viewModel.bulkKeepBoth() },
                            enabled = state.selectedCount > 0,
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.conflict_inbox_keep_both),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.conflict_inbox_title)) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.nav_back),
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        when {
            state.isLoading -> {
                LoadingState(
                    message = stringResource(R.string.loading_conflicts),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            state.conflicts.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.conflict_inbox_empty_title),
                    body = stringResource(R.string.conflict_inbox_empty_body),
                    icon = Icons.Filled.Inbox,
                    primaryActionLabel = onBack?.let { stringResource(R.string.conflict_inbox_empty_cta) },
                    onPrimaryAction = onBack,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            else -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!state.isSelectionMode) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            // Explain what a conflict is and how to resolve it
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.conflict_inbox_explainer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                    items(state.conflicts, key = { it.id }) { conflict ->
                        val isSelected = conflict.id in state.selectedIds
                        ConflictCard(
                            conflict = conflict,
                            isSelectionMode = state.isSelectionMode,
                            isSelected = isSelected,
                            onKeepLocal = { viewModel.keepLocal(conflict.id) },
                            onKeepRemote = { viewModel.keepRemote(conflict.id) },
                            onKeepBoth = { viewModel.keepBoth(conflict.id) },
                            onLongPress = { viewModel.enterSelectionMode(conflict.id) },
                            onToggleSelection = { viewModel.toggleSelection(conflict.id) },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConflictCard(
    conflict: ConflictInboxViewModel.ConflictRow,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onKeepBoth: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelection: () -> Unit,
    imageLoader: ImageLoader = ImageLoader(LocalContext.current),
) {
    val ctx = LocalContext.current
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val resolved = conflict.resolution != null
    val fileTypeLabel = stringResource(fileTypeLabelRes(conflict.fileType))
    val fileTypeIcon = fileTypeIcon(conflict.fileType)

    val selectedDescription = stringResource(R.string.conflict_inbox_selected)
    val notSelectedDescription = stringResource(R.string.conflict_inbox_not_selected)

    // Build the local thumbnail URI from the SAF tree URI + document ID.
    // This is done in the UI layer so the ViewModel stays platform-agnostic.
    val localThumbnailUri =
        remember(conflict.localDocumentId, conflict.localTreeUri) {
            if (conflict.localDocumentId != null && conflict.localTreeUri != null) {
                runCatching {
                    DocumentsContract
                        .buildDocumentUriUsingTree(
                            Uri.parse(conflict.localTreeUri),
                            conflict.localDocumentId,
                        ).toString()
                }.getOrNull()
            } else {
                null
            }
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    if (isSelectionMode) {
                        stateDescription = if (isSelected) selectedDescription else notSelectedDescription
                    }
                }
                .combinedClickable(
                    onClick = {
                        if (isSelectionMode) onToggleSelection()
                    },
                    onLongClick = {
                        if (!isSelectionMode) onLongPress()
                    },
                ),
        colors =
            when {
                isSelected -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                resolved -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                else -> CardDefaults.cardColors()
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // File path — show a selection check icon when in selection mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    )
                } else if (localThumbnailUri != null) {
                    ConflictThumbnail(
                        thumbnailUri = localThumbnailUri,
                        fallbackIcon = fileTypeIcon,
                        imageLoader = imageLoader,
                        contentDescription = null,
                    )
                } else {
                    Icon(
                        imageVector = fileTypeIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = conflict.relativePath,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            // File metadata for both sides, with thumbnails for image conflicts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.conflict_inbox_local_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (localThumbnailUri != null) {
                        ConflictThumbnail(
                            thumbnailUri = localThumbnailUri,
                            fallbackIcon = fileTypeIcon,
                            imageLoader = imageLoader,
                            contentDescription = stringResource(R.string.conflict_inbox_local_label),
                        )
                    }
                    Text(
                        text = stringResource(R.string.conflict_inbox_file_type_value, fileTypeLabel),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.conflict_inbox_file_size_value,
                                conflict.localSizeBytes?.let { Formatter.formatShortFileSize(ctx, it) }
                                    ?: stringResource(R.string.conflict_inbox_unknown_value),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.conflict_inbox_file_modified_value,
                                DateUtils.getRelativeTimeSpanString(
                                    conflict.localLastModifiedMs,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                ),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.conflict_inbox_remote_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (conflict.remoteThumbnailUrl != null) {
                        ConflictThumbnail(
                            thumbnailUri = conflict.remoteThumbnailUrl,
                            fallbackIcon = fileTypeIcon,
                            imageLoader = imageLoader,
                            contentDescription = stringResource(R.string.conflict_inbox_remote_label),
                        )
                    }
                    Text(
                        text = stringResource(R.string.conflict_inbox_file_type_value, fileTypeLabel),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.conflict_inbox_file_size_value,
                                conflict.remoteSizeBytes?.let { Formatter.formatShortFileSize(ctx, it) }
                                    ?: stringResource(R.string.conflict_inbox_unknown_value),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.conflict_inbox_file_modified_value,
                                DateUtils.getRelativeTimeSpanString(
                                    conflict.remoteLastModifiedMs,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                ),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.conflict_inbox_remote_account_value,
                                conflict.remoteAccountEmail ?: stringResource(R.string.conflict_inbox_unknown_value),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.conflict_inbox_detected_at,
                    fmt.format(Date(conflict.detectedAtMs)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Queued resolution badge
            if (conflict.resolution != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(
                            R.string.conflict_inbox_pending_resolution,
                            resolutionLabel(conflict.resolution),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Resolution action buttons with icons and descriptions — hidden in selection mode
            if (!resolved && !isSelectionMode) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConflictActionButton(
                        icon = Icons.Default.CloudUpload,
                        label = stringResource(R.string.conflict_inbox_keep_local),
                        description = stringResource(R.string.conflict_inbox_keep_local_hint),
                        onClick = onKeepLocal,
                        isPrimary = false,
                    )
                    ConflictActionButton(
                        icon = Icons.Default.CloudDownload,
                        label = stringResource(R.string.conflict_inbox_keep_remote),
                        description = stringResource(R.string.conflict_inbox_keep_remote_hint),
                        onClick = onKeepRemote,
                        isPrimary = false,
                    )
                    ConflictActionButton(
                        icon = Icons.Default.ContentCopy,
                        label = stringResource(R.string.conflict_inbox_keep_both),
                        description = stringResource(R.string.conflict_inbox_keep_both_hint),
                        onClick = onKeepBoth,
                        isPrimary = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictActionButton(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
) {
    val buttonContent: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPrimary) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (isPrimary) {
        Button(onClick = onClick, modifier = modifier.fillMaxWidth()) {
            buttonContent()
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
            buttonContent()
        }
    }
}

@Composable
private fun resolutionLabel(resolution: String): String =
    when (resolution) {
        ConflictRecord.RESOLUTION_KEEP_LOCAL -> stringResource(R.string.conflict_inbox_keep_local)
        ConflictRecord.RESOLUTION_KEEP_REMOTE -> stringResource(R.string.conflict_inbox_keep_remote)
        ConflictRecord.RESOLUTION_KEEP_BOTH -> stringResource(R.string.conflict_inbox_keep_both)
        else -> resolution
    }

private fun fileTypeLabelRes(fileTypeIcon: ConflictInboxViewModel.FileTypeIcon): Int =
    when (fileTypeIcon) {
        ConflictInboxViewModel.FileTypeIcon.FOLDER -> R.string.conflict_inbox_file_type_folder
        ConflictInboxViewModel.FileTypeIcon.IMAGE -> R.string.conflict_inbox_file_type_image
        ConflictInboxViewModel.FileTypeIcon.DOCUMENT -> R.string.conflict_inbox_file_type_document
        ConflictInboxViewModel.FileTypeIcon.GENERIC -> R.string.conflict_inbox_file_type_generic
    }

private fun fileTypeIcon(fileTypeIcon: ConflictInboxViewModel.FileTypeIcon): ImageVector =
    when (fileTypeIcon) {
        ConflictInboxViewModel.FileTypeIcon.FOLDER -> Icons.Default.Folder
        ConflictInboxViewModel.FileTypeIcon.IMAGE -> Icons.Default.Image
        ConflictInboxViewModel.FileTypeIcon.DOCUMENT -> Icons.Default.Description
        ConflictInboxViewModel.FileTypeIcon.GENERIC -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

/**
 * Displays a 48×48dp thumbnail for an image conflict side.
 *
 * Attempts to load [thumbnailUri] via Coil. On any load failure (network error,
 * permission denied, unsupported format) the composable silently falls back to
 * the material [fallbackIcon] so the card never shows an empty space.
 *
 * @param thumbnailUri   `content://` SAF URI or HTTP(S) URL to load.
 * @param fallbackIcon   Icon shown while loading or on failure (the file-type icon).
 * @param imageLoader    Coil [ImageLoader] to use; defaults to the app-singleton loader.
 *                       Pass a stub loader in Compose previews and screenshot tests.
 * @param contentDescription Accessibility description for the image; null if decorative.
 */
@Composable
private fun ConflictThumbnail(
    thumbnailUri: String,
    fallbackIcon: ImageVector,
    imageLoader: ImageLoader,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val size = 48.dp
    val request =
        ImageRequest
            .Builder(ctx)
            .data(thumbnailUri)
            .crossfade(true)
            .size(coil.size.Size(128, 128))
            .build()

    // Track whether the most recent load attempt failed so we can show the
    // fallback icon without leaving an empty/blank space in the card.
    val loadFailed = remember { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
            onState = { state ->
                loadFailed.value = state is AsyncImagePainter.State.Error
            },
        )
        // Show the fallback icon when the image is unavailable or fails to load.
        if (loadFailed.value) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/**
 * Compose preview for a conflict card that has image thumbnails.
 *
 * Uses [coil.test.FakeImageLoader] would be ideal, but since coil-test is not
 * on the classpath we use a plain [ImageLoader] that always fails to load
 * (because there is no real URI in a preview context). The fallback icon is
 * shown in that case, which is exactly the behaviour we want to verify.
 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ConflictCardImagePreview() {
    val ctx = LocalContext.current
    val stubImageLoader =
        ImageLoader
            .Builder(ctx)
            .memoryCache {
                MemoryCache
                    .Builder(ctx)
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()

    val imageConflict =
        ConflictInboxViewModel.ConflictRow(
            id = 1L,
            pairId = 1L,
            relativePath = "Photos/vacation.jpg",
            localLastModifiedMs = System.currentTimeMillis() - 3_600_000L,
            remoteLastModifiedMs = System.currentTimeMillis() - 7_200_000L,
            localSizeBytes = 2_048_576L,
            remoteSizeBytes = 3_145_728L,
            detectedAtMs = System.currentTimeMillis() - 1_800_000L,
            resolution = null,
            remoteAccountEmail = "user@example.com",
            fileType = ConflictInboxViewModel.FileTypeIcon.IMAGE,
            localDocumentId = "primary:Photos/vacation.jpg",
            localTreeUri = "content://com.android.externalstorage.documents/tree/primary%3APhotos",
            remoteThumbnailUrl = "https://example.com/thumbnail.jpg",
        )

    MaterialTheme {
        ConflictCard(
            conflict = imageConflict,
            isSelectionMode = false,
            isSelected = false,
            onKeepLocal = {},
            onKeepRemote = {},
            onKeepBoth = {},
            onLongPress = {},
            onToggleSelection = {},
            imageLoader = stubImageLoader,
        )
    }
}
