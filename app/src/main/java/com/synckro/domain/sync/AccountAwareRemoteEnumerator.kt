package com.synckro.domain.sync

/**
 * Optional extension for [RemoteEnumerator] implementations that need an
 * explicit account context.
 */
interface AccountAwareRemoteEnumerator {
    suspend fun enumerateForAccount(
        accountId: String,
        deltaToken: String?,
        rootFolderId: String = "",
    ): RemoteSnapshot

    suspend fun enumerateFullForAccount(
        accountId: String,
        rootFolderId: String = "",
    ): RemoteSnapshot = enumerateForAccount(accountId, null, rootFolderId)
}
