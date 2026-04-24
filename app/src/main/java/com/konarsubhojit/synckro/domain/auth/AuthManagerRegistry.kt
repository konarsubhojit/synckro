package com.konarsubhojit.synckro.domain.auth

import com.konarsubhojit.synckro.domain.model.CloudProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry of per-provider [AuthManager]s. Screens that need to offer
 * "Connect X" actions iterate [all]; code that already knows which provider
 * it needs looks it up via [get].
 */
@Singleton
class AuthManagerRegistry @Inject constructor(
    private val managers: Map<CloudProviderType, @JvmSuppressWildcards AuthManager>,
) {
    /**
     * Registered managers in a deterministic order (by [CloudProviderType]
     * declaration order). Hilt's multibound map has no guaranteed iteration
     * order, so we sort explicitly to keep the Accounts screen layout stable.
     */
    val all: List<AuthManager>
        get() = managers.entries
            .sortedBy { it.key.ordinal }
            .map { it.value }

    fun get(type: CloudProviderType): AuthManager =
        managers[type] ?: error("No AuthManager registered for $type")
}
