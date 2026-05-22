package com.synckro.ui.screens.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.data.repository.DarkModePreference
import com.synckro.data.repository.LogRetentionPreference
import com.synckro.domain.model.ConflictPolicy
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Top-level Settings screen. Renders all user-configurable preferences in a
 * [LazyColumn] of grouped sections (Appearance / Sync defaults / Notifications
 * / Storage &amp; logs / About). Side-effects (log export, cache clear,
 * channel deep-link, OSS licenses dialog) are orchestrated here using the
 * one-shot [SettingsViewModel.events] flow so the ViewModel stays free of
 * Android Context-coupled UI concerns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // One-shot events drive intents/snackbars. `collectAsState` would be wrong
    // here because we need to react each time, not just to the latest value.
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

    // Local UI-only dialog state. These flags are intentionally screen-scoped
    // rather than in the ViewModel because they have no business-state impact.
    var conflictPolicyDialog by remember { mutableStateOf(false) }
    var ossDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = 0.dp,
                    end = 0.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
        ) {
            // ---------- Appearance ----------
            item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
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

            // ---------- Sync defaults ----------
            item { SectionHeader(stringResource(R.string.settings_section_sync_defaults)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_global_auto_sync_title),
                    body = stringResource(R.string.settings_global_auto_sync_body),
                    checked = state.globalAutoSyncEnabled,
                    onCheckedChange = viewModel::setGlobalAutoSync,
                )
            }
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
            item {
                ActionRow(
                    title = stringResource(R.string.settings_default_conflict_policy_title),
                    body = conflictPolicyLabel(state.defaultConflictPolicy),
                    onClick = { conflictPolicyDialog = true },
                )
            }

            // ---------- Notifications ----------
            item { SectionHeader(stringResource(R.string.settings_section_notifications)) }
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

            // ---------- Storage & logs ----------
            item { SectionHeader(stringResource(R.string.settings_section_storage_logs)) }
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

            // ---------- About ----------
            item { SectionHeader(stringResource(R.string.settings_section_about)) }
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
                    onClick = { ossDialog = true },
                )
            }
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

// -----------------------------------------------------------------------------
// Reusable section primitives
// -----------------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
    HorizontalDivider()
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
private fun ActionRow(
    title: String,
    body: String,
    onClick: (() -> Unit)?,
) {
    val rowModifier =
        if (onClick != null) {
            Modifier
                .fillMaxWidth()
                .selectable(selected = false, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        }
    Column(modifier = rowModifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DarkModeRow(
    selected: DarkModePreference,
    onSelected: (DarkModePreference) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                Build.VERSION.RELEASE ?: "Unknown",
                Build.MANUFACTURER,
                Build.MODEL,
                currentLocale(ctx).toLanguageTag(),
            )
        val clipData = ClipData.newRawUri("synckro-feedback-logs", uri)
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
