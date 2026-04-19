package com.konarsubhojit.synckro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.os.Build

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * Applies the app's Material 3 color scheme to the provided composable content, choosing between
 * light, dark, or Android 12+ dynamic system palettes based on the provided flags and device support.
 *
 * When `dynamicColor` is true and the device runs Android 12 or newer, a dynamic light or dark
 * color scheme is obtained from the system; otherwise the statically defined `LightColors` or
 * `DarkColors` are used. The selected scheme is passed to `MaterialTheme`.
 *
 * @param darkTheme If `true`, prefer a dark color scheme; defaults to the system dark/light setting.
 * @param dynamicColor If `true`, allow using Android 12+ dynamic system colors when supported.
 * @param content Composable content that will be wrapped with the selected `MaterialTheme`.
 */
@Composable
fun SynckroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
