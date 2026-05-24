package com.synckro.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.synckro.ui.navigation.MainDestination

@Composable
fun MainDestinationTabRow(
    primaryDestinations: List<MainDestination>,
    selectedDestination: MainDestination,
    onSelectDestination: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = primaryDestinations.indexOf(selectedDestination).takeIf { it >= 0 }
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        TabRow(
            modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
            selectedTabIndex = selectedIndex ?: 0,
            indicator = { tabPositions ->
                if (selectedIndex != null) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    )
                }
            },
        ) {
            primaryDestinations.forEachIndexed { index, destination ->
                val label = stringResource(destination.labelRes)
                Tab(
                    selected = selectedIndex == index,
                    onClick = { onSelectDestination(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    text = { Text(label) },
                )
            }
        }
    }
}
