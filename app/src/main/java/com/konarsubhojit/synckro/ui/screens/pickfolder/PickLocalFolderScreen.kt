package com.konarsubhojit.synckro.ui.screens.pickfolder

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.konarsubhojit.synckro.R

/**
 * Screen that lets the user pick a local folder via the Storage Access Framework
 * (SAF) and persists the resulting tree-URI permission so the app retains
 * read/write access across reboots.
 *
 * Works for both internal storage and removable SD cards without requiring
 * `MANAGE_EXTERNAL_STORAGE`.
 *
 * @param initialUri Optional pre-selected tree-URI string (used when editing an
 *   existing sync pair so the user sees the current folder before re-picking).
 * @param onFolderPicked Called with the persisted tree-URI string once the user
 *   has confirmed their selection. The caller is responsible for storing it.
 * @param onBack Called when the user presses the back / up button without
 *   confirming a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickLocalFolderScreen(
    initialUri: String? = null,
    onFolderPicked: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pickedUri by rememberSaveable { mutableStateOf(initialUri) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read+write access so the permission survives app restarts
            // and device reboots.  Both flags are required; omitting WRITE would
            // allow only listing files.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            pickedUri = uri.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pick_folder_title)) },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.pick_folder_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { launcher.launch(pickedUri?.let(Uri::parse)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(R.string.pick_folder_button))
            }

            pickedUri?.let { uriString ->
                Spacer(Modifier.height(4.dp))
                SelectedFolderCard(uriString = uriString)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onFolderPicked(uriString) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.pick_folder_confirm))
                }
            }
        }
    }
}

@Composable
private fun SelectedFolderCard(uriString: String) {
    val context = LocalContext.current
    // Attempt to resolve a human-readable display name from the URI.
    val displayName = runCatching {
        val uri = Uri.parse(uriString)
        DocumentFile.fromTreeUri(context, uri)?.name ?: uriString
    }.getOrDefault(uriString)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.pick_folder_selected_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
