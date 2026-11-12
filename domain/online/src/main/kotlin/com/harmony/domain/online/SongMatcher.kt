package com.harmony.domain.online

import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.library.discovery.TrackVersion
import kotlin.math.abs

/** The song playing, as the player knows it: the metadata a catalog search starts from. */
data class SeedTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    /** Null or 0: not known. */
    val durationMs: Long? = null,
    /** The file's own explicit mark; null when its tags don't say (the usual case). */
    val explicit: Boolean? = null,
    /**
     * The catalog's id of this very recording, when it is already known (a
     * song an online mix prepared): then nothing is searched for.
     */
    val videoId: String? = null,
) {
    val knownDurationMs: Long? get() = durationMs?.takeIf { it > 0 }

    /** The search text: lead artist and title, versions kept (they are part of what is searched for). */
    val query: String get() = listOf(artist.trim(), title.trim()).filter { it.isNotEmpty() }.joinToString(" ")
}

enum class MatchVerdict {
    /** One recording matches, with enough evidence. */
    CONFIRMED,

    /** Two or more different recordings match equally well: none is chosen. */
    AMBIGUOUS,

    /** Something matches by name, but the evidence that it is this recording is too thin. */
    UNCERTAIN,

    /** Nothing matches. */
    NO_MATCH,
}

/** How one result compared with the seed, in points (0..100). [score] is null when it was rejected outright. */
data class CandidateAssessment(
    val track: CatalogTrack,
    val score: Int?,
    val reasons: List<String>,
)

data class MatchResult(
    val verdict: MatchVerdict,
    /** The confirmed recording (only with [MatchVerdict.CONFIRMED]). */
    val track: CatalogTrack?,
    /** The best result's points, 0..100 (0 when nothing matched). */
    val score: Int,
    val assessments: List<CandidateAssessment>,
) {
    val confirmed: Boolean get() = verdict == MatchVerdict.CONFIRMED
}

/**
 * Decides which search result, if any, is the recording the seed names. A
 * wrong match sends the radio somewhere else and, later, prepares the wrong
 * file, so the rules lean towards answering "not sure":
 *
 *  1. Only a [CatalogKind.SONG] counts. Videos (official or uploaded),
 *     episodes and untyped results are rejected whatever their title says:
 *     a `videoId` alone says nothing about what it is.
 *  2. The lead artist of one side must be among the credited artists of the
 *     other (diacritics, case, cedilla/comma forms and punctuation ignored).
 *  3. The base title must be the same, and so must the versions named in it
 *     (live, remix, acoustic, cover, karaoke, instrumental, demo, edit,
 *     extended, sped up/slowed): "Song (Live)" is never "Song". A remaster
 *     is the same recording remastered: accepted, with a penalty.
 *  4. Durations, when both are known, must agree within
 *     [DURATION_REJECT_MS]. An unknown duration stays unknown: no bonus.
 *  5. Explicit and clean editions, when both sides say, must agree. The
 *     seed's mark is usually unknown (files rarely tag it): then the edition
 *     is not used to decide.
 *  6. The best result is confirmed only at [CONFIRM_SCORE] or more (a
 *     known, close duration or the same album is needed), and only when no
 *     other recording (a different duration or album) comes within
 *     [AMBIGUITY_MARGIN] of it. Copies of the same recording (same duration
 *     within [SAME_RECORDING_MS]) are not a choice between recordings.
 *
 * The thresholds were fixed before tuning, against the versioned corpus
 * (ytmusic-match-corpus.v1.json): no wrong match may be accepted, and at
 * least [MIN_RECALL] of the right ones must be.
 */
object SongMatcher {
    /** Points (0..100): integers, so a threshold never depends on rounding. */
    const val CONFIRM_SCORE = 70
    const val AMBIGUITY_MARGIN = 10
    const val SAME_RECORDING_MS = 2_000L
    const val DURATION_STRONG_MS = 2_000L
    const val DURATION_CLOSE_MS = 4_000L
    const val DURATION_REJECT_MS = 8_000L
    const val MIN_RECALL = 0.85

    /** Versions that make another recording (everything but a remaster). */
    val DIFFERENT_RECORDING: Set<TrackVersion> = TrackVersion.entries.toSet() - TrackVersion.REMASTER

    // Credit separators, as TrackIdentity splits them (not "/" nor "and").
    private val artistSplit = Regex("\\s+(feat\\.?|ft\\.?|featuring|with|x|vs\\.?)\\s+|\\s*[,&;]\\s*", RegexOption.IGNORE_CASE)
    private val albumNoise = Regex("[(\\[{][^)\\]}]*[)\\]}]")

    fun match(seed: SeedTrack, results: List<CatalogTrack>): MatchResult {
        val assessments = results.map { assess(seed, it) }
        val scored = assessments.filter { it.score != null }.sortedByDescending { it.score }
        val best = scored.firstOrNull() ?: return MatchResult(MatchVerdict.NO_MATCH, null, 0, assessments)
        val bestScore = best.score!!
        // Another recording close behind: a different take, length or album, not a copy.
        val rival = scored.drop(1).firstOrNull { other ->
            other.score!! >= bestScore - AMBIGUITY_MARGIN && !sameRecording(best.track, other.track)
        }
        val verdict = when {
            rival != null -> MatchVerdict.AMBIGUOUS
            bestScore < CONFIRM_SCORE -> MatchVerdict.UNCERTAIN
            else -> MatchVerdict.CONFIRMED
        }
        return MatchResult(verdict, best.track.takeIf { verdict == MatchVerdict.CONFIRMED }, bestScore, assessments)
    }

    /** One result against the seed: rejected (score null, with the reason) or scored in points. */
    fun assess(seed: SeedTrack, track: CatalogTrack): CandidateAssessment {
        val reasons = mutableListOf<String>()
        fun reject(reason: String) = CandidateAssessment(track, null, reasons + reason)

        if (track.kind != CatalogKind.SONG) return reject("not a song: ${track.kind}")
        if (track.playable == false) return reject("not playable in the catalog")

        val seedArtists = credits(seed.artist)
        val trackArtists = track.artists.flatMap(::credits).toSet()
        if (seedArtists.isEmpty() || trackArtists.isEmpty()) return reject("artist unknown")
        val leadMatch = seedArtists.first() in trackArtists || track.artists.firstOrNull()?.let(::credits)?.firstOrNull() in seedArtists
        if (!leadMatch) return reject("other artist")

        val (seedTitle, seedVersions) = TrackIdentity.parseTitle(seed.title)
        val (trackTitle, trackVersions) = TrackIdentity.parseTitle(track.title)
        if (seedTitle.isBlank() || seedTitle != trackTitle) return reject("other title")
        val differentTake = symmetricDifference(seedVersions, trackVersions) intersect DIFFERENT_RECORDING
        if (differentTake.isNotEmpty()) return reject("other version: ${differentTake.sorted().joinToString { it.name.lowercase() }}")

        if (seed.explicit != null && track.explicit != null && seed.explicit != track.explicit) {
            return reject("other edition: ${if (track.explicit) "explicit" else "clean"}")
        }

        var score = 50
        reasons += "same artist and title"
        if (TrackVersion.REMASTER in symmetricDifference(seedVersions, trackVersions)) {
            score -= 15
            reasons += "remaster edition differs"
        }
        val seedMs = seed.knownDurationMs
        val trackMs = track.durationMs?.takeIf { it > 0 }
        if (seedMs != null && trackMs != null) {
            val delta = abs(seedMs - trackMs)
            when {
                delta > DURATION_REJECT_MS -> return reject("duration differs by ${delta / 1000} s")
                delta <= DURATION_STRONG_MS -> { score += 30; reasons += "same duration" }
                delta <= DURATION_CLOSE_MS -> { score += 20; reasons += "duration within ${delta / 1000} s" }
                else -> reasons += "duration differs by ${delta / 1000} s"
            }
        } else {
            reasons += "duration unknown"
        }
        val seedAlbum = album(seed.album)
        val trackAlbum = album(track.album)
        when {
            seedAlbum == null || trackAlbum == null -> reasons += "album unknown"
            seedAlbum == trackAlbum -> { score += 20; reasons += "same album" }
            else -> reasons += "other album"
        }
        if (seed.explicit == null || track.explicit == null) reasons += "edition unknown"
        return CandidateAssessment(track, score.coerceIn(0, 100), reasons)
    }

    private fun <T> symmetricDifference(a: Set<T>, b: Set<T>): Set<T> = (a - b) + (b - a)

    /** The same recording listed twice (an album and a single, a re-release): same length within a second or two. */
    fun sameRecording(a: CatalogTrack, b: CatalogTrack): Boolean {
        val da = a.durationMs ?: return false
        val db = b.durationMs ?: return false
        return abs(da - db) <= SAME_RECORDING_MS &&
            TrackIdentity.parseTitle(a.title) == TrackIdentity.parseTitle(b.title)
    }

    /** "Dua Lipa feat. DaBaby, Ariana Grande" → ["dua lipa", "dababy", "ariana grande"], normalized. */
    fun credits(artist: String): List<String> =
        artist.split(artistSplit).map(TrackIdentity::normalize).filter { it.isNotBlank() && it !in CREDIT_WORDS }

    private val CREDIT_WORDS = setOf("feat", "ft", "featuring", "with", "x", "vs")

    /** An album name without its bracketed edition notes ("(Remastered)", "[Deluxe]"), normalized. */
    fun album(value: String?): String? =
        value?.let { TrackIdentity.normalize(albumNoise.replace(it, " ")) }?.takeIf { it.isNotBlank() }
}
