package com.harmony.core.dsp

import java.io.Closeable
import java.util.Locale

/**
 * Number of features per track: [Energy, Acousticness, Normalized_BPM,
 * Valence, Danceability]. The native side carries the dimension with the data,
 * so adding a feature here is a one-line change plus a re-analysis pass.
 */
const val SHUFFLE_FEATURE_DIM = 5

// ---------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------

/**
 * A candidate track.
 *
 * Not a `data class` on purpose: `features` is a FloatArray, and a generated
 * `equals` would compare it by identity, giving two tracks with the same id and
 * the same numbers a false "not equal". Identity here is the track id and
 * nothing else.
 *
 * @param features length must be [SHUFFLE_FEATURE_DIM], each value normalized
 *   to [0, 1]. Values outside that range still work — the metric is just
 *   Euclidean — but the penalty calibration assumes a unit cube.
 * @param lastPlayedAtMs epoch millis, or 0 for "never played".
 */
class ShuffleTrack(
    val id: Long,
    val artist: String,
    val features: FloatArray,
    val lastPlayedAtMs: Long = 0L,
) {
    override fun equals(other: Any?): Boolean = other is ShuffleTrack && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "ShuffleTrack(id=$id, artist=$artist)"
}

/** The currently playing track, used as the origin for the distance search. */
class ShuffleSeed(
    val id: Long,
    val artist: String,
    val features: FloatArray,
)

/**
 * Tunables. Defaults match the native defaults, which implement the product
 * spec; see the C++ header for the reasoning behind [penaltyFloorFraction].
 */
data class ShuffleConfig(
    /** 0.5 == the spec's "50% distance penalty" for the same artist. */
    val sameArtistPenalty: Float = 0.50f,
    /** Peak penalty for a track played this instant, decaying to 0 across the window. */
    val recentPlayPenalty: Float = 0.75f,
    /**
     * Additive penalty as a fraction of the feature-space diagonal. This is
     * the knob that decides whether the fatigue rules actually change
     * behaviour or merely change numbers — raise it if Smart Shuffle still
     * feels artist-heavy, lower it if it feels like it is avoiding the
     * library's best matches.
     */
    val penaltyFloorFraction: Float = 0.15f,
    /** Size of the random-walk pool. */
    val topK: Int = 5,
    /** Fatigue window; the spec's 2 hours. */
    val recencyWindowMs: Long = 2 * 60 * 60 * 1000L,
    /**
     * Optional per-dimension weights, length [SHUFFLE_FEATURE_DIM].
     * Null means uniform. Weights multiply the squared term, so 4.0 doubles
     * that feature's influence on the final distance.
     */
    val dimensionWeights: FloatArray? = null,
) {
    // FloatArray again — compare by content so config changes are detectable.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShuffleConfig) return false
        return sameArtistPenalty == other.sameArtistPenalty &&
            recentPlayPenalty == other.recentPlayPenalty &&
            penaltyFloorFraction == other.penaltyFloorFraction &&
            topK == other.topK &&
            recencyWindowMs == other.recencyWindowMs &&
            dimensionWeights.contentEquals(other.dimensionWeights)
    }

    override fun hashCode(): Int {
        var h = sameArtistPenalty.hashCode()
        h = 31 * h + recentPlayPenalty.hashCode()
        h = 31 * h + penaltyFloorFraction.hashCode()
        h = 31 * h + topK
        h = 31 * h + recencyWindowMs.hashCode()
        h = 31 * h + (dimensionWeights?.contentHashCode() ?: 0)
        return h
    }
}

/**
 * The result. [consideredCount] and [poolSize] are diagnostics — when a user
 * reports "it keeps playing the same three songs", a poolSize of 3 is the
 * answer, and it is worth logging.
 */
data class ShuffleChoice(
    val track: ShuffleTrack,
    val adjustedDistance: Float,
    val consideredCount: Int,
    val poolSize: Int,
)

// ---------------------------------------------------------------------------
// Interface
// ---------------------------------------------------------------------------

/**
 * Bridge between the UI/domain layer and whichever selector is in use.
 *
 * Written as an interface so the existing Kotlin `SmartShuffleEngine` can
 * implement it too: if the native library fails to load on some device, the
 * caller swaps implementations instead of losing the feature.
 *
 * Lifecycle: [setLibrary] once per library change, [markPlayed] on each
 * completed track, [selectNext] on each track change. Implementations are
 * expected to be thread-safe.
 */
interface SmartShuffleSelector : Closeable {

    /** Replaces the candidate pool. Returns the number of accepted tracks. */
    fun setLibrary(tracks: List<ShuffleTrack>): Int

    /** Updates one track's play time without re-uploading the library. */
    fun markPlayed(trackId: Long, atMs: Long = System.currentTimeMillis()): Boolean

    /**
     * Picks the next track, or null when nothing is selectable — an empty
     * library, or one holding only the seed. Callers must handle null rather
     * than assuming a track always comes back.
     */
    fun selectNext(
        seed: ShuffleSeed,
        nowMs: Long = System.currentTimeMillis(),
        rngSeed: Long = System.nanoTime(),
    ): ShuffleChoice?
}

// ---------------------------------------------------------------------------
// Native implementation
// ---------------------------------------------------------------------------

/**
 * Native-backed selector.
 *
 * Holds a native session for its lifetime, so the library crosses JNI once
 * rather than on every track change. **Must be closed**, or the native
 * Session leaks; scope it to the playback service, not to a screen.
 */
class NativeSmartShuffle(
    config: ShuffleConfig = ShuffleConfig(),
    private val dim: Int = SHUFFLE_FEATURE_DIM,
) : SmartShuffleSelector {

    private val lock = Any()

    /** Zeroed by [close] so a late call fails cleanly instead of jumping into freed memory. */
    private var handle: Long = 0L

    /** Row index -> track, for mapping the native result back to a domain object. */
    private var tracks: List<ShuffleTrack> = emptyList()

    /**
     * Artist name -> dense int id.
     *
     * Interning matters: without it the native layer would have to compare
     * UTF-8 strings across JNI for every candidate on every track change.
     * With it, artist fatigue is a single integer compare in the inner loop.
     */
    private var artistIds: Map<String, Int> = emptyMap()

    var config: ShuffleConfig = config
        set(value) {
            field = value
            synchronized(lock) { if (handle != 0L) pushConfig(value) }
        }

    init {
        check(dim > 0) { "Feature dimension must be positive" }
        if (available) {
            handle = nativeCreate(dim)
            if (handle != 0L) pushConfig(config)
        }
    }

    /** False if the native library is missing or the session could not be created. */
    val isUsable: Boolean get() = handle != 0L

    override fun setLibrary(tracks: List<ShuffleTrack>): Int = synchronized(lock) {
        if (handle == 0L) return 0

        // Reject malformed rows here rather than letting them reach the
        // scoring loop: a short feature array is an out-of-bounds read, not a
        // bad recommendation.
        val valid = tracks.filter { it.features.size == dim }

        val features = FloatArray(valid.size * dim)
        val ids = LongArray(valid.size)
        val artists = IntArray(valid.size)
        val played = LongArray(valid.size)
        val intern = HashMap<String, Int>()

        valid.forEachIndexed { i, track ->
            track.features.copyInto(features, i * dim)
            ids[i] = track.id
            // Normalized so "The Beatles" and "the beatles " are one artist.
            val key = track.artist.trim().lowercase(Locale.ROOT)
            artists[i] = intern.getOrPut(key) { intern.size }
            played[i] = track.lastPlayedAtMs
        }

        val accepted = nativeSetLibrary(handle, features, ids, artists, played)
        if (accepted < 0) {
            this.tracks = emptyList()
            this.artistIds = emptyMap()
            return 0
        }
        this.tracks = valid
        this.artistIds = intern
        return accepted
    }

    override fun markPlayed(trackId: Long, atMs: Long): Boolean = synchronized(lock) {
        if (handle == 0L) false else nativeMarkPlayed(handle, trackId, atMs)
    }

    override fun selectNext(seed: ShuffleSeed, nowMs: Long, rngSeed: Long): ShuffleChoice? =
        synchronized(lock) {
            if (handle == 0L || seed.features.size != dim || tracks.isEmpty()) return null

            // -1 when the seed's artist appears nowhere in the library, which
            // is exactly right: no candidate can trigger the same-artist rule.
            val seedArtist = artistIds[seed.artist.trim().lowercase(Locale.ROOT)] ?: -1

            val stats = FloatArray(3)
            val index = nativeSelectNext(
                handle, seed.features, seed.id, seedArtist, nowMs, rngSeed, stats,
            )
            if (index < 0 || index >= tracks.size) return null

            ShuffleChoice(
                track = tracks[index],
                adjustedDistance = stats[0],
                consideredCount = stats[1].toInt(),
                poolSize = stats[2].toInt(),
            )
        }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
        tracks = emptyList()
        artistIds = emptyMap()
    }

    private fun pushConfig(c: ShuffleConfig) {
        nativeSetConfig(
            handle,
            c.sameArtistPenalty,
            c.recentPlayPenalty,
            c.penaltyFloorFraction,
            c.topK,
            c.recencyWindowMs,
            c.dimensionWeights?.takeIf { it.size == dim },
        )
    }

    private external fun nativeCreate(dim: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetLibrary(
        handle: Long,
        features: FloatArray,
        trackIds: LongArray,
        artistIds: IntArray,
        lastPlayedMs: LongArray,
    ): Int
    private external fun nativeSetConfig(
        handle: Long,
        sameArtistPenalty: Float,
        recentPlayPenalty: Float,
        penaltyFloorFraction: Float,
        topK: Int,
        recencyWindowMs: Long,
        dimWeights: FloatArray?,
    )
    private external fun nativeMarkPlayed(handle: Long, trackId: Long, atMs: Long): Boolean
    private external fun nativeSelectNext(
        handle: Long,
        seedFeatures: FloatArray,
        seedTrackId: Long,
        seedArtistId: Int,
        nowMs: Long,
        rngSeed: Long,
        outStats: FloatArray?,
    ): Int

    companion object {
        /**
         * False when the .so is absent for this ABI. Callers should check it
         * and fall back to the Kotlin engine rather than crashing — the
         * library is built for arm64-v8a and x86_64 only.
         */
        val available: Boolean = try {
            System.loadLibrary("harmonyshuffle")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
