package com.konarsubhojit.synckro.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Synckro brand palette. Values are chosen so that text drawn with the matching
 * `onX` color always meets WCAG AA contrast against its container in both
 * light and dark schemes. Keep these in sync with [LightColors] / [DarkColors]
 * in `Theme.kt` and the launcher background in `res/values/colors.xml`.
 */

// Brand (indigo-ish blue inspired by the launcher background #0F172A).
internal val BrandPrimary = Color(0xFF3B5BDB)
internal val BrandOnPrimary = Color(0xFFFFFFFF)
internal val BrandPrimaryContainer = Color(0xFFDCE4FF)
internal val BrandOnPrimaryContainer = Color(0xFF001551)

internal val BrandSecondary = Color(0xFF4F5B76)
internal val BrandOnSecondary = Color(0xFFFFFFFF)
internal val BrandSecondaryContainer = Color(0xFFD7E2FF)
internal val BrandOnSecondaryContainer = Color(0xFF0C1B3A)

internal val BrandTertiary = Color(0xFF2E7D32)
internal val BrandOnTertiary = Color(0xFFFFFFFF)
internal val BrandTertiaryContainer = Color(0xFFB8F0B8)
internal val BrandOnTertiaryContainer = Color(0xFF002204)

internal val BrandError = Color(0xFFB3261E)
internal val BrandOnError = Color(0xFFFFFFFF)
internal val BrandErrorContainer = Color(0xFFF9DEDC)
internal val BrandOnErrorContainer = Color(0xFF410E0B)

// Light neutrals.
internal val LightBackground = Color(0xFFFDFBFF)
internal val LightOnBackground = Color(0xFF1A1B21)
internal val LightSurface = Color(0xFFFDFBFF)
internal val LightOnSurface = Color(0xFF1A1B21)
internal val LightSurfaceVariant = Color(0xFFE1E2EC)
internal val LightOnSurfaceVariant = Color(0xFF44474F)
internal val LightOutline = Color(0xFF74777F)

// Dark neutrals — these fix the "everything is black / unreadable" bug.
// The old theme used the default `darkColorScheme()`, which rendered fine in
// preview but combined with `enableEdgeToEdge()` and the Material DayNight
// parent theme produced surfaces and onSurface values that were both very dark
// on some devices. Explicit values below guarantee readable text.
internal val DarkBackground = Color(0xFF111318)
internal val DarkOnBackground = Color(0xFFE3E2E9)
internal val DarkSurface = Color(0xFF111318)
internal val DarkOnSurface = Color(0xFFE3E2E9)
internal val DarkSurfaceVariant = Color(0xFF44474F)
internal val DarkOnSurfaceVariant = Color(0xFFC4C6D0)
internal val DarkOutline = Color(0xFF8E9099)

internal val DarkPrimary = Color(0xFFB6C4FF)
internal val DarkOnPrimary = Color(0xFF002682)
internal val DarkPrimaryContainer = Color(0xFF1A3EB8)
internal val DarkOnPrimaryContainer = Color(0xFFDCE4FF)

internal val DarkSecondary = Color(0xFFBAC6EA)
internal val DarkOnSecondary = Color(0xFF23304C)
internal val DarkSecondaryContainer = Color(0xFF394663)
internal val DarkOnSecondaryContainer = Color(0xFFD7E2FF)

internal val DarkTertiary = Color(0xFF9DD49E)
internal val DarkOnTertiary = Color(0xFF003909)
internal val DarkTertiaryContainer = Color(0xFF105217)
internal val DarkOnTertiaryContainer = Color(0xFFB8F0B8)

internal val DarkErrorColor = Color(0xFFF2B8B5)
internal val DarkOnErrorColor = Color(0xFF601410)
internal val DarkErrorContainer = Color(0xFF8C1D18)
internal val DarkOnErrorContainer = Color(0xFFF9DEDC)
