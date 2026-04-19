package com.konarsubhojit.synckro.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.konarsubhojit.synckro.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onAddSyncPair: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddSyncPair) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_sync_pair))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.home_empty))
        }
    }
}
