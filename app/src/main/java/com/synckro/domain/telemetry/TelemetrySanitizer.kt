package com.synckro.domain.telemetry

/**
 * Enforces the telemetry privacy contract described on [Telemetry]: no file
 * names, folder names, file paths, account identifiers, email addresses, or
 * tokens may ever reach Crashlytics/Analytics.
 *
 * This is intentionally pure Kotlin (no Android/Firebase types) so it can be
 * unit-tested directly and shared by every [Telemetry] implementation.
 */
object TelemetrySanitizer {
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    // Matches anything that looks like a filesystem/content path or URI:
    // forward or back slashes, or a "scheme://" prefix such as content:// or
    // file://. Legitimate telemetry values (enum labels, bucket strings,
    // counts) never need either character.
    private val PATH_LIKE_REGEX = Regex("""[\\/]|[A-Za-z][A-Za-z0-9+.-]*://""")

    // Matches OAuth token shapes that must never reach Crashlytics/Analytics:
    //  - JWT-style access/id tokens: three dot-separated base64url segments.
    //  - Google OAuth access tokens ("ya29....") and refresh tokens ("1//...").
    //  - A literal "******" Authorization header value.
    // Legitimate telemetry values (enum labels, bucket strings, counts) never
    // match any of these shapes.
    private val TOKEN_LIKE_REGEX =
        Regex(
            """eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""" +
                """|\bya29\.[A-Za-z0-9_-]+""" +
                """|\b1//[A-Za-z0-9_-]+""" +
                """|(?i)\bbearer\s+[A-Za-z0-9._-]{10,}""",
        )

    /** Placeholder substituted for any value that fails the privacy check. */
    const val REDACTED = "[redacted]"

    /** Returns `true` when [value] looks like it might contain an email address. */
    fun looksLikeEmail(value: String): Boolean = EMAIL_REGEX.containsMatchIn(value)

    /** Returns `true` when [value] looks like it might contain a file path or URI. */
    fun looksLikePath(value: String): Boolean = PATH_LIKE_REGEX.containsMatchIn(value)

    /** Returns `true` when [value] looks like it might contain an OAuth access/refresh token. */
    fun looksLikeToken(value: String): Boolean = TOKEN_LIKE_REGEX.containsMatchIn(value)

    /** Returns `true` when [value] is safe to send to Crashlytics/Analytics as-is. */
    fun isSafe(value: String): Boolean = !looksLikeEmail(value) && !looksLikePath(value) && !looksLikeToken(value)

    /**
     * Returns [value] unchanged if it passes the privacy contract, otherwise
     * returns [REDACTED]. Never throws — call sites forward whatever comes
     * back straight to Firebase.
     */
    fun sanitize(value: String): String = if (isSafe(value)) value else REDACTED

    /** Sanitizes every value in [params], preserving keys. */
    fun sanitizeParams(params: Map<String, String>): Map<String, String> =
        params.mapValues { (_, value) -> sanitize(value) }
}
