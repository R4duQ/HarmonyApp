package com.harmony.feature.downloads

import com.harmony.core.common.text.SearchTextNormalizer
import com.harmony.core.model.Song
import kotlin.math.abs

data class SpotifyTrackMatch(
    val song: Song,
    val confidence: PlaylistMatchConfidence,
    val score: Int,
)

/** Conservative matcher: a questionable edition remains downloadable instead of being silently linked. */
object SpotifyPlaylistMatcher {
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val featuredArtist = Regex("\\s+(?:feat\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE)
    private val artistSeparator = Regex("\\s*(?:,|&| x )\\s*", RegexOption.IGNORE_CASE)
    private val versionWords = setOf(
        "acoustic", "clean", "demo", "edit", "explicit", "instrumental", "live",
        "mix", "mono", "radio", "remaster", "remastered", "remix", "sped", "slowed",
    )

    fun match(track: SpotifyPlaylistTrack, library: List<Song>): SpotifyTrackMatch? =
        library.asSequence()
            .mapNotNull { song -> score(track, song)?.let { value -> song to value } }
            .maxByOrNull { it.second }
            ?.takeIf { (_, score) -> score >= MINIMUM_SCORE }
            ?.let { (song, score) ->
                val exact = canonical(track.title) == canonical(song.title) &&
                    artistsCompatible(track.artists, song.artist) &&
                    durationDifference(track.durationMs, song.durationMs) <= EXACT_DURATION_TOLERANCE_MS
                SpotifyTrackMatch(
                    song = song,
                    confidence = if (exact) PlaylistMatchConfidence.EXACT else PlaylistMatchConfidence.LIKELY,
                    score = score,
                )
            }

    /**
     * Playlist-sized matching avoids rescanning a large library for every
     * remote row. The exact normalized-title bucket is deliberately strict:
     * when the edition is uncertain it is safer to leave the track missing.
     */
    fun matchAll(
        tracks: List<SpotifyPlaylistTrack>,
        library: List<Song>,
    ): List<SpotifyTrackMatch?> {
        val songsByTitle = library.groupBy { canonical(it.title) }
        return tracks.map { track ->
            match(track, songsByTitle[canonical(track.title)].orEmpty())
        }
    }

    private fun score(track: SpotifyPlaylistTrack, song: Song): Int? {
        val remoteTitle = canonical(track.title)
        val localTitle = canonical(song.title)
        if (remoteTitle.isBlank() || localTitle.isBlank()) return null

        val titleScore = when {
            remoteTitle == localTitle -> 60
            tokenCoverage(remoteTitle, localTitle) >= 0.85 -> 45
            else -> return null
        }
        val artistScore = when {
            canonical(track.artists) == canonical(song.artist) -> 28
            artistsCompatible(track.artists, song.artist) -> 23
            else -> 0
        }
        if (artistScore == 0) return null

        val durationDelta = durationDifference(track.durationMs, song.durationMs)
        val durationScore = when {
            track.durationMs <= 0L || song.durationMs <= 0L -> 0
            durationDelta <= 2_000L -> 10
            durationDelta <= 5_000L -> 6
            durationDelta <= 10_000L -> 1
            else -> -18
        }
        val albumScore = if (
            track.album.isNotBlank() && song.album.isNotBlank() &&
            canonical(track.album) == canonical(song.album)
        ) 6 else 0

        val editionPenalty = if (editionWords(track.title) == editionWords(song.title)) 0 else -35
        return titleScore + artistScore + durationScore + albumScore + editionPenalty
    }

    internal fun canonical(value: String): String = SearchTextNormalizer.foldForSearch(value)
        .replace(featuredArtist, "")
        .replace(separators, " ")
        .trim()

    private fun primaryArtist(value: String): String = SearchTextNormalizer.foldForSearch(value)
        .replace(featuredArtist, "")
        .split(artistSeparator, limit = 2)
        .firstOrNull()
        ?.let(::canonical)
        .orEmpty()

    private fun artistsCompatible(remote: String, local: String): Boolean {
        val remotePrimary = primaryArtist(remote)
        val localPrimary = primaryArtist(local)
        return remotePrimary.isNotBlank() && remotePrimary == localPrimary
    }

    private fun editionWords(value: String): Set<String> = canonical(value)
        .split(' ')
        .filterTo(mutableSetOf()) { it in versionWords }

    private fun tokenCoverage(expected: String, actual: String): Double {
        val expectedTokens = expected.split(' ').filter(String::isNotBlank).toSet()
        if (expectedTokens.isEmpty()) return 0.0
        val actualTokens = actual.split(' ').filter(String::isNotBlank).toSet()
        return expectedTokens.count { it in actualTokens }.toDouble() / expectedTokens.size
    }

    private fun durationDifference(left: Long, right: Long): Long = abs(left - right)

    private const val MINIMUM_SCORE = 75
    private const val EXACT_DURATION_TOLERANCE_MS = 5_000L
}
