package com.synckro.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.ui.screens.home.HomeViewModel

/**
 * Minimal Phase 1 Status tab.
 *
 * Renders an at-a-glance summary of the user's current sync state (number of
 * configured pairs and pending conflicts). It is intentionally a thin shell
 * over the shared [HomeViewModel] so later phases can flesh it out with the
 * detailed dashboard described in the design.
 */
@Composable
fun StatusScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.status_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (state.pairs.isEmpty()) {
            Text(
                text = stringResource(R.string.status_summary_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = stringResource(R.string.status_summary_pairs, state.pairs.size),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.status_summary_conflicts, state.pendingConflictCount),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
