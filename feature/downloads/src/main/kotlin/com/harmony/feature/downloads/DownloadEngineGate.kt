package com.harmony.feature.downloads

import kotlinx.coroutines.sync.Mutex

/** Native clients expose one active transfer each. Never allow callers to overwrite that state. */
internal object DownloadEngineGate {
    private val mutex = Mutex()
    @Volatile var albumOwnsTransfer: Boolean = false
        private set
    suspend fun <T> run(albumTransfer: Boolean = false, block: suspend () -> T): T {
        if (!mutex.tryLock()) throw DownloadEngineBusyException()
        albumOwnsTransfer = albumTransfer
        return try { block() } finally { albumOwnsTransfer = false; mutex.unlock() }
    }
}
class DownloadEngineBusyException : Exception("Another download is active. Wait for it to finish, then resume.")
