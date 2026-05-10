package com.synckro.domain.auth

import com.synckro.domain.model.CloudProviderType

/**
 * Identifies a single account in a provider-aware way.
 *
 * Used by sync-pair queries that need to surface "needs re-authentication"
 * (or similar) signals at account granularity instead of provider granularity,
 * now that a single provider may host multiple accounts.
 *
 * [accountId] is nullable because a [com.synckro.domain.model.SyncPair] can be
 * orphaned from its account (e.g. after the user disconnects the account but
 * keeps the pair). Such pairs still belong to a [provider] and need to be
 * grouped/displayed together with their provider's other accounts.
 */
data class AccountKey(
    val provider: CloudProviderType,
    val accountId: String?,
)
