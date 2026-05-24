package com.synckro.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synckro.R
import com.synckro.domain.sync.ActiveTransfer
import com.synckro.domain.sync.TransferDirection
import com.synckro.domain.sync.TransferProgress

@Composable
fun SyncProgressRows(
    progress: TransferProgress?,
    syncingLabel: String,
    modifier: Modifier = Modifier,
    showActiveTransfers: Boolean = true,
) {
    val fraction = primaryProgressFraction(progress)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (fraction != null && progress != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "$syncingLabel ${(fraction * 100f).toInt()}%"
                                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                                liveRegion = LiveRegionMode.Polite
                            },
                )
                Text(
                    text =
                        stringResource(
                            R.string.home_sync_progress_files_format,
                            progress.filesCompleted,
                            progress.totalFiles,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = syncingLabel
                            liveRegion = LiveRegionMode.Polite
                        },
            )
        }
        val activeTransfers = progress?.activeTransfers.orEmpty()
        if (showActiveTransfers && activeTransfers.isNotEmpty()) {
            activeTransfers.forEach { transfer ->
                ActiveTransferRow(transfer = transfer)
            }
        } else if (activeTransfers.isEmpty()) {
            // Fall back to the legacy single "currently syncing <file>" text when
            // the live per-file rows are unavailable. When [showActiveTransfers]
            // is false we intentionally render nothing here because the per-file
            // rows now live on the Status screen's Sync status card.
            progress?.currentFileName?.let { fileName ->
                Text(
                    text = stringResource(R.string.home_sync_current_file_format, fileName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.semantics {
                            contentDescription = "$syncingLabel: $fileName"
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
        }
    }
}

/**
 * Renders a single in-flight file transfer row: direction label + relative
 * path, bytes transferred / total, and a percentage on the right. Visible
 * inside both the Status screen's Sync status card (aggregated across every
 * syncing pair) and the per-pair [SyncProgressRows] composable.
 *
 * @param transfer The in-flight transfer to render. `totalBytes == 0L` is
 *   treated as "unknown size" and renders a 0% indicator.
 */
@Composable
fun ActiveTransferRow(
    transfer: ActiveTransfer,
) {
    val context = LocalContext.current
    val directionLabel =
        when (transfer.direction) {
            TransferDirection.UPLOAD -> stringResource(R.string.home_sync_transfer_upload)
            TransferDirection.DOWNLOAD -> stringResource(R.string.home_sync_transfer_download)
        }
    val transferFraction = transferProgressFraction(transfer)
    val sizeDone =
        Formatter.formatShortFileSize(
            context,
            transfer.bytesTransferred.coerceAtLeast(0L),
        )
    val sizeTotal =
        Formatter.formatShortFileSize(
            context,
            transfer.totalBytes.coerceAtLeast(0L),
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$directionLabel · ${transfer.relativePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.home_sync_transfer_size_format, sizeDone, sizeTotal),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text =
                stringResource(
                    R.string.home_sync_transfer_progress_percent,
                    ((transferFraction ?: 0f) * 100f).toInt(),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier =
                Modifier
                    .widthIn(min = 48.dp)
                    .semantics {
                        contentDescription = "$directionLabel ${transfer.relativePath}"
                        transferFraction?.let { fraction ->
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        }
                        liveRegion = LiveRegionMode.Polite
                    },
        )
    }
}

internal fun primaryProgressFraction(progress: TransferProgress?): Float? =
    when {
        progress != null && progress.totalBytes > 0L ->
            (progress.bytesTransferred.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
        progress != null && progress.totalFiles > 0 ->
            (progress.filesCompleted.toFloat() / progress.totalFiles).coerceIn(0f, 1f)
        else -> null
    }

internal fun transferProgressFraction(transfer: ActiveTransfer): Float? =
    if (transfer.totalBytes > 0L) {
        (transfer.bytesTransferred.toFloat() / transfer.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
