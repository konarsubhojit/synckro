package com.synckro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synckro.R
import com.synckro.ui.theme.SynckroTheme

/**
 * Shared error-state composable used across screens to surface load or
 * operation failures with a clear message and an optional Retry action.
 *
 * The composable does NOT apply [fillMaxSize] to the outer [Box] — callers
 * are responsible for sizing it via [modifier]. For full-screen error states
 * pass `Modifier.fillMaxSize()`.
 *
 * The title is announced as a polite live region so accessibility services
 * communicate the error without requiring the user to focus the composable
 * explicitly.
 *
 * @param title Short headline describing what failed (e.g. "Couldn't load accounts").
 * @param body Optional secondary text with context or recovery advice.
 * @param icon Optional decorative icon; defaults to [Icons.Filled.ErrorOutline].
 * @param retryLabel Label for the retry button. Pass `null` to omit the button.
 * @param onRetry Click handler for the retry button; ignored when [retryLabel] is `null`.
 */
@Composable
fun ErrorState(
    title: String,
    body: String? = null,
    icon: ImageVector? = Icons.Filled.ErrorOutline,
    retryLabel: String? = stringResource(R.string.error_state_retry),
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = title
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(24.dp)
                    .widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
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
            if (retryLabel != null && onRetry != null) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(retryLabel)
                }
            }
        }
    }
}

@Preview(name = "ErrorState — with retry", showBackground = true)
@Composable
private fun ErrorStateRetryPreview() {
    SynckroTheme {
        ErrorState(
            title = "Couldn't load accounts",
            body = "Check your internet connection and try again.",
            onRetry = {},
        )
    }
}

@Preview(
    name = "ErrorState — with retry (dark)",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ErrorStateRetryDarkPreview() {
    SynckroTheme {
        ErrorState(
            title = "Couldn't load accounts",
            body = "Check your internet connection and try again.",
            onRetry = {},
        )
    }
}

@Preview(name = "ErrorState — no retry", showBackground = true)
@Composable
private fun ErrorStateNoRetryPreview() {
    SynckroTheme {
        ErrorState(
            title = "Something went wrong",
            retryLabel = null,
        )
    }
}
