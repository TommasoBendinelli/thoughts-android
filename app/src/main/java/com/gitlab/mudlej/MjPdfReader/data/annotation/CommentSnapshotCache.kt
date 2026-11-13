// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Rendering reads the last completed snapshot without waiting for database work. */
internal class CommentSnapshotCache(
    private val loader: suspend () -> CommentCatalogueSnapshot,
) {
    private val stateLock = Any()
    private val loadMutex = Mutex()
    private var generation = 0L
    private var loadedGeneration = -1L
    @Volatile private var snapshot = CommentCatalogueSnapshot()

    fun current(): CommentCatalogueSnapshot = snapshot

    fun invalidate() {
        synchronized(stateLock) { generation++ }
    }

    suspend fun load(): CommentCatalogueSnapshot = withContext(Dispatchers.IO) {
        loadMutex.withLock { loadCurrentGeneration() }
    }

    private suspend fun loadCurrentGeneration(): CommentCatalogueSnapshot {
        while (true) {
            val requestedGeneration = synchronized(stateLock) {
                if (loadedGeneration == generation) return snapshot
                generation
            }
            val loaded = loader()
            synchronized(stateLock) {
                // A mutation during loading must not mark an older result as current.
                if (generation == requestedGeneration) {
                    snapshot = loaded
                    loadedGeneration = requestedGeneration
                    return loaded
                }
            }
        }
    }
}
