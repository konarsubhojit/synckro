package com.konarsubhojit.synckro.domain.auth

import com.konarsubhojit.synckro.domain.model.CloudProviderType

/**
 * An authenticated user account known to Synckro. This is the non-secret
 * metadata we persist; refresh tokens themselves stay inside the provider
 * SDK's own encrypted storage (MSAL token cache, Google credential store).
 */
data class Account(
    val id: String,
    val provider: CloudProviderType,
    val displayName: String,
    val email: String?,
)
