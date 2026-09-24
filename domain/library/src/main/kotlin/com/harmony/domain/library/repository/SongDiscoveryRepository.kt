package com.harmony.domain.library.repository

import com.harmony.core.model.Song
import com.harmony.domain.library.discovery.DiscoveryDraft
import com.harmony.domain.library.discovery.TasteFeedback
import com.harmony.domain.library.discovery.TrackIdentity
import kotlinx.coroutines.flow.StateFlow
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

data class DiscoveryFilter(
    val country: String = "", // ISO storefront, not the artist's nationality. Empty = random.
    val genre: String = "",
    val genreId: Int = 0,
    val vibe: String = "",
    val artistCountryOnly: Boolean = false,
)

data class DiscoverySong(
    val key: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val artwork: String = "",
    val preview: String = "",
    val link: String = "",
    val provider: String = "Library",
    val genres: Set<String> = emptySet(),
    val vibe: String = "",
    val market: String = "",
    val localUri: String? = null,
    val isrc: String? = null,
    val artistCountry: String = "",
) {
    val normalizedArtist = DiscoveryRules.normalize(artist)
    /** Cross-source identity: ISRC, then primary artist, base title, version and duration. */
    val track = TrackIdentity.of(title, artist, durationMs, isrc)
    val identity = track.key
}

data class DiscoveryVote(val song: DiscoverySong, val liked: Boolean)
data class DiscoveryBatch(
    val id: String,
    val name: String,
    val songs: List<DiscoverySong>,
    val uris: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val playlistId: Long? = null,
    val status: String = "Ready to download",
    val source: String = "SPOTIFLAC",
    val format: String = "FLAC_LOSSLESS",
    val verificationProvider: String? = null,
    /** Songs already put in the playlist once. Never re-added, so a removal by the user sticks. */
    val placedKeys: Set<String> = emptySet(),
    /** Why each song was chosen, shown on the review screen. */
    val reasons: Map<String, String> = emptyMap(),
    /** The song the download worker is on right now. */
    val activeKey: String? = null,
    /** The user deleted the playlist; downloads keep files but stop adding to it. */
    val playlistDeleted: Boolean = false,
    /** A download was queued or a playlist was made: the song list can no longer be edited. */
    val locked: Boolean = false,
)

/** When a song was last recommended, so the next sessions can prefer others. */
data class RecommendationStamp(val identity: String, val at: Long)

data class SongDiscoveryState(
    val filter: DiscoveryFilter = DiscoveryFilter(),
    val likes: List<DiscoverySong> = emptyList(),
    val votes: List<DiscoveryVote> = emptyList(),
    val batches: List<DiscoveryBatch> = emptyList(),
    val revealedBatch: String? = null,
    val undoKey: String? = null,
    /** The selection being built in the three-step flow. Survives restarts. */
    val draft: DiscoveryDraft? = null,
    val recommended: List<RecommendationStamp> = emptyList(),
    /** "Not interested": song keys, identities and ISRCs never offered again. */
    val declined: Set<String> = emptySet(),
    val feedback: List<TasteFeedback> = emptyList(),
)

interface SongDiscoveryRepository {
    val state: StateFlow<SongDiscoveryState>
    /** Serialized, durable update. A failed write must not advance the visible deck. */
    suspend fun update(change: (SongDiscoveryState) -> SongDiscoveryState)
}

/** Pure, deterministic policies shared by Discover, the worker and tests. */
object DiscoveryRules {
    const val TARGET = 50
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(marks, "").lowercase(Locale.ROOT).replace(separators, " ").trim()

    fun sameRecording(a: DiscoverySong, b: DiscoverySong): Boolean =
        a.key == b.key || TrackIdentity.same(a.track, b.track)

    fun fromLocal(s: Song) = DiscoverySong("local:${s.id}", s.title, s.artist, s.album, s.durationMs,
        s.artworkUri.orEmpty(), genres = s.genre?.let { setOf(it) }.orEmpty(), localUri = s.uri)

    /** Same recording by URI, or by identity with a known local duration (files carry no ISRC here). */
    fun localMatch(track: DiscoverySong, songs: List<Song>): Song? = songs.asSequence()
        .filter { track.localUri == it.uri || (it.durationMs > 0 &&
            TrackIdentity.same(track.track.copy(isrc = null), TrackIdentity.of(it.title, it.artist, it.durationMs))) }
        .sortedByDescending { it.uri == track.localUri }.firstOrNull()

    /** All songs of the batch, readable and indexed, in batch order; null while any is missing. */
    fun readyPlaylistIds(batch: DiscoveryBatch, library: List<Song>, readableUris: Set<String>): List<Long>? {
        if (batch.songs.isEmpty() || deduplicate(batch.songs).size != batch.songs.size) return null
        val byUri = library.associateBy { it.uri }
        val ids = batch.songs.map { song ->
            val uri = batch.uris[song.key]?.takeIf { it in readableUris } ?: return null
            byUri[uri]?.id ?: return null
        }
        return ids.takeIf { it.distinct().size == batch.songs.size }
    }

    fun vote(state: SongDiscoveryState, song: DiscoverySong, liked: Boolean, batchId: String, name: String): SongDiscoveryState {
        if (state.revealedBatch != null || state.likes.size >= TARGET ||
            state.votes.any { sameRecording(it.song, song) } || state.likes.any { sameRecording(it, song) }) return state
        val likes = if (liked) state.likes + song else state.likes
        val votes = (state.votes + DiscoveryVote(song, liked)).takeLast(5_000)
        return if (likes.size == TARGET) state.copy(likes = emptyList(), votes = votes, undoKey = null,
            batches = state.batches + DiscoveryBatch(batchId, name, likes), revealedBatch = batchId)
        else state.copy(likes = likes, votes = votes, undoKey = song.key)
    }

    fun undo(state: SongDiscoveryState): SongDiscoveryState {
        val last = state.votes.lastOrNull()?.takeIf { it.song.key == state.undoKey } ?: return state
        if (state.revealedBatch != null) return state
        return state.copy(votes = state.votes.dropLast(1), likes = state.likes.filterNot { it.key == last.song.key }, undoKey = null)
    }

    fun deduplicate(songs: List<DiscoverySong>): List<DiscoverySong> {
        val groups = HashMap<String, MutableList<DiscoverySong>>()
        val result = ArrayList<DiscoverySong>()
        val ids = HashSet<String>(); val isrcs = HashSet<String>()
        for (s in songs) {
            if (s.key in ids || (!s.isrc.isNullOrBlank() && s.isrc in isrcs)) continue
            val group = groups.getOrPut(s.identity) { mutableListOf() }
            if (group.none { sameRecording(it, s) }) {
                group += s; result += s; ids += s.key; s.isrc?.takeIf { it.isNotBlank() }?.let { isrcs += it }
            }
        }
        return result
    }

    fun genreMatches(song: DiscoverySong, genre: String): Boolean {
        if (genre.isBlank()) return true
        fun family(g: String): String = when (val n = normalize(g)) {
            "hip hop", "rap", "hip hop rap", "rap hip hop" -> "rap"
            "r b", "r b soul", "rnb", "rnb soul" -> "soul"
            else -> n
        }
        return song.genres.any { family(it) == family(genre) || normalize(it).contains(normalize(genre)) }
    }

    /** Bounded pool; learned artist/genre affinity + 30% exploration + source and artist diversity.
     * Market and vibe are retrieval hints, never assertions about nationality/audio analysis. */
    fun select(pool: List<DiscoverySong>, state: SongDiscoveryState, random: Random): DiscoverySong? {
        val seen = state.votes.groupBy { it.song.identity }
        val keys = state.votes.mapTo(HashSet()) { it.song.key }
        val isrcs = state.votes.mapNotNullTo(HashSet()) { it.song.isrc?.takeIf(String::isNotBlank) }
        val eligible = deduplicate(pool.take(800)).filter { song -> song.key !in keys &&
            (song.isrc.isNullOrBlank() || song.isrc !in isrcs) &&
            seen[song.identity].orEmpty().none { sameRecording(it.song, song) } &&
            state.likes.none { sameRecording(it, song) } && genreMatches(song, state.filter.genre) &&
            (!state.filter.artistCountryOnly || (song.artistCountry.isNotBlank() &&
                (state.filter.country.isBlank() || song.artistCountry == state.filter.country))) }
        if (eligible.isEmpty()) return null
        val recent = state.votes.takeLast(3).map { normalize(it.song.artist) }
        val diverse = eligible.filter { normalize(it.artist) !in recent }.ifEmpty { eligible }
        // Target approximately one local recording in four, without blocking an empty source.
        val wantLocal = state.votes.takeLast(4).count { it.song.localUri != null } == 0
        val balanced = diverse.filter { (it.localUri != null) == wantLocal }.ifEmpty { diverse }
        if (random.nextFloat() < .30f) return balanced.random(random)
        val artists = HashMap<String, Float>(); val genres = HashMap<String, Float>()
        state.votes.takeLast(500).forEachIndexed { i, vote ->
            val weight = (if (vote.liked) 1f else -.7f) * (.5f + i / 1_000f)
            val artist = normalize(vote.song.artist)
            artists[artist] = (artists[artist] ?: 0f) + weight
            vote.song.genres.forEach { genres[normalize(it)] = (genres[normalize(it)] ?: 0f) + weight }
        }
        return balanced.maxByOrNull { song ->
            (artists[normalize(song.artist)] ?: 0f).coerceIn(-3f, 3f) * .65f +
                song.genres.sumOf { (genres[normalize(it)] ?: 0f).coerceIn(-3f, 3f).toDouble() }.toFloat() * .35f +
                vibeAffinity(song, state.filter.vibe) +
                (if (state.filter.country.isNotBlank() && song.market == state.filter.country) 1f else 0f) + random.nextFloat() * .8f
        }
    }

    /** A transparent metadata heuristic, not inferred BPM, mood detection or an audio model. */
    fun vibeAffinity(song: DiscoverySong, vibe: String): Float {
        if (vibe.isBlank()) return 0f
        if (song.vibe == vibe) return 1.2f
        val tags = when (vibe) {
            "Chill" -> listOf("ambient", "jazz", "acoustic", "soul", "lo fi")
            "Focus" -> listOf("ambient", "classical", "instrumental", "lo fi")
            "Workout", "Energy" -> listOf("dance", "trap", "rap", "metal", "rock", "electronic")
            "Party" -> listOf("dance", "pop", "latin", "electronic", "disco")
            "Melancholic" -> listOf("blues", "acoustic", "singer songwriter")
            "Romantic" -> listOf("soul", "r b", "jazz", "pop")
            else -> emptyList()
        }
        return if (song.genres.any { genre -> tags.any { normalize(genre).contains(it) } }) .6f else 0f
    }
}
