package com.synckro.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.synckro.data.repository.DarkModePreference

private val LightColors =
    lightColorScheme(
        primary = BrandPrimary,
        onPrimary = BrandOnPrimary,
        primaryContainer = BrandPrimaryContainer,
        onPrimaryContainer = BrandOnPrimaryContainer,
        secondary = BrandSecondary,
        onSecondary = BrandOnSecondary,
        secondaryContainer = BrandSecondaryContainer,
        onSecondaryContainer = BrandOnSecondaryContainer,
        tertiary = BrandTertiary,
        onTertiary = BrandOnTertiary,
        tertiaryContainer = BrandTertiaryContainer,
        onTertiaryContainer = BrandOnTertiaryContainer,
        error = BrandError,
        onError = BrandOnError,
        errorContainer = BrandErrorContainer,
        onErrorContainer = BrandOnErrorContainer,
        background = LightBackground,
        onBackground = LightOnBackground,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = LightOutline,
    )

private val DarkColors =
    darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        primaryContainer = DarkPrimaryContainer,
        onPrimaryContainer = DarkOnPrimaryContainer,
        secondary = DarkSecondary,
        onSecondary = DarkOnSecondary,
        secondaryContainer = DarkSecondaryContainer,
        onSecondaryContainer = DarkOnSecondaryContainer,
        tertiary = DarkTertiary,
        onTertiary = DarkOnTertiary,
        tertiaryContainer = DarkTertiaryContainer,
        onTertiaryContainer = DarkOnTertiaryContainer,
        error = DarkErrorColor,
        onError = DarkOnErrorColor,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
        background = DarkBackground,
        onBackground = DarkOnBackground,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = DarkOutline,
    )

/**
 * Applies the app's Material 3 color scheme.
 *
 * Historically this defaulted to `dynamicColor = true`, which on Android 12+
 * pulled the scheme from the system wallpaper. On many devices this produced
 * very dark-on-dark combinations at night, rendering labels unreadable (the
 * "everything is black" bug reported by users). The explicit [LightColors] /
 * [DarkColors] defined above are tuned for WCAG AA contrast, so we default
 * `dynamicColor` to `false` and only opt in when the caller explicitly asks
 * for it (Settings → Appearance → "Use dynamic color").
 *
 * @param darkMode User preference (System / Light / Dark). `null` is treated as
 *   [DarkModePreference.SYSTEM] so unit-tested previews don't need to wire a
 *   repository value.
 * @param dynamicColor When `true` and running on Android 12+ the scheme is
 *   derived from the system wallpaper via [dynamicLightColorScheme] /
 *   [dynamicDarkColorScheme]. No-op on older versions.
 * @param respectFontScale When `false`, the composition is wrapped in a
 *   [LocalDensity] override that pins `fontScale = 1.0`, ignoring the system
 *   font-scale slider. Defaults to `true` so accessibility settings are
 *   respected unless the user explicitly opts out in Settings → Appearance.
 */
@Composable
fun SynckroTheme(
    darkMode: DarkModePreference = DarkModePreference.SYSTEM,
    dynamicColor: Boolean = false,
    respectFontScale: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme =
        when (darkMode) {
            DarkModePreference.SYSTEM -> systemDark
            DarkModePreference.LIGHT -> false
            DarkModePreference.DARK -> true
        }
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val ctx = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }
    val themed: @Composable () -> Unit = { MaterialTheme(colorScheme = colorScheme, content = content) }
    if (respectFontScale) {
        themed()
    } else {
        val current = LocalDensity.current
        val pinned = Density(density = current.density, fontScale = 1f)
        CompositionLocalProvider(LocalDensity provides pinned) { themed() }
    }
}
