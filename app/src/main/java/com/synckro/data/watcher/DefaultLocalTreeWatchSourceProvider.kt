package com.synckro.data.watcher

import com.synckro.data.local.dao.SyncPairDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Resolves opportunistic watch sources from the persisted sync-pair tree. */
class DefaultLocalTreeWatchSourceProvider(
    private val syncPairDao: SyncPairDao,
    private val resolver: LocalTreeWatchSourceResolver,
) : LocalTreeWatchSourceProvider {
    override fun sourceFor(pairId: Long): LocalTreeWatchSource =
        pair(pairId)?.localTreeUri?.takeIf { it.isNotBlank() }?.let(resolver::resolve)
            ?: LocalTreeWatchSource.Unsupported

    override fun excludeSubfolders(pairId: Long): Boolean = pair(pairId)?.excludeSubfolders == true

    private fun pair(pairId: Long) = runBlocking(Dispatchers.IO) { syncPairDao.getById(pairId) }
}
