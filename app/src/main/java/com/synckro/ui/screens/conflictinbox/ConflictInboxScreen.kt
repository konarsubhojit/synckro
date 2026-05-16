package com.synckro.ui.screens.conflictinbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.ConflictRecord
import com.synckro.ui.components.EmptyState
import com.synckro.ui.components.LoadingState
import java.text.DateFormat
import java.util.Date

/**
 * Displays the list of unresolved sync conflicts. Each conflict card explains
 * what changed on both sides and offers three resolution actions with clear
 * descriptions:
 *
 * - **Keep local** (↑ Upload) — the local version wins; overwrites cloud copy on next sync.
 * - **Keep remote** (↓ Download) — the cloud version wins; overwrites local copy on next sync.
 * - **Keep both** (⎘ Copy) — both versions are preserved with a conflict-copy name.
 *
 * Resolved conflicts are automatically removed after the next sync run.
 *
 * @param onBack Called when the user presses the back / up button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictInboxScreen(
    onBack: (() -> Unit)? = null,
    viewModel: ConflictInboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_inbox_title)) },
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
                    message = stringResource(R.string.loading_conflicts),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            state.conflicts.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.conflict_inbox_empty_title),
                    body = stringResource(R.string.conflict_inbox_empty_body),
                    icon = Icons.Filled.Inbox,
                    primaryActionLabel = onBack?.let { stringResource(R.string.conflict_inbox_empty_cta) },
                    onPrimaryAction = onBack,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            else -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        // Explain what a conflict is and how to resolve it
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.conflict_inbox_explainer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
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
    val resolved = conflict.resolution != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (resolved) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // File path
            Text(
                text = conflict.relativePath,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            HorizontalDivider()

            // Timestamps for both sides
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.conflict_inbox_local_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = fmt.format(Date(conflict.localLastModifiedMs)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.conflict_inbox_remote_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = fmt.format(Date(conflict.remoteLastModifiedMs)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.conflict_inbox_detected_at,
                    fmt.format(Date(conflict.detectedAtMs)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Queued resolution badge
            if (conflict.resolution != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(
                            R.string.conflict_inbox_pending_resolution,
                            resolutionLabel(conflict.resolution),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Resolution action buttons with icons and descriptions
            if (!resolved) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConflictActionButton(
                        icon = Icons.Default.CloudUpload,
                        label = stringResource(R.string.conflict_inbox_keep_local),
                        description = stringResource(R.string.conflict_inbox_keep_local_hint),
                        onClick = onKeepLocal,
                        isPrimary = false,
                    )
                    ConflictActionButton(
                        icon = Icons.Default.CloudDownload,
                        label = stringResource(R.string.conflict_inbox_keep_remote),
                        description = stringResource(R.string.conflict_inbox_keep_remote_hint),
                        onClick = onKeepRemote,
                        isPrimary = false,
                    )
                    ConflictActionButton(
                        icon = Icons.Default.ContentCopy,
                        label = stringResource(R.string.conflict_inbox_keep_both),
                        description = stringResource(R.string.conflict_inbox_keep_both_hint),
                        onClick = onKeepBoth,
                        isPrimary = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictActionButton(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
) {
    val buttonContent: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPrimary) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (isPrimary) {
        Button(onClick = onClick, modifier = modifier.fillMaxWidth()) {
            buttonContent()
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
            buttonContent()
        }
    }
}

@Composable
private fun resolutionLabel(resolution: String): String =
    when (resolution) {
        ConflictRecord.RESOLUTION_KEEP_LOCAL -> stringResource(R.string.conflict_inbox_keep_local)
        ConflictRecord.RESOLUTION_KEEP_REMOTE -> stringResource(R.string.conflict_inbox_keep_remote)
        ConflictRecord.RESOLUTION_KEEP_BOTH -> stringResource(R.string.conflict_inbox_keep_both)
        else -> resolution
    }


