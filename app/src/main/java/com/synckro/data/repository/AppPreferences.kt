package com.synckro.data.repository

/**
 * User-facing dark-mode preference. Stored as the enum's [name] in
 * [SettingsRepository].
 *
 * - [SYSTEM] follows `Configuration.UI_MODE_NIGHT_*` and is the default so the
 *   app respects the user's device-wide preference out of the box.
 * - [LIGHT] forces the light color scheme regardless of the system setting.
 * - [DARK] forces the dark color scheme regardless of the system setting.
 */
enum class DarkModePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * User-facing app language preference.
 *
 * - [SYSTEM] follows the device language.
 * - [ENGLISH] forces English strings where translations exist.
 */
enum class AppLanguagePreference(
    val languageTag: String?,
) {
    SYSTEM(null),
    ENGLISH("en"),
}

/**
 * Allowed log retention windows surfaced in the Settings screen. The stored
 * value is the integer number of days so the schema is forward-compatible with
 * additional presets without an enum migration.
 */
enum class LogRetentionPreference(
    val days: Int,
) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
    ;

    companion object {
        /** Maps an arbitrary stored day count to the nearest supported preset. */
        fun fromDays(days: Int): LogRetentionPreference =
            entries.firstOrNull { it.days == days } ?: THIRTY_DAYS
    }
}
