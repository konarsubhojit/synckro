package com.synckro.ui.screens.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.data.repository.AppLanguagePreference
import com.synckro.data.repository.AutoSyncSchedule
import com.synckro.data.repository.DarkModePreference
import com.synckro.data.repository.InternetConnectionScope
import com.synckro.data.repository.LogRetentionPreference
import com.synckro.data.repository.SettingsRepository
import com.synckro.domain.model.ConflictPolicy
import kotlinx.coroutines.launch
import java.util.Locale

// ---- Internal navigation constants ----
private const val SECTION_SYNC = "sync"
private const val SECTION_APPEARANCE = "appearance"
private const val SECTION_NOTIFICATIONS = "notifications"
private const val SECTION_BATTERY = "battery"
private const val SECTION_SECURITY = "security"
private const val SECTION_BACKUP = "backup"
private const val SECTION_ABOUT = "about"
private const val SECTION_SUPPORT = "support"

/**
 * Top-level Settings screen. Shows a Material3 navigable menu that groups all
 * user preferences into themed sections (Account / Preferences / Privacy &amp;
 * data / App). Tapping a section row swaps the content inline to the
 * corresponding sub-screen; the [TopAppBar] back arrow returns to the root menu.
 *
 * Side-effects (log export, cache clear, channel deep-link, OSS licenses
 * dialog) are orchestrated at this level via the one-shot
 * [SettingsViewModel.events] flow so the ViewModel stays free of Android
 * Context-coupled UI concerns.
 *
 * @param onBack Called when the user navigates out of the root Settings menu
 *   (e.g. host scaffold close). `null` hides the back arrow at root level.
 * @param onNavigateToAccounts Called when the user taps the Accounts menu
 *   entry. Pass `null` to disable the row (e.g. Accounts is not reachable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onNavigateToAccounts: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var section by remember { mutableStateOf<String?>(null) }
    var conflictPolicyDialog by remember { mutableStateOf(false) }
    var ossDialog by remember { mutableStateOf(false) }

    // One-shot events drive intents/snackbars. Direct suspend calls inside
    // `collect` are safe because the LaunchedEffect coroutine is already
    // suspending when awaiting events.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsViewModel.UiEvent.ShareLogs -> startShareLogs(ctx, event.uri)
                is SettingsViewModel.UiEvent.ComposeFeedback -> {
                    val launched =
                        startSendFeedback(
                            ctx = ctx,
                            uri = event.uri,
                            feedbackEmail = viewModel.feedbackEmailConfig.address,
                            versionName = viewModel.versionName,
                        )
                    if (!launched) {
                        snackbarHostState.showSnackbar(
                            ctx.getString(R.string.settings_send_feedback_no_app),
                        )
                    }
                }
                is SettingsViewModel.UiEvent.ExportFailed ->
                    snackbarHostState.showSnackbar(
                        ctx.getString(R.string.settings_export_logs_failed, event.message),
                    )
                is SettingsViewModel.UiEvent.CacheCleared ->
                    snackbarHostState.showSnackbar(
                        ctx.getString(
                            R.string.settings_clear_cache_done,
                            Formatter.formatShortFileSize(ctx, event.freedBytes),
                        ),
                    )
            }
        }
    }

    // Back navigation: sub-screens return to the root menu; root menu uses the
    // caller-supplied onBack (or hides the arrow when onBack is null).
    val onNavigateBack: (() -> Unit)? =
        if (section != null) {
            { section = null }
        } else {
            onBack
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title =
                        when (section) {
                            SECTION_SYNC -> stringResource(R.string.settings_section_sync_defaults)
                            SECTION_APPEARANCE ->
                                stringResource(R.string.settings_section_appearance)
                            SECTION_NOTIFICATIONS ->
                                stringResource(R.string.settings_section_notifications)
                            SECTION_BATTERY ->
                                stringResource(R.string.settings_section_battery)
                            SECTION_SECURITY ->
                                stringResource(R.string.settings_section_security)
                            SECTION_BACKUP ->
                                stringResource(R.string.settings_section_backup)
                            SECTION_ABOUT -> stringResource(R.string.settings_section_about)
                            SECTION_SUPPORT ->
                                stringResource(R.string.settings_section_support)
                            else -> stringResource(R.string.settings_title)
                        }
                    Text(title)
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.nav_back),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentPadding =
            PaddingValues(
                start = 0.dp,
                end = 0.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            )
        when (section) {
            null ->
                SettingsMenu(
                    contentPadding = contentPadding,
                    onNavigateToAccounts = onNavigateToAccounts,
                    onNavigateToSection = { section = it },
                )
            SECTION_SYNC ->
                SyncSettingsContent(
                    state = state,
                    viewModel = viewModel,
                    ctx = ctx,
                    contentPadding = contentPadding,
                    onShowConflictPolicyDialog = { conflictPolicyDialog = true },
                )
            SECTION_APPEARANCE ->
                AppearanceSettingsContent(
                    state = state,
                    viewModel = viewModel,
                    contentPadding = contentPadding,
                )
            SECTION_NOTIFICATIONS ->
                NotificationsSettingsContent(
                    state = state,
                    viewModel = viewModel,
                    ctx = ctx,
                    contentPadding = contentPadding,
                )
            SECTION_BATTERY ->
                BatterySettingsContent(
                    state = state,
                    viewModel = viewModel,
                    contentPadding = contentPadding,
                )
            SECTION_SECURITY ->
                SecuritySettingsContent(
                    state = state,
                    viewModel = viewModel,
                    contentPadding = contentPadding,
                    snackbarHostState = snackbarHostState,
                )
            SECTION_BACKUP ->
                BackupSettingsContent(
                    state = state,
                    viewModel = viewModel,
                    contentPadding = contentPadding,
                )
            SECTION_ABOUT ->
                AboutSettingsContent(
                    viewModel = viewModel,
                    ctx = ctx,
                    contentPadding = contentPadding,
                    onShowOssDialog = { ossDialog = true },
                )
            SECTION_SUPPORT ->
                SupportSettingsContent(
                    viewModel = viewModel,
                    ctx = ctx,
                    contentPadding = contentPadding,
                )
        }
    }

    if (conflictPolicyDialog) {
        ConflictPolicyPickerDialog(
            selected = state.defaultConflictPolicy,
            onPick = {
                viewModel.setDefaultConflictPolicy(it)
                conflictPolicyDialog = false
            },
            onDismiss = { conflictPolicyDialog = false },
        )
    }

    if (ossDialog) {
        OssLicensesDialog(
            loadText = { viewModel.loadOssLicensesText() },
            onDismiss = { ossDialog = false },
        )
    }
}

// =============================================================================
// Root menu
// =============================================================================

/**
 * Root settings menu: a scrollable list of category rows grouped by labelled
 * sections. Each row shows a leading icon, a title, a subtitle, and a trailing
 * arrow indicating navigation to the corresponding sub-screen.
 */
@Composable
private fun SettingsMenu(
    contentPadding: PaddingValues,
    onNavigateToAccounts: (() -> Unit)?,
    onNavigateToSection: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        // ---------- Account ----------
        item { MenuSectionHeader(stringResource(R.string.settings_section_account)) }
        item {
            MenuCard {
                SettingsMenuEntry(
                    icon = Icons.Filled.AccountCircle,
                    title = stringResource(R.string.settings_menu_accounts_title),
                    subtitle = stringResource(R.string.settings_menu_accounts_subtitle),
                    onClick = onNavigateToAccounts ?: {},
                )
            }
        }

        // ---------- Preferences ----------
        item { MenuSectionHeader(stringResource(R.string.settings_section_preferences)) }
        item {
            MenuCard {
                SettingsMenuEntry(
                    icon = Icons.Filled.Sync,
                    title = stringResource(R.string.settings_menu_sync_title),
                    subtitle = stringResource(R.string.settings_menu_sync_subtitle),
                    onClick = { onNavigateToSection(SECTION_SYNC) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsMenuEntry(
                    icon = Icons.Filled.Language,
                    title = stringResource(R.string.settings_menu_appearance_title),
                    subtitle = stringResource(R.string.settings_menu_appearance_subtitle),
                    onClick = { onNavigateToSection(SECTION_APPEARANCE) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsMenuEntry(
                    icon = Icons.Filled.Notifications,
                    title = stringResource(R.string.settings_menu_notifications_title),
                    subtitle = stringResource(R.string.settings_menu_notifications_subtitle),
                    onClick = { onNavigateToSection(SECTION_NOTIFICATIONS) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsMenuEntry(
                    icon = Icons.Filled.BatteryFull,
                    title = stringResource(R.string.settings_menu_battery_title),
                    subtitle = stringResource(R.string.settings_menu_battery_subtitle),
                    onClick = { onNavigateToSection(SECTION_BATTERY) },
                )
            }
        }

        // ---------- Privacy & data ----------
        item { MenuSectionHeader(stringResource(R.string.settings_section_privacy)) }
        item {
            MenuCard {
                SettingsMenuEntry(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.settings_menu_security_title),
                    subtitle = stringResource(R.string.settings_menu_security_subtitle),
                    onClick = { onNavigateToSection(SECTION_SECURITY) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsMenuEntry(
                    icon = Icons.Filled.Backup,
                    title = stringResource(R.string.settings_menu_backup_title),
                    subtitle = stringResource(R.string.settings_menu_backup_subtitle),
                    onClick = { onNavigateToSection(SECTION_BACKUP) },
                )
            }
        }

        // ---------- App ----------
        item { MenuSectionHeader(stringResource(R.string.settings_section_app)) }
        item {
            MenuCard {
                SettingsMenuEntry(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.settings_menu_about_title),
                    subtitle = stringResource(R.string.settings_menu_about_subtitle),
                    onClick = { onNavigateToSection(SECTION_ABOUT) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsMenuEntry(
                    icon = Icons.AutoMirrored.Filled.Help,
                    title = stringResource(R.string.settings_menu_support_title),
                    subtitle = stringResource(R.string.settings_menu_support_subtitle),
                    onClick = { onNavigateToSection(SECTION_SUPPORT) },
                )
            }
        }
    }
}

@Composable
private fun MenuSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsMenuEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// =============================================================================
// Sub-screen content composables
// =============================================================================

@Composable
private fun SyncSettingsContent(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel,
    ctx: Context,
    contentPadding: PaddingValues,
    onShowConflictPolicyDialog: () -> Unit,
) {
    var showUploadLimitDialog by remember { mutableStateOf(false) }
    var showDownloadLimitDialog by remember { mutableStateOf(false) }
    var showRetryWaitDialog by remember { mutableStateOf(false) }
    var showRetryAttemptsDialog by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showConnectionScopeDialog by remember { mutableStateOf(false) }
    var editingAllowedNetworks by remember { mutableStateOf(false) }
    var editingDisallowedNetworks by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item { SettingsGroupHeader(stringResource(R.string.settings_sync_group_network_limits)) }
        item {
            ActionRow(
                title = stringResource(R.string.settings_mobile_upload_limit_title),
                body = formatMobileLimit(ctx, state.mobileUploadLimitMb),
                onClick = { showUploadLimitDialog = true },
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_mobile_download_limit_title),
                body = formatMobileLimit(ctx, state.mobileDownloadLimitMb),
                onClick = { showDownloadLimitDialog = true },
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_warn_mobile_network_title),
                body = stringResource(R.string.settings_warn_mobile_network_body),
                checked = state.warnOnMobileNetworkSync,
                onCheckedChange = viewModel::setWarnOnMobileNetworkSync,
            )
        }
        item { SettingsGroupHeader(stringResource(R.string.settings_sync_group_after_errors)) }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_retry_automatically_title),
                body = stringResource(R.string.settings_retry_automatically_body),
                checked = state.retryAutomaticallyAfterError,
                onCheckedChange = viewModel::setRetryAutomaticallyAfterError,
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_retry_wait_title),
                body =
                    stringResource(
                        R.string.settings_retry_wait_value,
                        state.retryWaitMinutes,
                    ),
                onClick = { showRetryWaitDialog = true },
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_retry_max_attempts_title),
                body =
                    stringResource(
                        R.string.settings_retry_max_attempts_value,
                        state.retryMaxAttempts,
                    ),
                onClick = { showRetryAttemptsDialog = true },
            )
        }
        item { SettingsGroupHeader(stringResource(R.string.settings_sync_group_parallel)) }
        item {
            SliderRow(
                title = stringResource(R.string.settings_parallel_uploads_title),
                body = stringResource(R.string.settings_parallel_uploads_body),
                value = state.parallelUploads,
                max = SettingsRepository.MAX_PARALLEL_TRANSFERS_PER_DIRECTION,
                onValueChange = viewModel::setParallelUploads,
            )
        }
        item {
            SliderRow(
                title = stringResource(R.string.settings_parallel_downloads_title),
                body = stringResource(R.string.settings_parallel_downloads_body),
                value = state.parallelDownloads,
                max = SettingsRepository.MAX_PARALLEL_TRANSFERS_PER_DIRECTION,
                onValueChange = viewModel::setParallelDownloads,
            )
        }
        item { SettingsGroupHeader(stringResource(R.string.settings_sync_group_background)) }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_global_auto_sync_title),
                body = stringResource(R.string.settings_global_auto_sync_body),
                checked = state.globalAutoSyncEnabled,
                onCheckedChange = viewModel::setGlobalAutoSync,
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_auto_sync_schedule_title),
                body = autoSyncScheduleLabel(state.autoSyncSchedule),
                onClick = { showScheduleDialog = true },
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_auto_sync_charging_only_title),
                body = stringResource(R.string.settings_auto_sync_charging_only_body),
                checked = state.autoSyncChargingOnly,
                onCheckedChange = viewModel::setAutoSyncChargingOnly,
            )
        }
        item {
            SliderRow(
                title = stringResource(R.string.settings_auto_sync_battery_threshold_title),
                body =
                    stringResource(
                        R.string.settings_auto_sync_battery_threshold_value,
                        state.autoSyncBatteryThresholdPercent,
                    ),
                value = state.autoSyncBatteryThresholdPercent,
                max = 100,
                min = 0,
                onValueChange = viewModel::setAutoSyncBatteryThresholdPercent,
            )
        }
        item {
            WebsiteInfoRow(
                text = stringResource(R.string.settings_auto_sync_battery_info),
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.batteryInfoUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
        item { SettingsGroupHeader(stringResource(R.string.settings_sync_group_internet)) }
        item {
            ActionRow(
                title = stringResource(R.string.settings_internet_scope_title),
                body = internetConnectionScopeLabel(state.internetConnectionScope),
                onClick = { showConnectionScopeDialog = true },
            )
        }
        item {
            CheckboxRow(
                title = stringResource(R.string.settings_sync_metered_wifi_title),
                body = stringResource(R.string.settings_sync_metered_wifi_body),
                checked = state.syncOnMeteredWifi,
                onCheckedChange = viewModel::setSyncOnMeteredWifi,
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_allowed_wifi_networks_title),
                body = networksSummary(state.allowedWifiNetworks),
                onClick = { editingAllowedNetworks = true },
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_disallowed_wifi_networks_title),
                body = networksSummary(state.disallowedWifiNetworks),
                onClick = { editingDisallowedNetworks = true },
            )
        }
        item {
            CheckboxRow(
                title = stringResource(R.string.settings_sync_mobile_roaming_title),
                body = stringResource(R.string.settings_sync_mobile_roaming_body),
                checked = state.syncOnMobileRoaming,
                onCheckedChange = viewModel::setSyncOnMobileRoaming,
            )
        }
        item {
            CheckboxRow(
                title = stringResource(R.string.settings_sync_slow_2g_title),
                body = stringResource(R.string.settings_sync_slow_2g_body),
                checked = state.syncOnSlow2g,
                onCheckedChange = viewModel::setSyncOnSlow2g,
            )
        }
    }

    if (showUploadLimitDialog) {
        IntOptionsDialog(
            title = stringResource(R.string.settings_mobile_upload_limit_title),
            options = listOf(25, 100, 250, 500, 1_024, 0),
            valueFormatter = { formatMobileLimit(ctx, it) },
            onSelect = {
                viewModel.setMobileUploadLimitMb(it)
                showUploadLimitDialog = false
            },
            onDismiss = { showUploadLimitDialog = false },
        )
    }

    if (showDownloadLimitDialog) {
        IntOptionsDialog(
            title = stringResource(R.string.settings_mobile_download_limit_title),
            options = listOf(25, 100, 250, 500, 1_024, 0),
            valueFormatter = { formatMobileLimit(ctx, it) },
            onSelect = {
                viewModel.setMobileDownloadLimitMb(it)
                showDownloadLimitDialog = false
            },
            onDismiss = { showDownloadLimitDialog = false },
        )
    }

    if (showRetryWaitDialog) {
        IntOptionsDialog(
            title = stringResource(R.string.settings_retry_wait_title),
            options = listOf(1, 5, 10, 15, 30, 60),
            valueFormatter = { ctx.getString(R.string.settings_retry_wait_value, it) },
            onSelect = {
                viewModel.setRetryWaitMinutes(it)
                showRetryWaitDialog = false
            },
            onDismiss = { showRetryWaitDialog = false },
        )
    }

    if (showRetryAttemptsDialog) {
        IntOptionsDialog(
            title = stringResource(R.string.settings_retry_max_attempts_title),
            options = listOf(1, 2, 3, 5, 7, 10),
            valueFormatter = { ctx.getString(R.string.settings_retry_max_attempts_value, it) },
            onSelect = {
                viewModel.setRetryMaxAttempts(it)
                showRetryAttemptsDialog = false
            },
            onDismiss = { showRetryAttemptsDialog = false },
        )
    }

    if (showScheduleDialog) {
        EnumOptionsDialog(
            title = stringResource(R.string.settings_auto_sync_schedule_title),
            values = AutoSyncSchedule.entries,
            selected = state.autoSyncSchedule,
            label = { autoSyncScheduleLabel(it) },
            onSelect = {
                viewModel.setAutoSyncSchedule(it)
                showScheduleDialog = false
            },
            onDismiss = { showScheduleDialog = false },
        )
    }

    if (showConnectionScopeDialog) {
        EnumOptionsDialog(
            title = stringResource(R.string.settings_internet_scope_title),
            values = InternetConnectionScope.entries,
            selected = state.internetConnectionScope,
            label = { internetConnectionScopeLabel(it) },
            onSelect = {
                viewModel.setInternetConnectionScope(it)
                showConnectionScopeDialog = false
            },
            onDismiss = { showConnectionScopeDialog = false },
        )
    }

    if (editingAllowedNetworks) {
        WifiNetworksDialog(
            title = stringResource(R.string.settings_allowed_wifi_networks_title),
            initial = state.allowedWifiNetworks,
            onSave = {
                viewModel.setAllowedWifiNetworks(it)
                editingAllowedNetworks = false
            },
            onDismiss = { editingAllowedNetworks = false },
        )
    }

    if (editingDisallowedNetworks) {
        WifiNetworksDialog(
            title = stringResource(R.string.settings_disallowed_wifi_networks_title),
            initial = state.disallowedWifiNetworks,
            onSave = {
                viewModel.setDisallowedWifiNetworks(it)
                editingDisallowedNetworks = false
            },
            onDismiss = { editingDisallowedNetworks = false },
        )
    }
}

@Composable
private fun AppearanceSettingsContent(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            LanguageRow(
                selected = state.appLanguage,
                onSelected = viewModel::setAppLanguage,
            )
        }
        item {
            DarkModeRow(
                selected = state.darkMode,
                onSelected = viewModel::setDarkMode,
            )
        }
        item {
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color_title),
                body =
                    if (supported) {
                        stringResource(R.string.settings_dynamic_color_body)
                    } else {
                        stringResource(R.string.settings_dynamic_color_unsupported)
                    },
                checked = state.dynamicColor && supported,
                onCheckedChange = viewModel::setDynamicColor,
                enabled = supported,
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_respect_font_scale_title),
                body = stringResource(R.string.settings_respect_font_scale_body),
                checked = state.respectFontScale,
                onCheckedChange = viewModel::setRespectFontScale,
            )
        }
    }
}

@Composable
private fun NotificationsSettingsContent(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel,
    ctx: Context,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            SwitchRow(
                title = stringResource(R.string.settings_notify_success_title),
                body = stringResource(R.string.settings_notify_success_body),
                checked = state.notifyOnSuccess,
                onCheckedChange = viewModel::setNotifyOnSuccess,
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_notify_failure_title),
                body = stringResource(R.string.settings_notify_failure_body),
                checked = state.notifyOnFailure,
                onCheckedChange = viewModel::setNotifyOnFailure,
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_enable_haptics_title),
                body = stringResource(R.string.settings_enable_haptics_body),
                checked = state.enableHaptics,
                onCheckedChange = viewModel::setEnableHaptics,
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_notification_channels_title),
                body = stringResource(R.string.settings_notification_channels_body),
                onClick = {
                    runCatching { ctx.startActivity(viewModel.buildChannelSettingsIntent()) }
                },
            )
        }
    }
}

@Composable
private fun BatterySettingsContent(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            SwitchRow(
                title = stringResource(R.string.settings_default_wifi_only_title),
                body = stringResource(R.string.settings_default_wifi_only_body),
                checked = state.defaultWifiOnly,
                onCheckedChange = viewModel::setDefaultWifiOnly,
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_default_charging_only_title),
                body = stringResource(R.string.settings_default_charging_only_body),
                checked = state.defaultChargingOnly,
                onCheckedChange = viewModel::setDefaultChargingOnly,
            )
        }
    }
}

@Composable
private fun SecuritySettingsContent(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    var showPinTimeoutDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pinSetupComingSoon = stringResource(R.string.settings_security_set_pin_coming_soon)
    val pinTimeoutMinutesFormat = stringResource(R.string.settings_security_pin_timeout_minutes_format)
    val pinTimeoutOptions =
        listOf(
            SettingsRepository.MIN_PIN_TIMEOUT_MINUTES,
            2,
            5,
            10,
            SettingsRepository.MAX_PIN_TIMEOUT_MINUTES,
        ).distinct()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            SwitchRow(
                title = stringResource(R.string.settings_security_pin_enable_title),
                body = stringResource(R.string.settings_security_pin_enable_body),
                checked = state.pinProtectionEnabled,
                onCheckedChange = viewModel::setPinProtectionEnabled,
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_security_set_pin_title),
                body = stringResource(R.string.settings_security_set_pin_body),
                enabled = state.pinProtectionEnabled,
                onClick = {
                    scope.launch { snackbarHostState.showSnackbar(pinSetupComingSoon) }
                },
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_security_pin_timeout_title),
                body =
                    stringResource(
                        R.string.settings_security_pin_timeout_body_format,
                        state.pinTimeoutMinutes,
                    ),
                enabled = state.pinProtectionEnabled,
                onClick = { showPinTimeoutDialog = true },
            )
        }
        item {
            CheckboxRow(
                title = stringResource(R.string.settings_security_biometrics_title),
                body = stringResource(R.string.settings_security_biometrics_body),
                checked = state.unlockWithBiometrics,
                enabled = state.pinProtectionEnabled,
                onCheckedChange = viewModel::setUnlockWithBiometrics,
            )
        }
        item {
            CheckboxRow(
                title = stringResource(R.string.settings_security_protect_settings_only_title),
                body = stringResource(R.string.settings_security_protect_settings_only_body),
                checked = state.protectSettingsOnly,
                enabled = state.pinProtectionEnabled,
                onCheckedChange = viewModel::setProtectSettingsOnly,
            )
        }
    }

    if (showPinTimeoutDialog) {
        IntOptionsDialog(
            title = stringResource(R.string.settings_security_pin_timeout_title),
            options = pinTimeoutOptions,
            valueFormatter = { pinTimeoutMinutesFormat.format(it) },
            onSelect = {
                viewModel.setPinTimeoutMinutes(it)
                showPinTimeoutDialog = false
            },
            onDismiss = { showPinTimeoutDialog = false },
        )
    }
}

@Composable
private fun BackupSettingsContent(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            LogRetentionRow(
                days = state.logRetentionDays,
                onSelected = { viewModel.setLogRetentionDays(it.days) },
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_export_logs_title),
                body = stringResource(R.string.settings_export_logs_body),
                onClick = viewModel::exportLogs,
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_clear_cache_title),
                body = stringResource(R.string.settings_clear_cache_body),
                onClick = viewModel::clearCache,
            )
        }
    }
}

@Composable
private fun AboutSettingsContent(
    viewModel: SettingsViewModel,
    ctx: Context,
    contentPadding: PaddingValues,
    onShowOssDialog: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            ActionRow(
                title = stringResource(R.string.settings_version_title),
                body =
                    stringResource(
                        R.string.settings_version_format,
                        viewModel.versionName,
                        viewModel.versionCode,
                    ),
                onClick = null,
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_reset_hints_title),
                body = stringResource(R.string.settings_reset_hints_body),
                onClick = viewModel::resetHints,
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_privacy_policy_title),
                body = stringResource(R.string.settings_privacy_policy_body),
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.privacyPolicyUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
        item {
            ActionRow(
                title = stringResource(R.string.settings_oss_licenses_title),
                body = stringResource(R.string.settings_oss_licenses_body),
                onClick = onShowOssDialog,
            )
        }
    }
}

@Composable
private fun SupportSettingsContent(
    viewModel: SettingsViewModel,
    ctx: Context,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            val feedbackBody =
                if (viewModel.feedbackEmailConfig.isConfigured) {
                    stringResource(R.string.settings_send_feedback_body)
                } else {
                    stringResource(
                        R.string.settings_send_feedback_body_unset,
                        viewModel.feedbackEmailConfig.address,
                    )
                }
            ActionRow(
                title = stringResource(R.string.settings_send_feedback_title),
                body = feedbackBody,
                onClick = viewModel::sendFeedback,
            )
        }
    }
}

// =============================================================================
// Reusable row primitives (used by sub-screens)
// =============================================================================

@Composable
private fun SettingsGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(0.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun CheckboxRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val titleColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val bodyColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = bodyColor,
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    body: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val rowModifier =
        if (onClick != null && enabled) {
            Modifier
                .fillMaxWidth()
                .selectable(selected = false, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        }
    val titleColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val bodyColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        }
    Column(modifier = rowModifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = bodyColor,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LanguageRow(
    selected: AppLanguagePreference,
    onSelected: (AppLanguagePreference) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.settings_language_relaunch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        listOf(
            AppLanguagePreference.SYSTEM to R.string.settings_language_system,
            AppLanguagePreference.ENGLISH to R.string.settings_language_english,
        ).forEach { (language, label) ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected == language,
                            onClick = { onSelected(language) },
                        )
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == language, onClick = null)
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun DarkModeRow(
    selected: DarkModePreference,
    onSelected: (DarkModePreference) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            stringResource(R.string.settings_dark_mode_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(4.dp))
        listOf(
            DarkModePreference.SYSTEM to R.string.settings_dark_mode_system,
            DarkModePreference.LIGHT to R.string.settings_dark_mode_light,
            DarkModePreference.DARK to R.string.settings_dark_mode_dark,
        ).forEach { (mode, label) ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected == mode,
                            onClick = { onSelected(mode) },
                        )
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = { onSelected(mode) })
                Spacer(Modifier.height(0.dp))
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ConcurrentTransfersRow(value: Int, max: Int, onValueChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.settings_concurrent_transfers_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_concurrent_transfers_value_format, value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            stringResource(R.string.settings_concurrent_transfers_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 1f..max.toFloat(),
            steps = max - 2,
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    body: String,
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    min: Int = 1,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "$value",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            // For range [min..max], endpoints are implicit; steps = (discrete values - 2).
            // Example: [1..5] has 5 values and needs 3 steps (2, 3, 4).
            steps = (max - min - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun WebsiteInfoRow(text: String, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun IntOptionsDialog(
    title: String,
    options: List<Int>,
    valueFormatter: (Int) -> String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { value ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(value) }
                                .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = valueFormatter(value))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_delete_cancel)) }
        },
    )
}

@Composable
private fun <T> EnumOptionsDialog(
    title: String,
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                values.forEach { value ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = value == selected,
                                    onClick = { onSelect(value) },
                                )
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value == selected,
                            onClick = { onSelect(value) },
                        )
                        Text(
                            text = label(value),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_delete_cancel)) }
        },
    )
}

@Composable
private fun WifiNetworksDialog(
    title: String,
    initial: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.sorted().joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_wifi_networks_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        text
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toSet(),
                    )
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_delete_cancel)) }
        },
    )
}

@Composable
private fun LogRetentionRow(
    days: Int,
    onSelected: (LogRetentionPreference) -> Unit,
) {
    val current = LogRetentionPreference.fromDays(days)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.settings_log_retention_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.settings_log_retention_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        listOf(
            LogRetentionPreference.SEVEN_DAYS to R.string.settings_log_retention_7d,
            LogRetentionPreference.THIRTY_DAYS to R.string.settings_log_retention_30d,
            LogRetentionPreference.NINETY_DAYS to R.string.settings_log_retention_90d,
        ).forEach { (preset, label) ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = preset == current,
                            onClick = { onSelected(preset) },
                        )
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = preset == current, onClick = { onSelected(preset) })
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ConflictPolicyPickerDialog(
    selected: ConflictPolicy,
    onPick: (ConflictPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_default_conflict_policy_title)) },
        text = {
            Column {
                ConflictPolicy.entries.forEach { policy ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = policy == selected,
                                    onClick = { onPick(policy) },
                                )
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = policy == selected, onClick = { onPick(policy) })
                        Text(
                            text = conflictPolicyLabel(policy),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_delete_cancel)) }
        },
    )
}

@Composable
private fun OssLicensesDialog(
    loadText: suspend () -> String?,
    onDismiss: () -> Unit,
) {
    val text by produceState<String?>(initialValue = null) { value = loadText() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_oss_licenses_title)) },
        text = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val display = text ?: stringResource(R.string.settings_oss_licenses_unavailable)
                Text(
                    text = display,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_oss_licenses_close))
            }
        },
    )
}

@Composable
private fun autoSyncScheduleLabel(schedule: AutoSyncSchedule): String =
    when (schedule) {
        AutoSyncSchedule.EVERY_15_MINUTES -> stringResource(R.string.settings_auto_sync_schedule_15m)
        AutoSyncSchedule.EVERY_30_MINUTES -> stringResource(R.string.settings_auto_sync_schedule_30m)
        AutoSyncSchedule.HOURLY -> stringResource(R.string.settings_auto_sync_schedule_hourly)
        AutoSyncSchedule.DAILY -> stringResource(R.string.settings_auto_sync_schedule_daily)
    }

@Composable
private fun internetConnectionScopeLabel(scope: InternetConnectionScope): String =
    when (scope) {
        InternetConnectionScope.WIFI_AND_MOBILE ->
            stringResource(R.string.settings_internet_scope_wifi_and_mobile)
        InternetConnectionScope.WIFI_ONLY ->
            stringResource(R.string.settings_internet_scope_wifi_only)
        InternetConnectionScope.MOBILE_ONLY ->
            stringResource(R.string.settings_internet_scope_mobile_only)
    }

private fun formatMobileLimit(context: Context, valueMb: Int): String =
    // Non-composable helper to allow usage in dialog valueFormatter lambdas where
    // stringResource is unavailable.
    if (valueMb == 0) {
        context.getString(R.string.settings_mobile_limit_unlimited)
    } else {
        context.getString(R.string.settings_mobile_limit_value, valueMb)
    }

@Composable
private fun networksSummary(networks: Set<String>): String =
    if (networks.isEmpty()) {
        stringResource(R.string.settings_wifi_networks_none)
    } else {
        networks.sorted().joinToString(", ")
    }

@Composable
private fun conflictPolicyLabel(policy: ConflictPolicy): String =
    when (policy) {
        ConflictPolicy.NEWEST_WINS -> stringResource(R.string.conflict_policy_newest_wins)
        ConflictPolicy.PREFER_LOCAL -> stringResource(R.string.conflict_policy_prefer_local)
        ConflictPolicy.PREFER_REMOTE -> stringResource(R.string.conflict_policy_prefer_remote)
        ConflictPolicy.KEEP_BOTH -> stringResource(R.string.conflict_policy_keep_both)
    }

/**
 * Launches the system share sheet for the bundled log zip. Wrapped in
 * `runCatching` because some OEMs return no chooser-resolver activity.
 */
private fun startShareLogs(ctx: Context, uri: Uri) {
    runCatching {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.settings_export_logs_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ctx.startActivity(
            Intent.createChooser(send, ctx.getString(R.string.settings_export_logs_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun startSendFeedback(
    ctx: Context,
    uri: Uri,
    feedbackEmail: String,
    versionName: String,
): Boolean =
    runCatching {
        val subject = ctx.getString(R.string.settings_send_feedback_subject, versionName)
        val body =
            ctx.getString(
                R.string.settings_send_feedback_template,
                versionName,
                Build.VERSION.RELEASE,
                Build.MANUFACTURER,
                Build.MODEL,
                currentLocale(ctx).toLanguageTag(),
            )
        val clipData = ClipData.newRawUri("synckro_feedback_logs", uri)
        val sendToIntent =
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", feedbackEmail, null)).apply {
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uri)
                this.clipData = clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (sendToIntent.resolveActivity(ctx.packageManager) != null) {
            ctx.startActivity(sendToIntent)
            return@runCatching true
        }
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(feedbackEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uri)
                this.clipData = clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ctx.startActivity(
            Intent.createChooser(sendIntent, ctx.getString(R.string.settings_send_feedback_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

private fun currentLocale(ctx: Context): Locale =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        ctx.resources.configuration.locales.get(0) ?: Locale.getDefault()
    } else {
        @Suppress("DEPRECATION")
        ctx.resources.configuration.locale ?: Locale.getDefault()
    }
