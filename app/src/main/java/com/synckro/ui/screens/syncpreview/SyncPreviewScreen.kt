package com.synckro.ui.screens.syncpreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.ui.components.EmptyState
import com.synckro.ui.components.ErrorState
import com.synckro.ui.components.LoadingState
import com.synckro.ui.theme.SynckroTheme
import androidx.compose.ui.tooling.preview.Preview as ComposePreview

/**
 * Dry-run preview of the next sync run for a single pair (issue #359).
 *
 * The plan is computed on demand via [SyncPreviewViewModel.refresh], which calls
 * [com.synckro.domain.sync.SyncEngine.previewOnce]. No file or database state is
 * modified, so the user can inspect the planned adds/updates/deletes/moves before
 * triggering a real run from the Pair Detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPreviewScreen(
    onBack: () -> Unit,
    viewModel: SyncPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SyncPreviewContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncPreviewContent(
    state: SyncPreviewViewModel.UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.pairName ?: stringResource(R.string.sync_preview_title)) },
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
            state.isLoading ->
                LoadingState(
                    message = stringResource(R.string.sync_preview_loading),
                    modifier = Modifier.padding(padding),
                )
            state.pairMissing ->
                ErrorState(
                    title = stringResource(R.string.sync_preview_missing_pair),
                    retryLabel = null,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            state.error != null ->
                ErrorState(
                    title = stringResource(R.string.sync_preview_error_title),
                    body = state.error,
                    retryLabel = stringResource(R.string.sync_preview_refresh),
                    onRetry = onRefresh,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            !state.hasPlan ->
                EmptyState(
                    title = stringResource(R.string.sync_preview_start_title),
                    body = stringResource(R.string.sync_preview_intro),
                    icon = Icons.Filled.Preview,
                    primaryActionLabel = stringResource(R.string.sync_preview_run),
                    onPrimaryAction = onRefresh,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            state.groups.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.sync_preview_empty_title),
                    body = stringResource(R.string.sync_preview_empty_body),
                    icon = Icons.Filled.Preview,
                    primaryActionLabel = stringResource(R.string.sync_preview_refresh),
                    onPrimaryAction = onRefresh,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            else ->
                SyncPreviewPlan(
                    state = state,
                    onRefresh = onRefresh,
                    contentPadding = padding,
                )
        }
    }
}

@Composable
private fun SyncPreviewPlan(
    state: SyncPreviewViewModel.UiState,
    onRefresh: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.sync_preview_total_format, state.totalOps),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(state.groups, key = { it.kind.name }) { group ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "${stringResource(group.kind.labelRes())} (${group.paths.size})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    group.paths.forEach { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sync_preview_refresh))
            }
        }
    }
}

/** Maps a plan bucket to its user-facing section heading. */
internal fun PlanGroupKind.labelRes(): Int =
    when (this) {
        PlanGroupKind.UPLOAD -> R.string.sync_preview_group_upload
        PlanGroupKind.DOWNLOAD -> R.string.sync_preview_group_download
        PlanGroupKind.UPDATE_REMOTE -> R.string.sync_preview_group_update_remote
        PlanGroupKind.UPDATE_LOCAL -> R.string.sync_preview_group_update_local
        PlanGroupKind.DELETE_REMOTE -> R.string.sync_preview_group_delete_remote
        PlanGroupKind.DELETE_LOCAL -> R.string.sync_preview_group_delete_local
        PlanGroupKind.MOVE -> R.string.sync_preview_group_move
        PlanGroupKind.CONFLICT -> R.string.sync_preview_group_conflict
    }

@ComposePreview(name = "SyncPreview — plan", showBackground = true)
@Composable
private fun SyncPreviewPlanPreview() {
    SynckroTheme {
        SyncPreviewContent(
            state =
                SyncPreviewViewModel.UiState(
                    hasPlan = true,
                    totalOps = 3,
                    groups =
                        listOf(
                            SyncPreviewViewModel.PlanGroup(PlanGroupKind.UPLOAD, listOf("notes/todo.md")),
                            SyncPreviewViewModel.PlanGroup(PlanGroupKind.DOWNLOAD, listOf("photos/a.jpg", "photos/b.jpg")),
                        ),
                    pairName = "Camera backup",
                ),
            onBack = {},
            onRefresh = {},
        )
    }
}
