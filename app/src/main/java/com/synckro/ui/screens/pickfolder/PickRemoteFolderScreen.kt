package com.synckro.ui.screens.pickfolder

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.provider.RemoteFile
import com.synckro.ui.auth.ActivityAuthUiHost
import com.synckro.ui.components.LoadingState

/**
 * Screen that lets the user browse through a cloud provider's folder hierarchy
 * and select a folder to use as the remote sync destination.
 *
 * Back navigation (system back or the top-app-bar arrow) navigates up the folder
 * tree rather than closing the screen. Pressing back at the root calls [onBack].
 *
 * When the cloud provider returns an authentication error the ViewModel emits a
 * [PickRemoteFolderViewModel.reauthEvent].  This screen observes that event and
 * immediately calls [PickRemoteFolderViewModel.signInAndRetry] with an
 * [ActivityAuthUiHost] so the interactive sign-in flow is launched automatically
 * without the user having to navigate away to the Accounts screen first.
 *
 * @param activity The host [ComponentActivity] needed to launch interactive sign-in
 *   flows (Credential Manager / consent intents). Kept here — not in the ViewModel —
 *   to avoid leaking Activity references.
 * @param onFolderPicked Called with the selected folder's ID and display name.
 *   An empty [id] means the root level was chosen.
 * @param onBack Called when the user cancels without making a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickRemoteFolderScreen(
    activity: ComponentActivity,
    onFolderPicked: (id: String, name: String) -> Unit,
    onBack: () -> Unit,
    viewModel: PickRemoteFolderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val host = remember(activity) { ActivityAuthUiHost(activity) }
    var showCreateFolderDialog by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }

    // When the ViewModel detects an expired or missing access token it emits a
    // reauthEvent instead of showing an error.  We collect that here and kick off
    // the interactive sign-in flow; the ViewModel keeps isReauthenticating = true
    // so the screen shows a dedicated "Signing in…" message instead of the generic
    // loading spinner.
    LaunchedEffect(Unit) {
        viewModel.reauthEvent.collect {
            viewModel.signInAndRetry { manager -> manager.signIn(host) }
        }
    }

    // Intercept system back to navigate up the hierarchy; close the screen only when at root.
    BackHandler(enabled = state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    val currentName = state.breadcrumbs.lastOrNull()?.folderName ?: "/"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pick_remote_folder_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!viewModel.navigateUp()) onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCreateFolderDialog = true },
                        enabled = !state.isLoading && !state.isReauthenticating,
                    ) {
                        Icon(
                            Icons.Filled.CreateNewFolder,
                            contentDescription = stringResource(R.string.pick_remote_folder_create_folder),
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
                    .fillMaxSize(),
        ) {
            // Breadcrumb trail showing the current navigation path.
            BreadcrumbRow(
                breadcrumbs = state.breadcrumbs,
                onBreadcrumbClick = viewModel::navigateToBreadcrumb,
            )

            HorizontalDivider()

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isReauthenticating -> {
                        LoadingState(
                            message = stringResource(R.string.pick_remote_folder_signing_in),
                        )
                    }
                    state.isLoading -> {
                        LoadingState(
                            message = stringResource(R.string.loading_folders),
                        )
                    }
                    state.error != null -> {
                        ErrorContent(
                            error = state.error,
                            onRetry = viewModel::retry,
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                        )
                    }
                    state.items.isEmpty() -> {
                        Text(
                            text =
                                if (state.currentFolderId == null) {
                                    stringResource(R.string.pick_remote_folder_empty_root)
                                } else {
                                    stringResource(R.string.pick_remote_folder_empty)
                                },
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.items, key = { it.id }) { folder ->
                                FolderItem(
                                    folder = folder,
                                    onClick = { viewModel.navigateInto(folder) },
                                )
                                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            }
                        }
                    }
                }
            }

            // Bottom action: confirm selection of the currently-displayed folder.
            Surface(shadowElevation = 4.dp) {
                Button(
                    onClick = {
                        val folderId = state.currentFolderId ?: ""
                        onFolderPicked(folderId, currentName)
                    },
                    enabled = !state.isLoading && !state.isReauthenticating && state.error == null,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.pick_remote_folder_confirm, currentName))
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.pick_remote_folder_create_folder_title)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.pick_remote_folder_create_folder_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(newFolderName)
                        showCreateFolderDialog = false
                        newFolderName = ""
                    },
                    enabled = newFolderName.isNotBlank() && !state.isLoading && !state.isReauthenticating,
                ) {
                    Text(stringResource(R.string.pick_remote_folder_create_folder_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun BreadcrumbRow(
    breadcrumbs: List<PickRemoteFolderViewModel.BreadcrumbEntry>,
    onBreadcrumbClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(breadcrumbs) { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val isLast = index == breadcrumbs.lastIndex
            Text(
                text = crumb.folderName,
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (isLast) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .clickable(enabled = !isLast) { onBreadcrumbClick(index) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FolderItem(
    folder: RemoteFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = folder.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = stringResource(R.string.pick_remote_folder_navigate_into),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ErrorContent(
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = error ?: stringResource(R.string.pick_remote_folder_unknown_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.pick_remote_folder_retry))
        }
    }
}
