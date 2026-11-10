package com.harmony.feature.downloads

/**
 * Who may use the native download clients right now.
 *
 * A single download (the Downloads screen, a Soulseek transfer) has the
 * engines to itself, as before. Album work may run several tracks at once
 * when the engine says it can (Mornye's runtime takes its provider per
 * request, so two downloads no longer change each other's route): callers
 * that are all album transfers share the gate, up to the smallest [run]
 * `parallel` among them. Anything else finds the gate busy.
 */
internal object DownloadEngineGate {
    private val lock = Any()
    private var holders = 0
    private var limit = 1

    @Volatile var albumOwnsTransfer: Boolean = false
        private set

    suspend fun <T> run(albumTransfer: Boolean = false, parallel: Int = 1, block: suspend () -> T): T {
        enter(albumTransfer, parallel.coerceAtLeast(1))
        return try { block() } finally { leave() }
    }

    private fun enter(albumTransfer: Boolean, parallel: Int) = synchronized(lock) {
        when {
            holders == 0 -> { holders = 1; limit = parallel; albumOwnsTransfer = albumTransfer }
            // Only album transfers stack, and only as far as every one of them allows.
            albumTransfer && albumOwnsTransfer && holders < minOf(limit, parallel) -> { holders++; limit = minOf(limit, parallel) }
            else -> throw DownloadEngineBusyException()
        }
    }

    private fun leave() = synchronized(lock) {
        holders--
        if (holders == 0) { albumOwnsTransfer = false; limit = 1 }
    }
}
class DownloadEngineBusyException : Exception("Another download is active. Wait for it to finish, then resume.")
