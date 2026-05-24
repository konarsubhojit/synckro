package com.synckro.ui.screens.accounts

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.auth.Account
import com.synckro.ui.auth.ActivityAuthUiHost
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
                AccountProviderCard(
                    row = row,
                    highlightedAccountId = state.highlightedAccountId,
                    onConnect = {
                        viewModel.connect(row.providerKey) { manager -> manager.signIn(host) }
                    },
                    onDisconnect = { viewModel.disconnect(it) },
                )
            }
        }
    }
}

@Composable
private fun AccountProviderCard(
    row: AccountsViewModel.AccountRow,
    onConnect: () -> Unit,
    onDisconnect: (Account) -> Unit,
    highlightedAccountId: String? = null,
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
        // Provider header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.providerDisplayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (row.accounts.isNotEmpty() && !needsReauth) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
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

        // Connected accounts
        if (row.accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.accounts_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HorizontalDivider()
            row.accounts.forEach { item ->
                AccountItemRow(
                    item = item,
                    isBusy = row.isBusy,
                    isHighlighted = item.account.id == highlightedAccountId,
                    onDisconnect = { onDisconnect(item.account) },
                    onReauth = onConnect,
                )
            }
        }

        // Connect / Add another / Re-authenticate button
        Button(
            onClick = onConnect,
            enabled = !row.isBusy && row.isConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (row.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                val label =
                    when {
                        row.accounts.isNotEmpty() && !needsReauth ->
                            stringResource(R.string.accounts_add_another_format, row.providerDisplayName)
                        needsReauth ->
                            stringResource(R.string.accounts_reauth_button, row.providerDisplayName)
                        else ->
                            stringResource(R.string.accounts_connect_format, row.providerDisplayName)
                    }
                Text(label, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * A single row showing an account's avatar initials, email / display name,
 * an optional per-account Re-authenticate button, and a Disconnect button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountItemRow(
    item: AccountsViewModel.AccountItem,
    isBusy: Boolean,
    onDisconnect: () -> Unit,
    onReauth: () -> Unit,
    isHighlighted: Boolean = false,
) {
    val label = item.account.email ?: item.account.displayName
    val initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    // Phase 5d: animate a brief background tint when this row is the target of a
    // reauth deep-link, then fade back to the default. The VM clears
    // highlightedAccountId after ~2 s, which drives [isHighlighted] back to false
    // and animates the colour out via [animateColorAsState].
    val highlightColor =
        animateColorAsState(
            targetValue =
                if (isHighlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            animationSpec = tween(durationMillis = 350),
            label = "AccountItemRow.highlight",
        )

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            // Auto-scroll the surrounding scroll container so the highlighted row is
            // visible even when it was off-screen at deep-link time.
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .background(
                    color = highlightColor.value,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val avatarDescription = stringResource(R.string.accounts_avatar_description, label)
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    )
                    .semantics { contentDescription = avatarDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            if (item.needsReauth) {
                Text(
                    text = stringResource(R.string.accounts_reauth_account_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(R.string.accounts_signed_in_format, label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (item.needsReauth) {
            OutlinedButton(
                onClick = onReauth,
                enabled = !isBusy,
            ) {
                Text(stringResource(R.string.accounts_reauth_account))
            }
        }
        OutlinedButton(
            onClick = onDisconnect,
            enabled = !isBusy,
        ) {
            Text(stringResource(R.string.accounts_disconnect))
        }
    }
}

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
