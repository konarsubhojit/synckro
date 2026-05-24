package com.synckro.ui.screens.status

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.ui.components.AccountsCard
import com.synckro.ui.components.BatteryOptimizationCard
import com.synckro.ui.components.RecentChangesCard
import com.synckro.ui.components.SyncStatusCard
import com.synckro.ui.components.WarningBanner
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .widthIn(max = 840.dp),
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
                        .widthIn(max = 840.dp)
                        .padding(16.dp),
            )
        }
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
