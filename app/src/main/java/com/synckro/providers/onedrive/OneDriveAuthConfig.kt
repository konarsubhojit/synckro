package com.synckro.providers.onedrive

/**
 * Pure-Kotlin helper that validates MSAL configuration before any Android SDK
 * is touched. Kept framework-free so it can be unit-tested without Robolectric.
 *
 * The same rules are enforced at build time by `app/build.gradle.kts`, but
 * having an explicit runtime check lets [OneDriveAuthManager] return a clear
 * [com.synckro.domain.auth.AuthResult.NotConfigured] instead of
 * a confusing MSAL stack trace when the app is installed from a CI artifact
 * built without auth secrets.
 */
object OneDriveAuthConfig {
    /**
     * Outcome of a config validation.
     *
     * - [Valid] — both values are present and structurally sound; MSAL can proceed.
     * - [NotConfigured] — both values are blank; MSAL is intentionally disabled in
     *   this build. Short-circuit with [AuthResult.NotConfigured] and log a
     *   single warning.
     * - [Invalid] — exactly one value is set, or the redirect URI has a
     *   recognisably wrong format. [reason] carries a developer-readable
     *   explanation pointing at `docs/login-setup.md`.
     */
    sealed interface ValidationResult {
        data object Valid : ValidationResult

        data object NotConfigured : ValidationResult

        data class Invalid(
            val reason: String,
        ) : ValidationResult
    }

    /**
     * Validates the MSAL [clientId] / [redirectUri] pair.
     *
     * Rules (in evaluation order):
     * 1. Both blank → [ValidationResult.NotConfigured]
     * 2. Only [clientId] blank → [ValidationResult.Invalid]
     * 3. Only [redirectUri] blank → [ValidationResult.Invalid]
     * 4. [redirectUri] does not start with `msauth://` → [ValidationResult.Invalid]
     * 5. Otherwise → [ValidationResult.Valid]
     */
    fun validate(clientId: String, redirectUri: String): ValidationResult {
        val idBlank = clientId.isBlank()
        val uriBlank = redirectUri.isBlank()
        return when {
            idBlank && uriBlank -> ValidationResult.NotConfigured
            idBlank ->
                ValidationResult.Invalid(
                    "MS_CLIENT_ID is not set but MSAL_REDIRECT_URI is. " +
                        "Both must be provided together. See docs/login-setup.md.",
                )
            uriBlank ->
                ValidationResult.Invalid(
                    "MSAL_REDIRECT_URI is not set but MS_CLIENT_ID is. " +
                        "Both must be provided together. See docs/login-setup.md.",
                )
            !redirectUri.startsWith("msauth://") ->
                ValidationResult.Invalid(
                    "MSAL_REDIRECT_URI must start with 'msauth://'. " +
                        "Got: '$redirectUri'. See docs/login-setup.md.",
                )
            else -> ValidationResult.Valid
        }
    }
}
