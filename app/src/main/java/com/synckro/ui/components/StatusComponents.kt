package com.synckro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.synckro.R
import com.synckro.domain.model.CloudProviderType
import com.synckro.ui.screens.status.StatusOverview

@Composable
fun SyncStatusCard(status: StatusOverview.SyncStatus) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        StatusCardHeader(
            icon = Icons.Default.Sync,
            title = stringResource(R.string.status_card_sync_status_title),
        )
        when {
            status.totalPairs == 0 ->
                Text(
                    text = stringResource(R.string.status_card_sync_status_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            status.isSyncing -> {
                Text(
                    text =
                        stringResource(
                            R.string.status_card_sync_status_running,
                            status.syncingPairs,
                            status.totalPairs,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val fraction = status.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .semantics {
                                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                                },
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (status.totalFiles > 0) {
                    Text(
                        text =
                            stringResource(
                                R.string.status_card_sync_status_files,
                                status.filesCompleted,
                                status.totalFiles,
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else ->
                Text(
                    text = stringResource(R.string.status_card_sync_status_idle),
                    style = MaterialTheme.typography.bodyMedium,
                )
        }
    }
}

@Composable
fun BatteryOptimizationCard(onAction: () -> Unit) {
    SectionCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        StatusCardHeader(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.status_card_battery_title),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = stringResource(R.string.status_card_battery_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onAction) {
            Text(stringResource(R.string.status_card_battery_action))
        }
    }
}

@Composable
fun RecentChangesCard(changes: StatusOverview.RecentChanges) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        StatusCardHeader(
            icon = Icons.Default.History,
            title = stringResource(R.string.status_card_recent_changes_title),
        )
        if (!changes.hasAnyHistory) {
            Text(
                text = stringResource(R.string.status_card_recent_changes_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        Text(
            text =
                stringResource(
                    R.string.status_card_recent_changes_summary,
                    changes.applied,
                    changes.pairsWithChanges,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (changes.conflicts > 0) {
            Text(
                text =
                    stringResource(
                        R.string.status_card_recent_changes_conflicts,
                        changes.conflicts,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (changes.errors > 0) {
            Text(
                text = stringResource(R.string.status_card_recent_changes_errors, changes.errors),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val ts = changes.lastTimestampMs
        if (ts != null) {
            val label =
                remember(ts) {
                    android.text.format.DateUtils
                        .getRelativeTimeSpanString(
                            ts,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS,
                        )
                        .toString()
                }
            Text(
                text = stringResource(R.string.status_card_recent_changes_last_run, label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AccountsCard(rows: List<StatusOverview.AccountRow>) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        StatusCardHeader(
            icon = Icons.Default.AccountCircle,
            title = stringResource(R.string.status_card_accounts_title),
        )
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.status_card_accounts_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        rows.forEach { row -> AccountRowEntry(row) }
    }
}

@Composable
fun WarningBanner(
    warnings: StatusOverview.Warnings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.status_warning_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.status_warning_banner_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (warnings.pendingConflicts > 0) {
            Text(
                text =
                    stringResource(
                        R.string.status_warning_banner_conflicts,
                        warnings.pendingConflicts,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (warnings.pairsNeedingReauth > 0) {
            Text(
                text =
                    stringResource(
                        R.string.status_warning_banner_reauth,
                        warnings.pairsNeedingReauth,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (warnings.pairsNeedingRelink > 0) {
            Text(
                text =
                    stringResource(
                        R.string.status_warning_banner_relink,
                        warnings.pairsNeedingRelink,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AccountRowEntry(row: StatusOverview.AccountRow) {
    val providerLabel =
        stringResource(
            when (row.provider) {
                CloudProviderType.ONEDRIVE -> R.string.provider_label_onedrive
                CloudProviderType.GOOGLE_DRIVE -> R.string.provider_label_google_drive
                CloudProviderType.FAKE -> R.string.provider_label_onedrive
            },
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = providerLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val subtitle = row.email ?: stringResource(R.string.status_card_accounts_unlinked)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (row.accountId == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        Text(
            text = stringResource(R.string.status_card_accounts_pair_count, row.pairCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCardHeader(
    icon: ImageVector,
    title: String,
    tint: Color = LocalContentColor.current,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
