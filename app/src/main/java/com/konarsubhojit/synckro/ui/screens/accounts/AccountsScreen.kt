package com.konarsubhojit.synckro.ui.screens.accounts

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.konarsubhojit.synckro.BuildConfig
import com.konarsubhojit.synckro.R
import com.konarsubhojit.synckro.ui.auth.ActivityAuthUiHost
import com.konarsubhojit.synckro.util.logging.FileLoggingTree
import java.io.File

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
            modifier = Modifier
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

            // Temporary debug-only button: exports the file log so login failures
            // can be inspected without a USB cable. Absent in release builds.
            if (BuildConfig.DEBUG) {
                val context = LocalContext.current
                Spacer(Modifier.height(8.dp))
                ExportDebugLogButton(context)
            }
        }
    }
}

/**
 * Temporary debug-only button that exports the in-app log file to the
 * public Downloads folder on API 29+ (no permission required), or fires an
 * ACTION_SEND chooser on API 26–28 via FileProvider.
 *
 * This button is only shown when [BuildConfig.DEBUG] is true (see call site).
 * The FileProvider authority `${packageName}.fileprovider` is declared
 * exclusively in the debug-variant AndroidManifest, so it is absent from
 * release builds.
 */
@Composable
private fun ExportDebugLogButton(context: Context) {
    val noLogMsg = stringResource(R.string.accounts_export_debug_log_none)
    val insertFailMsg = stringResource(R.string.accounts_export_debug_log_insert_failed)
    val savedMsg = stringResource(R.string.accounts_export_debug_log_saved)
    val subject = stringResource(R.string.accounts_export_debug_log_subject)
    val chooser = stringResource(R.string.accounts_export_debug_log_chooser)

    Button(
        onClick = { exportLog(context, noLogMsg, insertFailMsg, savedMsg, subject, chooser) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.accounts_export_debug_log))
    }
}

/**
 * Copies the debug log to the public Downloads folder (MediaStore, API 29+)
 * or fires an ACTION_SEND share-intent (API 26–28). Both paths require no
 * extra storage permissions.
 */
private fun exportLog(
    context: Context,
    noLogMsg: String,
    insertFailMsg: String,
    savedMsg: String,
    subject: String,
    chooser: String,
) {
    val logFile = File(context.filesDir, FileLoggingTree.LOG_RELATIVE_PATH)
    if (!logFile.exists()) {
        Toast.makeText(context, noLogMsg, Toast.LENGTH_LONG).show()
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // API 29+: insert directly into MediaStore Downloads — no permission needed.
        val destName = "synckro-debug.log"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, destName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: run {
            Toast.makeText(context, insertFailMsg, Toast.LENGTH_LONG).show()
            return
        }
        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                logFile.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            Toast.makeText(
                context,
                savedMsg.format(destName),
                Toast.LENGTH_LONG,
            ).show()
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
                .onFailure { android.util.Log.w("ExportLog", "Failed to clean up pending MediaStore entry", it) }
            Toast.makeText(context, e.localizedMessage ?: insertFailMsg, Toast.LENGTH_LONG).show()
        }
    } else {
        // API 26–28 fallback: share via FileProvider content URI so any app can receive it.
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooser))
    }
}

@Composable
private fun AccountProviderCard(
    row: AccountsViewModel.AccountRow,
    onConnect: () -> Unit,
    onDisconnect: (com.konarsubhojit.synckro.domain.auth.Account) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
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
            if (row.needsReauth) {
                // Surface the terminal-auth state inline so the user understands
                // why background sync stopped and what tapping the button below
                // is for.
                Text(
                    text = stringResource(R.string.accounts_reauth_required),
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
                row.accounts.forEach { account ->
                    Text(
                        text = stringResource(
                            R.string.accounts_signed_in_format,
                            account.email ?: account.displayName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedButton(
                        onClick = { onDisconnect(account) },
                        enabled = !row.isBusy,
                    ) {
                        Text(stringResource(R.string.accounts_disconnect))
                    }
                }
            }
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
                    // Re-authenticate when a pair for this provider is stuck in
                    // NEEDS_REAUTH; otherwise the normal "Connect %provider" CTA.
                    val label = if (row.needsReauth) {
                        stringResource(R.string.accounts_reauth_button, row.providerDisplayName)
                    } else {
                        stringResource(R.string.accounts_connect_format, row.providerDisplayName)
                    }
                    Text(label)
                }
            }
        }
    }
}
