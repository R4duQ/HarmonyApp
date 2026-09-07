package com.harmony.feature.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A download that is currently running, reduced to what a one-line banner needs. */
data class ActiveDownload(
    val title: String,
    /** "Soulseek", "SpotiFLAC", "File" — where it is coming from. */
    val source: String,
    /** Short status line, e.g. "Queue position 4" or "Converting". */
    val detail: String? = null,
    /** 0f..1f, or null when the size is not known yet (indeterminate). */
    val fraction: Float? = null,
    val albumId: String? = null,
)

/** A finished download, shown briefly and then dismissed. */
data class DownloadOutcome(
    val title: String,
    val succeeded: Boolean,
    val message: String? = null,
    /** Distinguishes two outcomes with the same title so the banner re-shows. */
    val id: Long = System.nanoTime(),
)

/**
 * App-scoped view of download activity.
 *
 * Exists because download state lives in workflow ViewModels, which are scoped
 * to their back-stack entries — the app shell cannot reach them, and
 * reaching into another destination's ViewModelStore would break the moment
 * Downloads has not been visited yet.
 *
 * This is a *mirror*, not a second source of truth: nothing here starts,
 * cancels or tracks a transfer. The active workflow remains the owner of the
 * download logic and pushes a reduced snapshot in. If that ViewModel is
 * cleared mid-transfer the job dies with it, which is why [clear] is
 * called from `onCleared` — a banner for a download that is no longer running
 * would be worse than no banner.
 */
@Singleton
class DownloadStatusCenter @Inject constructor() {
    private val owners = linkedMapOf<String, ActiveDownload>()

    /** A screen can only clear its own transfer. Album workers outlive navigation. */
    fun forOwner(album: Boolean = false): Publisher = Publisher(
        (if (album) "album:" else "screen:") + java.util.UUID.randomUUID())

    inner class Publisher internal constructor(private val owner: String) {
        fun publishActive(download: ActiveDownload?) = publish(owner, download)
        fun clear() = publish(owner, null)
        fun publishOutcome(title: String, succeeded: Boolean, message: String? = null) =
            finish(owner, title, succeeded, message)
    }

    @Synchronized private fun publish(owner: String, download: ActiveDownload?) {
        if (download != null && owner !in owners && owners.isEmpty()) _outcome.value = null
        if (download == null) owners.remove(owner) else owners[owner] = download
        _active.value = owners.entries.lastOrNull { it.key.startsWith("album:") }?.value
            ?: owners.values.lastOrNull()
    }

    @Synchronized private fun finish(owner: String, title: String, succeeded: Boolean, message: String?) {
        if (!owner.startsWith("album:") && owners.keys.any { it.startsWith("album:") }) return
        publishOutcome(title, succeeded, message)
    }

    private val _active = MutableStateFlow<ActiveDownload?>(null)

    /** Non-null while something is downloading. */
    val active: StateFlow<ActiveDownload?> = _active.asStateFlow()

    private val _outcome = MutableStateFlow<DownloadOutcome?>(null)

    /**
     * Set when a download finishes. The UI shows it, then calls
     * [consumeOutcome] — a StateFlow rather than a SharedFlow so a completion
     * that lands while the app is backgrounded is still there on return
     * instead of having been dropped into a collector that did not exist.
     */
    val outcome: StateFlow<DownloadOutcome?> = _outcome.asStateFlow()

    /** Mirrors the current in-flight download, or null when nothing is running. */
    fun publishActive(download: ActiveDownload?) {
        publish("legacy", download)
    }

    fun publishOutcome(title: String, succeeded: Boolean, message: String? = null) {
        if (title.isBlank()) return
        _outcome.value = DownloadOutcome(title = title, succeeded = succeeded, message = message)
    }

    fun consumeOutcome(id: Long? = _outcome.value?.id) {
        val current = _outcome.value
        if (current?.id == id) _outcome.compareAndSet(current, null)
    }

    /** Called when the owning ViewModel goes away and its jobs die with it. */
    fun clear() {
        publish("legacy", null)
    }
}
