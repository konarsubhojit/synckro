package com.konarsubhojit.synckro.ui.screens.conflictinbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.konarsubhojit.synckro.R
import com.konarsubhojit.synckro.domain.model.ConflictRecord
import java.text.DateFormat
import java.util.Date

/**
 * Displays the list of unresolved sync conflicts. The user can choose:
 * - **Keep local** – the next sync will overwrite the remote with the local version.
 * - **Keep remote** – the next sync will overwrite the local with the remote version.
 * - **Keep both** – the next sync will rename one copy so both versions are preserved.
 *
 * Resolved conflicts are automatically removed from the list after the next sync run.
 *
 * @param onBack Called when the user presses the back / up button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictInboxScreen(
    onBack: () -> Unit,
    viewModel: ConflictInboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_inbox_title)) },
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
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.conflicts.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.conflict_inbox_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.conflicts, key = { it.id }) { conflict ->
                        ConflictCard(
                            conflict = conflict,
                            onKeepLocal = { viewModel.keepLocal(conflict.id) },
                            onKeepRemote = { viewModel.keepRemote(conflict.id) },
                            onKeepBoth = { viewModel.keepBoth(conflict.id) },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(
    conflict: ConflictRecord,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onKeepBoth: () -> Unit,
) {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = conflict.relativePath,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.conflict_inbox_detected_at,
                    fmt.format(Date(conflict.detectedAtMs)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.conflict_inbox_timestamps,
                    fmt.format(Date(conflict.localLastModifiedMs)),
                    fmt.format(Date(conflict.remoteLastModifiedMs)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (conflict.resolution != null) {
                Text(
                    text = stringResource(
                        R.string.conflict_inbox_pending_resolution,
                        resolutionLabel(conflict.resolution),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onKeepLocal,
                    modifier = Modifier.weight(1f),
                    enabled = conflict.resolution == null,
                ) {
                    Text(
                        stringResource(R.string.conflict_inbox_keep_local),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onKeepRemote,
                    modifier = Modifier.weight(1f),
                    enabled = conflict.resolution == null,
                ) {
                    Text(
                        stringResource(R.string.conflict_inbox_keep_remote),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onKeepBoth,
                    modifier = Modifier.weight(1f),
                    enabled = conflict.resolution == null,
                ) {
                    Text(
                        stringResource(R.string.conflict_inbox_keep_both),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun resolutionLabel(resolution: String): String = when (resolution) {
    ConflictRecord.RESOLUTION_KEEP_LOCAL -> stringResource(R.string.conflict_inbox_keep_local)
    ConflictRecord.RESOLUTION_KEEP_REMOTE -> stringResource(R.string.conflict_inbox_keep_remote)
    ConflictRecord.RESOLUTION_KEEP_BOTH -> stringResource(R.string.conflict_inbox_keep_both)
    else -> resolution
}
