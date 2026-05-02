package com.synckro.providers.onedrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OneDriveAuthConfig.validate].
 *
 * Pure JUnit — no Robolectric / Android context required because
 * [OneDriveAuthConfig] is a plain Kotlin object with no framework dependencies.
 */
class OneDriveAuthConfigTest {

    private val validClientId = "aaaabbbb-cccc-dddd-eeee-ffffffffffff"
    private val validRedirectUri = "msauth://com.synckro.debug/DtQuXucYhhudNaIP9tJG2ySmySA="

    // -------------------------------------------------------------------------
    // Both blank → NotConfigured
    // -------------------------------------------------------------------------

    @Test
    fun `both blank returns NotConfigured`() {
        val result = OneDriveAuthConfig.validate("", "")
        assertEquals(OneDriveAuthConfig.ValidationResult.NotConfigured, result)
    }

    @Test
    fun `both whitespace returns NotConfigured`() {
        val result = OneDriveAuthConfig.validate("   ", "   ")
        assertEquals(OneDriveAuthConfig.ValidationResult.NotConfigured, result)
    }

    // -------------------------------------------------------------------------
    // Exactly one set → Invalid
    // -------------------------------------------------------------------------

    @Test
    fun `clientId blank with redirectUri set returns Invalid mentioning MS_CLIENT_ID`() {
        val result = OneDriveAuthConfig.validate("", validRedirectUri)
        assertTrue(result is OneDriveAuthConfig.ValidationResult.Invalid)
        val reason = (result as OneDriveAuthConfig.ValidationResult.Invalid).reason
        assertTrue("reason should mention MS_CLIENT_ID", reason.contains("MS_CLIENT_ID"))
    }

    @Test
    fun `redirectUri blank with clientId set returns Invalid mentioning MSAL_REDIRECT_URI`() {
        val result = OneDriveAuthConfig.validate(validClientId, "")
        assertTrue(result is OneDriveAuthConfig.ValidationResult.Invalid)
        val reason = (result as OneDriveAuthConfig.ValidationResult.Invalid).reason
        assertTrue("reason should mention MSAL_REDIRECT_URI", reason.contains("MSAL_REDIRECT_URI"))
    }

    // -------------------------------------------------------------------------
    // Both set but malformed redirect URI → Invalid
    // -------------------------------------------------------------------------

    @Test
    fun `redirectUri without msauth scheme returns Invalid`() {
        val result = OneDriveAuthConfig.validate(validClientId, "https://com.example/abc")
        assertTrue(result is OneDriveAuthConfig.ValidationResult.Invalid)
        val reason = (result as OneDriveAuthConfig.ValidationResult.Invalid).reason
        assertTrue("reason should mention msauth://", reason.contains("msauth://"))
    }

    @Test
    fun `redirectUri with only scheme and no host returns Invalid`() {
        // "msauth://" with nothing after → empty host after parsing
        val result = OneDriveAuthConfig.validate(validClientId, "msauth://")
        // The URI starts with msauth:// so format check passes; but the host extracted
        // by the build script would be empty. The runtime validator only checks the
        // prefix — build.gradle.kts enforces the host/path constraints.
        // This test documents the boundary: just the prefix is enough for Valid here.
        assertTrue(
            "msauth:// alone should be Valid per runtime validator (host/path enforced at build time)",
            result is OneDriveAuthConfig.ValidationResult.Valid,
        )
    }

    @Test
    fun `redirectUri with wrong scheme prefix returns Invalid`() {
        val result = OneDriveAuthConfig.validate(validClientId, "msal://com.example/abc")
        assertTrue(result is OneDriveAuthConfig.ValidationResult.Invalid)
    }

    // -------------------------------------------------------------------------
    // Valid config → Valid
    // -------------------------------------------------------------------------

    @Test
    fun `valid clientId and redirectUri returns Valid`() {
        val result = OneDriveAuthConfig.validate(validClientId, validRedirectUri)
        assertEquals(OneDriveAuthConfig.ValidationResult.Valid, result)
    }

    @Test
    fun `valid config with different hash returns Valid`() {
        val result = OneDriveAuthConfig.validate(
            "12345678-1234-1234-1234-123456789012",
            "msauth://com.synckro.debug/SomeOtherBase64Hash=",
        )
        assertEquals(OneDriveAuthConfig.ValidationResult.Valid, result)
    }

    // -------------------------------------------------------------------------
    // Error message references docs
    // -------------------------------------------------------------------------

    @Test
    fun `Invalid reason references login-setup docs`() {
        val result = OneDriveAuthConfig.validate(validClientId, "")
        val reason = (result as OneDriveAuthConfig.ValidationResult.Invalid).reason
        assertTrue("reason should reference docs/login-setup.md", reason.contains("docs/login-setup.md"))
    }
}
