package com.harmony.domain.online

/**
 * The narrow edge between Harmony and an online music catalog: find a song,
 * and get the radio that follows it. Everything Harmony needs from YouTube
 * Music goes through these two calls, so a new catalog version (or another
 * implementation) changes one class, not the queue.
 *
 * Implementations never throw for a failed request: they say what went
 * wrong in [CatalogResult.Failed]. Cancellation is the one exception (it
 * propagates, as for any coroutine).
 */
interface OnlineCatalog {
    /** Songs matching [query] (ytmusicapi `search(query, filter="songs")`), in the catalog's order. */
    suspend fun searchSongs(query: String, limit: Int = 10): CatalogResult<List<CatalogTrack>>

    /**
     * The radio that follows [videoId] (ytmusicapi `get_watch_playlist(videoId, radio=True)`),
     * in the catalog's order. The first track is usually [videoId] itself.
     */
    suspend fun radio(videoId: String, limit: Int = 25): CatalogResult<List<CatalogTrack>>
}

/**
 * What a result is, as the catalog types it (ytmusicapi's `videoType`). Only
 * [SONG] is a recording of a release; the rest are videos, uploads and
 * episodes, whatever their title says.
 */
enum class CatalogKind {
    /** `MUSIC_VIDEO_TYPE_ATV`: the audio track of a release. */
    SONG,

    /** `MUSIC_VIDEO_TYPE_OMV`: an official music video (often another edit, intro or outro). */
    OFFICIAL_VIDEO,

    /** `MUSIC_VIDEO_TYPE_UGC`: a user upload (lyric videos, re-uploads, sped-up edits). */
    USER_VIDEO,

    /** `MUSIC_VIDEO_TYPE_PODCAST_EPISODE`. */
    PODCAST_EPISODE,

    /** No type given, or one this version does not know. Never treated as a song. */
    UNKNOWN,
    ;

    companion object {
        fun of(videoType: String?): CatalogKind = when (videoType) {
            "MUSIC_VIDEO_TYPE_ATV" -> SONG
            "MUSIC_VIDEO_TYPE_OMV" -> OFFICIAL_VIDEO
            "MUSIC_VIDEO_TYPE_UGC" -> USER_VIDEO
            "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" -> PODCAST_EPISODE
            else -> UNKNOWN
        }
    }
}

/**
 * One result. Every field the catalog did not give is null, never guessed:
 * a null [durationMs], [explicit] or [playable] means "not known".
 */
data class CatalogTrack(
    /** YouTube's id, 11 characters of `[A-Za-z0-9_-]`. */
    val videoId: String,
    val title: String,
    /** Credited artists, lead first. */
    val artists: List<String>,
    val album: String?,
    val albumId: String?,
    val durationMs: Long?,
    val kind: CatalogKind,
    /** The catalog's own explicit mark; null when the answer does not carry it. */
    val explicit: Boolean?,
    /** False when the catalog greys the result out; null when it does not say. */
    val playable: Boolean?,
    val year: String? = null,
    /** The raw `videoType`, kept for diagnostics. */
    val videoType: String? = null,
) {
    val artist: String get() = artists.joinToString(", ")

    companion object {
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        fun isVideoId(value: String): Boolean = VIDEO_ID.matches(value)
    }
}

sealed interface CatalogResult<out T> {
    data class Ok<T>(val value: T) : CatalogResult<T>

    data class Failed(val failure: CatalogFailure) : CatalogResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok<T>)?.value
}

/** Why a catalog request gave nothing usable. Each one is handled differently. */
sealed interface CatalogFailure {
    /** No network, or the host could not be reached. Retried later. */
    data object Offline : CatalogFailure

    /** The request took longer than its deadline. Retried with backoff. */
    data object Timeout : CatalogFailure

    /** HTTP 429: the catalog asked to be called less. Nobody asks again for [retryAfterMs] (or a default pause). */
    data class RateLimited(val retryAfterMs: Long?) : CatalogFailure

    /** Another HTTP error. 5xx is retried; 4xx is not. */
    data class Http(val status: Int) : CatalogFailure {
        val retryable: Boolean get() = status >= 500
    }

    /** The answer did not have the structure this version reads: the catalog changed. Not retried. */
    data class SchemaChanged(val where: String) : CatalogFailure

    /** The online source is off (Offline mode, or the feature flag). No request was made. */
    data object Disabled : CatalogFailure
}
