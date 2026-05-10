package com.synckro.providers.onedrive

/**
 * Provider-specific startup probe used during the MSAL single-account →
 * multi-account migration. Implementations should attempt a lightweight cache
 * read and return [Result.failure] when the underlying auth SDK throws.
 */
interface OneDriveCacheCompatibilityChecker {
    suspend fun probeMultiAccountCacheRead(): Result<Unit>
}
