package com.harmony.domain.online

import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.library.discovery.TrackVersion

/** A radio track that passed: its own score, why, and whether the library already has it. */
data class PlannedCandidate(
    val track: CatalogTrack,
    /** [TrackIdentity.key] of the recording, for repeats across sources. */
    val identityKey: String,
    /** Points, 0..100. */
    val score: Int,
    val reasons: List<String>,
)

/** A radio track turned away, and why (for the diagnostics, ids only). */
data class TurnedAway(val videoId: String, val reason: String)

data class PlannedBatch(val accepted: List<PlannedCandidate>, val turnedAway: List<TurnedAway>)

/**
 * What the session knows when a batch is planned.
 *
 * [avoidKeys]: identity keys of what must not come again automatically (the
 * queue as it is, the recent play history, this session's picks, what was
 * turned away). [skippedArtists]: lead artists of online songs the listener
 * skipped fast in this session.
 */
data class PlannerContext(
    val seed: SeedTrack,
    val seedVideoId: String,
    val avoidKeys: Set<String> = emptySet(),
    val avoidVideoIds: Set<String> = emptySet(),
    val skippedArtists: Set<String> = emptySet(),
)

/**
 * Turns the radio YouTube Music built from the seed into a batch Harmony can
 * stand behind. The catalog's order is its relevance; Harmony adds what it
 * knows about the session and what it wants to avoid. The online score is
 * separate from the 24-D one: the audio of an online song is not known
 * until it is prepared, so nothing acoustic is invented for it.
 *
 * Turned away outright:
 *  - anything but a [CatalogKind.SONG], and anything greyed out;
 *  - the seed itself, and any recording already in [PlannerContext.avoidKeys]
 *    (the queue, recent plays, this session) or twice in the batch;
 *  - sped up/slowed, karaoke and cover versions the seed isn't;
 *  - lengths under [MIN_DURATION_MS] (interludes, skits) or over
 *    [MAX_DURATION_MS] (DJ mixes, full albums).
 *
 * Scored (points, 0..100): the radio rank (up to 45), a known length (10),
 * a known album (5), an artist shared with the seed (10), a version the
 * seed isn't (live, remix… −25), an artist skipped fast this session (−30).
 * Below [THRESHOLD] it is turned away. When too few pass, the batch is
 * smaller: never padded with weak picks.
 *
 * Order: the catalog's, with no two songs of the same lead artist next to
 * each other when another order exists, and at most [PER_ARTIST] per batch.
 */
object OnlineMixPlanner {
    const val THRESHOLD = 50
    const val PER_ARTIST = 2
    const val MIN_DURATION_MS = 60_000L
    const val MAX_DURATION_MS = 15 * 60_000L

    private val NEVER_ALLOWED = setOf(TrackVersion.ALTERED, TrackVersion.KARAOKE, TrackVersion.COVER)

    fun plan(radio: List<CatalogTrack>, context: PlannerContext, want: Int): PlannedBatch {
        val turned = mutableListOf<TurnedAway>()
        val accepted = mutableListOf<PlannedCandidate>()
        val seenKeys = HashSet<String>()
        val (_, seedVersions) = TrackIdentity.parseTitle(context.seed.title)
        val seedArtists = SongMatcher.credits(context.seed.artist).toSet()
        val n = radio.size.coerceAtLeast(1)

        radio.forEachIndexed { index, track ->
            fun away(reason: String) { turned += TurnedAway(track.videoId, reason) }
            if (track.videoId == context.seedVideoId) return@forEachIndexed away("the seed")
            if (track.kind != CatalogKind.SONG) return@forEachIndexed away("not a song: ${track.kind}")
            if (track.playable == false) return@forEachIndexed away("not playable")
            if (track.videoId in context.avoidVideoIds) return@forEachIndexed away("already used")
            val identity = TrackIdentity.of(track.title, track.artists.firstOrNull().orEmpty(), track.durationMs ?: 0)
            if (identity.artist.isBlank() || identity.title.isBlank()) return@forEachIndexed away("no artist or title")
            if (identity.key in context.avoidKeys) return@forEachIndexed away("played, queued or picked already")
            if (!seenKeys.add(identity.key)) return@forEachIndexed away("twice in the radio")
            val never = (identity.versions intersect NEVER_ALLOWED) - seedVersions
            if (never.isNotEmpty()) return@forEachIndexed away("version: ${never.sorted().joinToString { it.name.lowercase() }}")
            val ms = track.durationMs
            if (ms != null && ms < MIN_DURATION_MS) return@forEachIndexed away("shorter than a minute")
            if (ms != null && ms > MAX_DURATION_MS) return@forEachIndexed away("longer than 15 minutes")

            val reasons = mutableListOf<String>()
            var score = 30 + (45 * (n - index) / n)
            reasons += "radio rank ${index + 1}/${radio.size}"
            if (ms != null) score += 10 else reasons += "length unknown"
            if (track.album != null) score += 5
            val artists = track.artists.flatMap(SongMatcher::credits).toSet()
            if (artists.any { it in seedArtists }) { score += 10; reasons += "same artist as the seed" }
            val otherTake = (identity.versions - seedVersions) intersect SongMatcher.DIFFERENT_RECORDING
            if (otherTake.isNotEmpty()) { score -= 25; reasons += "version: ${otherTake.sorted().joinToString { it.name.lowercase() }}" }
            if (identity.artist in context.skippedArtists) { score -= 30; reasons += "artist skipped this session" }
            if (track.explicit == null) reasons += "edition unknown"
            score = score.coerceIn(0, 100)
            if (score < THRESHOLD) return@forEachIndexed away("score $score below $THRESHOLD")
            accepted += PlannedCandidate(track, identity.key, score, reasons)
        }
        return PlannedBatch(spread(accepted, want), turned)
    }

    /** The catalog's order, but no lead artist twice in a row when avoidable, and [PER_ARTIST] at most. */
    private fun spread(candidates: List<PlannedCandidate>, want: Int): List<PlannedCandidate> {
        val left = candidates.toMutableList()
        val out = mutableListOf<PlannedCandidate>()
        val perArtist = HashMap<String, Int>()
        fun lead(c: PlannedCandidate) = c.identityKey.substringBefore('|')
        while (out.size < want && left.isNotEmpty()) {
            val previous = out.lastOrNull()?.let(::lead)
            val pick = left.firstOrNull { lead(it) != previous && (perArtist[lead(it)] ?: 0) < PER_ARTIST }
                ?: left.firstOrNull { (perArtist[lead(it)] ?: 0) < PER_ARTIST }
                ?: break
            left.remove(pick)
            out += pick
            perArtist[lead(pick)] = (perArtist[lead(pick)] ?: 0) + 1
        }
        return out
    }
}
