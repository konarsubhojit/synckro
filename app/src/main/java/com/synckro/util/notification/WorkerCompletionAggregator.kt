package com.synckro.util.notification

import com.synckro.domain.model.SyncPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerCompletionAggregator {
    private val scope: CoroutineScope
    private val windowMs: Long
    private val mutex = Mutex()
    private val pending = linkedMapOf<Long, Completion>()
    private var flushJob: Job? = null

    @Inject
    constructor() : this(CoroutineScope(Dispatchers.IO + SupervisorJob()), DEFAULT_WINDOW_MS)

    internal constructor(
        scope: CoroutineScope,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ) {
        this.scope = scope
        this.windowMs = windowMs
    }

    data class Completion(
        val pair: SyncPair,
        val transferredFiles: Int,
    )

    suspend fun record(
        pair: SyncPair,
        transferredFiles: Int,
        onFlush: suspend (List<Completion>) -> Unit,
    ) {
        require(transferredFiles >= 0) { "transferredFiles must be >= 0" }
        mutex.withLock {
            val existing = pending[pair.id]
            pending[pair.id] =
                if (existing == null) {
                    Completion(pair = pair, transferredFiles = transferredFiles)
                } else {
                    existing.copy(pair = pair, transferredFiles = existing.transferredFiles + transferredFiles)
                }
            if (flushJob?.isActive != true) {
                flushJob =
                    scope.launch {
                        delay(windowMs)
                        val completions =
                            mutex.withLock {
                                flushJob = null
                                pending.values.toList().also { pending.clear() }
                            }
                        if (completions.isNotEmpty()) {
                            onFlush(completions)
                        }
                    }
            }
        }
    }

    companion object {
        internal const val DEFAULT_WINDOW_MS = 5_000L
    }
}
