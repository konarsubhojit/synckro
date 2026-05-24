package com.synckro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.synckro.ui.theme.SynckroTheme

/**
 * Standard tokens shared by every [SectionCard] in the app. Phase 2 of the UX
 * initiative pulls padding/shape/elevation values into one place so subsequent
 * phases can iterate against a single visual baseline.
 */
object SectionCardDefaults {
    /** Default content padding inside a [SectionCard]. */
    val ContentPadding: Dp = 16.dp

    /** Default vertical spacing between children stacked inside a [SectionCard]. */
    val ContentSpacing: Dp = 12.dp

    /** Default tonal elevation. Kept at 0 so cards inherit the surface tint by colour. */
    val TonalElevation: Dp = 0.dp
}

/**
 * Standardised card-like surface used to group related content across the app
 * (pair list rows, account-provider rows, intro banners, …).
 *
 * Wraps a Material 3 [Surface] with a [Column] body so callers can stack
 * children using a shared spacing token. All visual tokens
 * (`containerColor`, `shape`, `contentPadding`, `verticalArrangement`,
 * `tonalElevation`) have sensible defaults pulled from
 * [SectionCardDefaults] but can be overridden where a specific screen needs
 * to preserve existing behaviour (for example error-tinted pair cards).
 *
 * @param modifier Modifier applied to the outer surface. Callers usually
 *   add `fillMaxWidth()` here.
 * @param containerColor Background colour of the card.
 * @param contentColor Foreground colour propagated as `LocalContentColor` to
 *   the children. Defaults to the standard [contentColorFor] mapping of
 *   [containerColor].
 * @param shape Card shape. Defaults to [MaterialTheme.shapes.medium].
 * @param tonalElevation Tonal elevation forwarded to the underlying [Surface].
 * @param contentPadding Padding applied between the card edges and the
 *   stacked children.
 * @param verticalArrangement Vertical arrangement used by the inner [Column].
 *   Defaults to [Arrangement.spacedBy] with [SectionCardDefaults.ContentSpacing].
 * @param content Children stacked inside the card. The receiver is a
 *   [ColumnScope] so callers can use `weight`, `align`, etc.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = contentColorFor(containerColor),
    shape: Shape = MaterialTheme.shapes.medium,
    tonalElevation: Dp = SectionCardDefaults.TonalElevation,
    contentPadding: PaddingValues = PaddingValues(SectionCardDefaults.ContentPadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(SectionCardDefaults.ContentSpacing),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

@Preview(name = "SectionCard — default", showBackground = true)
@Preview(
    name = "SectionCard — default (dark)",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SectionCardDefaultPreview() {
    SynckroTheme {
        SectionCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Section title", style = MaterialTheme.typography.titleMedium)
            Text(
                "Supporting body text describing the section contents.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(name = "SectionCard — tinted", showBackground = true)
@Composable
private fun SectionCardTintedPreview() {
    SynckroTheme {
        SectionCard(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text("Heads up", style = MaterialTheme.typography.titleMedium)
            Text(
                "A tinted section card used for inline banners.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
