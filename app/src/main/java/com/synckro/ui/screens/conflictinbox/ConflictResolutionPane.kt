package com.synckro.ui.screens.conflictinbox

import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.synckro.R
import java.text.DateFormat
import java.util.Date

@Composable
fun ConflictResolutionPane(
    conflict: ConflictInboxViewModel.ConflictRow,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onKeepBoth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val imageLoader = remember(ctx) { ImageLoader(ctx) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val fileTypeLabel = stringResource(fileTypeLabelRes(conflict.fileType))
    val fileTypeIcon = fileTypeIcon(conflict.fileType)
    val resolved = conflict.resolution != null

    val localThumbnailUri =
        remember(conflict.localDocumentId, conflict.localTreeUri) {
            if (conflict.localDocumentId != null && conflict.localTreeUri != null) {
                runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(
                        Uri.parse(conflict.localTreeUri),
                        conflict.localDocumentId,
                    ).toString()
                }.getOrNull()
            } else {
                null
            }
        }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = conflict.relativePath,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            HorizontalDivider()

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
                        text = stringResource(
                            R.string.conflict_inbox_file_size_value,
                            conflict.localSizeBytes?.let { Formatter.formatShortFileSize(ctx, it) }
                                ?: stringResource(R.string.conflict_inbox_unknown_value),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(
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
                        text = stringResource(
                            R.string.conflict_inbox_file_size_value,
                            conflict.remoteSizeBytes?.let { Formatter.formatShortFileSize(ctx, it) }
                                ?: stringResource(R.string.conflict_inbox_unknown_value),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(
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
                        text = stringResource(
                            R.string.conflict_inbox_remote_account_value,
                            conflict.remoteAccountEmail ?: stringResource(R.string.conflict_inbox_unknown_value),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                text = stringResource(R.string.conflict_inbox_detected_at, fmt.format(Date(conflict.detectedAtMs))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

            if (!resolved) {
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
