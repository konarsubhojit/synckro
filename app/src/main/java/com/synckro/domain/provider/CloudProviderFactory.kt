package com.synckro.domain.provider

/**
 * Creates account-scoped [CloudProvider] instances.
 *
 * Implementations may cache and reuse provider instances per [accountId].
 */
interface CloudProviderFactory {
    fun providerFor(accountId: String): CloudProvider
}
