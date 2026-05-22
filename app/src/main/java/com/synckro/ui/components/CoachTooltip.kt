package com.synckro.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

object CoachTooltipIds {
    const val PairsFab = "pairs_fab"
    const val ConflictsTab = "conflicts_tab"
    const val LogsExport = "logs_export"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachTooltip(
    visible: Boolean,
    tooltipText: String,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tooltipState = rememberTooltipState()
    val positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider()

    LaunchedEffect(visible) {
        if (visible) {
            onShown()
            tooltipState.show()
        }
    }

    TooltipBox(
        positionProvider = positionProvider,
        tooltip = { PlainTooltip { Text(tooltipText) } },
        state = tooltipState,
        focusable = false,
        enableUserInput = false,
        modifier = modifier,
    ) {
        content()
    }
}
