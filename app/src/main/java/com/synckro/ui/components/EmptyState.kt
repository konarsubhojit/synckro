package com.synckro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synckro.ui.theme.SynckroTheme

/**
 * Shared empty-state composable used across screens such as the home pair list,
 * the conflict inbox, and the logs viewer. Pairs a short headline with optional
 * supporting text, an illustrative icon, and an optional primary action button.
 *
 * The composable does NOT apply [fillMaxSize] to the outer [Box] — callers are
 * responsible for sizing it via [modifier] so it remains usable inside cards or
 * other constrained layouts. For full-screen empty states pass
 * `Modifier.fillMaxSize()`.
 *
 * @param title Headline describing the empty state.
 * @param body Optional secondary text providing context about why the state is empty.
 * @param icon Optional decorative icon shown above the title.
 * @param primaryActionLabel Label for the optional primary action button. When non-null
 *   together with [onPrimaryAction], a Material 3 [Button] is rendered below the body.
 * @param onPrimaryAction Click handler for the primary action.
 * @param secondaryActionLabel Label for the optional secondary action. When non-null
 *   together with [onSecondaryAction], a Material 3 [OutlinedButton] is rendered below
 *   the primary action.
 * @param onSecondaryAction Click handler for the secondary action.
 */
@Composable
fun EmptyState(
    title: String,
    body: String? = null,
    icon: ImageVector? = null,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (!body.isNullOrBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(primaryActionLabel)
                }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                OutlinedButton(
                    onClick = onSecondaryAction,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

@Preview(name = "EmptyState — minimal", showBackground = true)
@Composable
private fun EmptyStateMinimalPreview() {
    SynckroTheme {
        EmptyState(title = "No sync pairs yet")
    }
}

@Preview(name = "EmptyState — primary action", showBackground = true)
@Composable
private fun EmptyStatePrimaryPreview() {
    SynckroTheme {
        EmptyState(
            title = "No sync pairs yet",
            body = "Create a sync pair to keep a folder on this device in sync with a cloud account.",
            icon = Icons.Filled.CloudOff,
            primaryActionLabel = "Create your first sync pair",
            onPrimaryAction = {},
        )
    }
}

@Preview(name = "EmptyState — primary + secondary", showBackground = true)
@Preview(
    name = "EmptyState — primary + secondary (dark)",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun EmptyStatePrimaryAndSecondaryPreview() {
    SynckroTheme {
        EmptyState(
            title = "No sync pairs yet",
            body = "Create a sync pair to keep a folder on this device in sync with a cloud account.",
            icon = Icons.Filled.CloudOff,
            primaryActionLabel = "Create your first sync pair",
            onPrimaryAction = {},
            secondaryActionLabel = "Connect a cloud account",
            onSecondaryAction = {},
        )
    }
}
