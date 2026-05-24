package com.synckro.ui.screens.status

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.CloudProviderType
import com.synckro.ui.components.SectionCard
import com.synckro.ui.screens.home.HomeViewModel

/**
 * Phase 2 Status tab — redesigned from the Phase 1 placeholder into a
 * card-based dashboard. Each card surfaces a specific dimension of the
 * sync state (sync status / file progress, battery optimization warning,
 * recent changes, account info) and a dismissible warning banner is
 * pinned at the bottom for issues that need user attention.
 *
 * Data is sourced from [HomeViewModel.UiState] and aggregated via the
 * pure-Kotlin [buildStatusOverview] helper so the math is unit-testable.
 */
@Composable
fun StatusScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val overview =
        remember(
            state.pairs,
            state.syncingPairIds,
            state.progressByPairId,
            state.lastSummaryByPairId,
            state.accountEmailById,
            state.pendingConflictCount,
        ) {
            buildStatusOverview(
                pairs = state.pairs,
                syncingPairIds = state.syncingPairIds,
                progressByPairId = state.progressByPairId,
                lastSummaryByPairId = state.lastSummaryByPairId,
                accountEmailById = state.accountEmailById,
                pendingConflictCount = state.pendingConflictCount,
            )
        }

    val context = LocalContext.current
    val ignoringBatteryOptimizations = rememberBatteryOptimizationState(context)
    var warningDismissed by rememberSaveable { mutableStateOf(false) }
    val showBanner by remember(overview.warnings, warningDismissed) {
        derivedStateOf { overview.warnings.hasAny && !warningDismissed }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    // Leave room so the bottom banner never overlaps the last card.
                    bottom = if (showBanner) 160.dp else 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.status_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            item { SyncStatusCard(overview.syncStatus) }
            if (!ignoringBatteryOptimizations) {
                item { BatteryOptimizationCard(onAction = { openBatterySettings(context) }) }
            }
            item { RecentChangesCard(overview.recentChanges) }
            item { AccountsCard(overview.accountRows) }
        }

        if (showBanner) {
            WarningBanner(
                warnings = overview.warnings,
                onDismiss = { warningDismissed = true },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
            )
        }
    }
}

@Composable
private fun SyncStatusCard(status: StatusOverview.SyncStatus) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
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
                        modifier = Modifier.fillMaxWidth(),
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
private fun BatteryOptimizationCard(onAction: () -> Unit) {
    SectionCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        CardHeader(
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
private fun RecentChangesCard(changes: StatusOverview.RecentChanges) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
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
            // android.text.format.DateUtils.getRelativeTimeSpanString produces a
            // locale-aware "x minutes ago" label.
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
private fun AccountsCard(rows: List<StatusOverview.AccountRow>) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
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
private fun AccountRowEntry(row: StatusOverview.AccountRow) {
    val providerLabel =
        stringResource(
            when (row.provider) {
                CloudProviderType.ONEDRIVE -> R.string.provider_label_onedrive
                CloudProviderType.GOOGLE_DRIVE -> R.string.provider_label_google_drive
                // FAKE is only used in tests/offline-dev builds; fall back to the
                // OneDrive label so the UI still renders something sensible.
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
private fun WarningBanner(
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
private fun CardHeader(
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

/**
 * Tracks whether Synckro is currently exempted from Android battery
 * optimizations. The check is repeated on every `ON_RESUME` so coming
 * back from system settings reflects the new state without a manual
 * refresh.
 */
@Composable
private fun rememberBatteryOptimizationState(context: Context): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    ignoring = isIgnoringBatteryOptimizations(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return ignoring
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatterySettings(context: Context) {
    // Use the user-facing list rather than ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    // which Google Play restricts to apps that meet specific criteria. This action
    // opens the system-wide list of optimized apps; no package URI is honoured.
    val intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // Fallback to the generic battery-saver settings if the specific intent
        // isn't resolvable on this device.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
