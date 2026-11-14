package com.harmony.domain.online

import com.harmony.domain.library.discovery.TrackIdentity

/**
 * Who the song in a file is, read from its sound (an audio fingerprint),
 * for when its tags are not enough to find it in the catalog. Only the
 * fingerprint leaves the phone, never the audio.
 */
fun interface SongFingerprinter {
    suspend fun identify(uri: String): FingerprintResult

    companion object {
        val NONE = SongFingerprinter { FingerprintResult.Failed("no fingerprinter") }
    }
}

sealed interface FingerprintResult {
    data class Found(val title: String, val artist: String, val album: String? = null, val isrc: String? = null) : FingerprintResult

    /** The fingerprint service heard it and knows no song like it. */
    data object NotFound : FingerprintResult

    /** It could not be asked (no network, the file could not be decoded): not "unknown song". */
    data class Failed(val reason: String) : FingerprintResult
}

/** What the mix's radio starts from, from the most exact to the loosest. */
enum class SeedBasis {
    /** The song's own id was already known (a song an online mix prepared). */
    KNOWN,

    /** Found by its tags (title, artist, album, length). */
    TAGS,

    /** The tags as they were found nothing; a cleaned reading of them did (the file's name, a rip's notes taken off). */
    NAME,

    /** The tags were not enough; recognised by its sound, then found by what that said. */
    FINGERPRINT,

    /** The song itself was not found: the radio starts from a song by the same artist. */
    ARTIST,

    /** Not even the artist: the radio starts from a song of the same genre. */
    GENRE,
    ;

    /** Suggestions follow this exact song (not just its artist or genre). */
    val exact: Boolean get() = this == KNOWN || this == TAGS || this == NAME || this == FINGERPRINT
}

/** Where a looser radio starts when the song itself is not found. */
object LooseAnchors {
    /** Genre tags that say nothing: blank, catch-alls, ID3v1 numbers like "(13)". */
    private val meaningless = setOf("unknown", "other", "others", "misc", "miscellaneous", "none", "genre", "music", "various")
    private val numeric = Regex("^\\(?\\d+\\)?$")

    /** The seed's genre when it names one, else null. */
    fun usableGenre(genre: String?): String? {
        val g = genre?.trim().orEmpty()
        if (g.isEmpty() || numeric.matches(g)) return null
        return g.takeUnless { TrackIdentity.normalize(it) in meaningless }
    }

    /**
     * The first song in [results] by [artist] (their lead credit among the
     * song's credits): the start of an artist radio. Never the seed itself
     * (another title is not required, but a different id is).
     */
    fun byArtist(artist: String, results: List<CatalogTrack>): CatalogTrack? {
        val lead = SongMatcher.credits(artist).firstOrNull() ?: return null
        return results.firstOrNull { t ->
            t.kind == CatalogKind.SONG && t.playable != false && t.artists.flatMap(SongMatcher::credits).contains(lead)
        }
    }

    /** The first playable song of a genre search: the start of a genre radio. */
    fun byGenre(results: List<CatalogTrack>): CatalogTrack? =
        results.firstOrNull { it.kind == CatalogKind.SONG && it.playable != false }
}
