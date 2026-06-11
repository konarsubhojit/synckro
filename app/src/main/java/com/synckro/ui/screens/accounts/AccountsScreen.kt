package com.synckro.ui.screens.accounts

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.auth.Account
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.provider.StorageQuota
import com.synckro.ui.auth.ActivityAuthUiHost
import com.synckro.ui.components.ErrorState
import com.synckro.ui.components.LoadingState
import com.synckro.ui.components.SectionCard

/**
 * Lists connected / connectable cloud accounts. This is the "login first"
 * entry point the user lands on before adding a sync pair.
 *
 * [activity] is threaded in from the navigation host rather than retrieved by
 * casting `LocalContext`, so the screen is safe to host from non-activity
 * contexts (previews, multi-activity setups) — callers are forced to supply
 * the concrete [ComponentActivity] used for interactive sign-in flows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    activity: ComponentActivity,
    onBack: (() -> Unit)? = null,
    highlightAccountId: String? = null,
    onHighlightConsumed: () -> Unit = {},
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val host = remember(activity) { ActivityAuthUiHost(activity) }

    // Phase 5d: forward deep-link requests into the ViewModel as a one-shot, then
    // immediately tell the host it has been consumed so the same id doesn't keep
    // re-triggering on every recomposition.
    LaunchedEffect(highlightAccountId) {
        if (highlightAccountId != null) {
            viewModel.setHighlight(highlightAccountId)
            onHighlightConsumed()
        }
    }

    state.pendingDisconnect?.let { pending ->
        DisconnectConfirmDialog(
            pending = pending,
            onCancel = { viewModel.cancelDisconnect() },
            onDelete = { viewModel.confirmDisconnectDelete() },
            onReassign = { toAccountId -> viewModel.confirmDisconnectReassign(toAccountId) },
        )
    }

    state.pendingRename?.let { pending ->
        RenameDialog(
            account = pending.account,
            onCancel = { viewModel.cancelRename() },
            onConfirm = { newName -> viewModel.confirmRename(newName) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
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
    ) { padding ->
        when {
            state.isLoading -> {
                LoadingState(
                    message = stringResource(R.string.loading_accounts),
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
            state.error != null -> {
                ErrorState(
                    title = stringResource(R.string.error_state_accounts_title),
                    body = stringResource(R.string.error_state_accounts_body),
                    onRetry = viewModel::refresh,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
            else -> {
                Column(
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Introductory instruction banner
                    SectionCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.accounts_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = stringResource(R.string.accounts_body_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    state.rows.forEach { row ->
                        AccountProviderSection(
                            row = row,
                            highlightedAccountId = state.highlightedAccountId,
                            onConnect = {
                                viewModel.connect(row.providerKey) { manager -> manager.signIn(host) }
                            },
                            onSignOut = { viewModel.disconnect(it) },
                            onRename = { viewModel.startRename(it) },
                            onRemove = { viewModel.remove(it) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Provider section: one section per provider type
// ---------------------------------------------------------------------------

@Composable
private fun AccountProviderSection(
    row: AccountsViewModel.AccountRow,
    highlightedAccountId: String?,
    onConnect: () -> Unit,
    onSignOut: (Account) -> Unit,
    onRename: (Account) -> Unit,
    onRemove: (Account) -> Unit,
) {
    val needsReauth = row.needsReauth
    val cardColor =
        if (needsReauth) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    SectionCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = cardColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        // Provider header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderIcon(providerKey = row.providerKey)
                Text(
                    text = row.providerDisplayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (row.accounts.isNotEmpty() && !needsReauth) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.accounts_provider_connected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (!row.isConfigured) {
            Text(
                text = stringResource(R.string.accounts_not_configured_format, row.providerDisplayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Reauth banner — shown at provider level when ANY account needs it
        if (needsReauth) {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.accounts_reauth_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Connected account cards
        if (row.accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.accounts_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HorizontalDivider()
            row.accounts.forEach { item ->
                AccountCard(
                    item = item,
                    providerDisplayName = row.providerDisplayName,
                    isBusy = row.isBusy,
                    isHighlighted = item.account.id == highlightedAccountId,
                    onSignOut = { onSignOut(item.account) },
                    onReauth = onConnect,
                    onRename = { onRename(item.account) },
                    onRemove = { onRemove(item.account) },
                )
            }
        }

        // "Add account" pill button
        AddAccountButton(
            row = row,
            onConnect = onConnect,
        )
    }
}

// ---------------------------------------------------------------------------
// Provider icon
// ---------------------------------------------------------------------------

@Composable
private fun ProviderIcon(
    providerKey: String,
    modifier: Modifier = Modifier,
) {
    val icon =
        when (providerKey) {
            CloudProviderType.ONEDRIVE.name -> Icons.Default.Cloud
            CloudProviderType.GOOGLE_DRIVE.name -> Icons.Default.Cloud
            else -> Icons.Default.Cloud
        }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(22.dp),
    )
}

// ---------------------------------------------------------------------------
// Account card
// ---------------------------------------------------------------------------

/**
 * A card showing one connected account's details (avatar, provider name,
 * display name, email, storage usage) plus a three-dot overflow menu with
 * "Rename", "Sign out", and "Remove" actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountCard(
    item: AccountsViewModel.AccountItem,
    providerDisplayName: String,
    isBusy: Boolean,
    onSignOut: () -> Unit,
    onReauth: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    isHighlighted: Boolean = false,
) {
    val account = item.account
    val label = account.email ?: account.displayName
    val initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    var menuExpanded by remember { mutableStateOf(false) }

    // Phase 5d: animate a brief background tint when this card is the target
    // of a reauth deep-link, then fade back to the default.
    val highlightColor =
        animateColorAsState(
            targetValue =
                if (isHighlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            animationSpec = tween(durationMillis = 350),
            label = "AccountCard.highlight",
        )

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    val context = LocalContext.current

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color =
            if (item.needsReauth) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            },
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .background(highlightColor.value)
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Top row: avatar + name/email column + overflow button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Avatar with initials
                val avatarDescription = stringResource(R.string.accounts_avatar_description, label)
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                            )
                            .semantics { contentDescription = avatarDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Name / provider / reauth columns
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Provider name (small label)
                    Text(
                        text = providerDisplayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Display name
                    Text(
                        text = account.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Email (if different from displayName)
                    if (account.email != null && account.email != account.displayName) {
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Reauth hint
                    if (item.needsReauth) {
                        Text(
                            text = stringResource(R.string.accounts_reauth_account_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Overflow menu
                Box {
                    val overflowDesc = stringResource(R.string.accounts_overflow_menu_description)
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !isBusy,
                        modifier = Modifier.semantics { contentDescription = overflowDesc },
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.accounts_rename)) },
                            leadingIcon = {
                                Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.accounts_sign_out)) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                if (item.needsReauth) onReauth() else onSignOut()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.accounts_remove),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }

            // Storage usage line
            StorageUsageRow(quota = item.storageQuota, context = context)
        }
    }
}

// ---------------------------------------------------------------------------
// Storage usage
// ---------------------------------------------------------------------------

@Composable
private fun StorageUsageRow(
    quota: StorageQuota?,
    context: android.content.Context,
) {
    when {
        quota == null -> {
            // Quota not yet loaded — show a subtle placeholder
            Text(
                text = stringResource(R.string.accounts_storage_fetching),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        quota.totalBytes > 0L -> {
            val usedFormatted = Formatter.formatShortFileSize(context, quota.usedBytes)
            val totalFormatted = Formatter.formatShortFileSize(context, quota.totalBytes)
            val percent = (quota.usedBytes * 100L / quota.totalBytes).toInt().coerceIn(0, 100)
            val usageText =
                stringResource(
                    R.string.accounts_storage_used_format,
                    usedFormatted,
                    percent,
                    totalFormatted,
                )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = usageText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    color =
                        if (percent >= 90) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// "Add account" pill button
// ---------------------------------------------------------------------------

@Composable
private fun AddAccountButton(
    row: AccountsViewModel.AccountRow,
    onConnect: () -> Unit,
) {
    val needsReauth = row.needsReauth
    val label =
        when {
            row.isBusy -> null
            needsReauth -> stringResource(R.string.accounts_reauth_button, row.providerDisplayName)
            row.accounts.isEmpty() -> stringResource(R.string.accounts_connect_format, row.providerDisplayName)
            else -> stringResource(R.string.accounts_add_account)
        }

    Button(
        onClick = onConnect,
        enabled = !row.isBusy && row.isConfigured,
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth(),
        colors =
            ButtonDefaults.buttonColors(
                containerColor =
                    if (needsReauth) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            ),
    ) {
        if (row.isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(label ?: "", fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------------------
// Rename dialog
// ---------------------------------------------------------------------------

@Composable
private fun RenameDialog(
    account: Account,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(account.id) { mutableStateOf(account.displayName) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.accounts_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.accounts_rename_dialog_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.trim().isNotEmpty(),
            ) {
                Text(stringResource(R.string.accounts_rename_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.accounts_rename_dialog_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Disconnect confirmation dialog (unchanged)
// ---------------------------------------------------------------------------

/**
 * Confirmation dialog shown when the user requests disconnect of an account
 * that still owns one or more sync pairs. Offers three branches:
 *
 *  * **Delete pairs &amp; disconnect** — drops every orphaned pair and signs out.
 *  * **Reassign pairs to <other account>** — re-binds orphans to the chosen
 *    account on the same provider, then signs the original out. Only shown
 *    when at least one re-assignment target exists.
 *  * **Cancel** — closes the dialog without making changes.
 */
@Composable
private fun DisconnectConfirmDialog(
    pending: AccountsViewModel.PendingDisconnect,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onReassign: (String) -> Unit,
) {
    val accountLabel = pending.account.email ?: pending.account.displayName
    var reassignMenuExpanded by remember { mutableStateOf(false) }
    var selectedReassign by remember(pending.account.id) {
        mutableStateOf(pending.reassignableAccounts.firstOrNull())
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(stringResource(R.string.accounts_disconnect_confirm_title, accountLabel))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.accounts_disconnect_confirm_body,
                        accountLabel,
                        pending.orphanedPairs.size,
                    ),
                )
                pending.orphanedPairs.take(MAX_ORPHAN_PREVIEW).forEach { pair ->
                    Text(
                        text = "• ${pair.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (pending.orphanedPairs.size > MAX_ORPHAN_PREVIEW) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (pending.reassignableAccounts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.accounts_disconnect_confirm_reassign_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Box {
                        OutlinedButton(onClick = { reassignMenuExpanded = true }) {
                            val current = selectedReassign
                            Text(current?.email ?: current?.displayName ?: "")
                        }
                        DropdownMenu(
                            expanded = reassignMenuExpanded,
                            onDismissRequest = { reassignMenuExpanded = false },
                        ) {
                            pending.reassignableAccounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = { Text(acc.email ?: acc.displayName) },
                                    onClick = {
                                        selectedReassign = acc
                                        reassignMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val target = selectedReassign
                if (target != null) {
                    TextButton(onClick = { onReassign(target.id) }) {
                        Text(
                            stringResource(
                                R.string.accounts_disconnect_confirm_reassign_format,
                                target.email ?: target.displayName,
                            ),
                        )
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.accounts_disconnect_confirm_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.accounts_disconnect_confirm_cancel))
            }
        },
    )
}

/** Maximum number of orphan-pair names to list inline in the disconnect dialog. */
private const val MAX_ORPHAN_PREVIEW: Int = 5
