package com.synckro.ui.screens.accounts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.auth.Account
import com.synckro.ui.auth.ActivityAuthUiHost

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
    onBack: () -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val host = remember(activity) { ActivityAuthUiHost(activity) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.accounts_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            state.rows.forEach { row ->
                AccountProviderCard(
                    row = row,
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = row.providerDisplayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!row.isConfigured) {
                Text(
                    text = stringResource(R.string.accounts_not_configured_format, row.providerDisplayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (row.accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.accounts_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                row.accounts.forEach { item ->
                    AccountItemRow(
                        item = item,
                        isBusy = row.isBusy,
                        onDisconnect = { onDisconnect(item.account) },
                        onReauth = onConnect,
                    )
                }
            }
            // "Add another" when accounts exist; "Connect" when none; "Re-authenticate"
            // when the provider level needs re-auth and no accounts are listed.
            Button(
                onClick = onConnect,
                enabled = !row.isBusy && row.isConfigured,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (row.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    val label =
                        when {
                            row.accounts.isNotEmpty() ->
                                stringResource(R.string.accounts_add_another_format, row.providerDisplayName)
                            row.needsReauth ->
                                stringResource(R.string.accounts_reauth_button, row.providerDisplayName)
                            else ->
                                stringResource(R.string.accounts_connect_format, row.providerDisplayName)
                        }
                    Text(label)
                }
            }
        }
    }
}

/**
 * A single row showing an account's avatar (first-letter initials), email /
 * display name, an optional per-account Re-authenticate button, and a Disconnect
 * button.
 */
@Composable
private fun AccountItemRow(
    item: AccountsViewModel.AccountItem,
    isBusy: Boolean,
    onDisconnect: () -> Unit,
    onReauth: () -> Unit,
) {
    val label = item.account.email ?: item.account.displayName
    val initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Avatar circle with initials
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
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
