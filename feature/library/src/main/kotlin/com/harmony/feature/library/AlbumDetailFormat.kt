package com.harmony.feature.library

import com.harmony.core.model.Song
import com.harmony.core.ui.component.formatLongDuration
import java.util.Locale

/** One line of the album's track list: a song, or a run of track numbers the library doesn't have. */
internal sealed interface AlbumRow {
    /** [number] is the printed track number: the tag when there is one, else the position. */
    data class Track(val song: Song, val number: Int) : AlbumRow

    /** Tracks [from]..[to] of this disc are not in the library. */
    data class Missing(val from: Int, val to: Int) : AlbumRow
}

/** A disc of the album. [disc] is null for a single-disc album, which gets no header. */
internal data class DiscSection(
    val disc: Int?,
    val rows: List<AlbumRow>,
    val songCount: Int,
    val durationMs: Long,
)

/** How much of the album is lossless, going by what the scanner could read. */
internal enum class Lossless { ALL, SOME, NONE, UNKNOWN }

/**
 * [label] is the short technical line: "FLAC · 24-bit / 96 kHz", "320 kbps",
 * or null when nothing is known. [typical] is the resolution most tracks
 * share, so a row can say when it is the odd one out.
 */
internal data class AlbumQuality(
    val lossless: Lossless,
    val label: String?,
    val losslessCount: Int = 0,
    val lossyCount: Int = 0,
    val typical: String? = null,
)

/** The album's name, credit and release facts, taken from its songs' tags. */
internal data class AlbumHeadline(
    val title: String,
    val artist: String?,
    /** The name to open on the artist page, or null when there is no single artist to go to. */
    val artistLink: String?,
    val year: Int?,
    val genres: List<String>,
)

/**
 * Text and ordering decisions for the album page, kept free of Compose so
 * they can be unit-tested and the page only has to lay things out.
 *
 * Everything comes from the songs themselves. Tags are often incomplete, so
 * every function degrades quietly: no disc tags means one disc, no track
 * numbers means list order, no bit depth means no quality claim.
 */
internal object AlbumDetailFormat {

    const val VARIOUS_ARTISTS = "Various artists"

    /** Disc, then track number, then title. Songs without a track number go after numbered ones. */
    fun ordered(songs: List<Song>): List<Song> = songs.sortedWith(
        compareBy<Song>({ disc(it) }, { it.trackNumber?.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )

    /**
     * The track list, split by disc when there is more than one, with a
     * [AlbumRow.Missing] line wherever the numbering skips.
     *
     * Gaps are only shown when every song on the disc has its own track
     * number and no two share one: otherwise the numbers don't describe a
     * single release, and "track 3 is missing" would be a guess.
     */
    fun sections(songs: List<Song>): List<DiscSection> {
        val byDisc = ordered(songs).groupBy { disc(it) }
        val multiDisc = byDisc.size > 1
        return byDisc.map { (disc, discSongs) ->
            val numbers = discSongs.map { it.trackNumber?.takeIf { n -> n > 0 } }
            val trustNumbers = numbers.none { it == null } && numbers.toSet().size == numbers.size
            val rows = ArrayList<AlbumRow>(discSongs.size + 2)
            var expected = 1
            discSongs.forEachIndexed { index, song ->
                val number = numbers[index]
                if (trustNumbers && number != null) {
                    if (number > expected) rows += AlbumRow.Missing(expected, number - 1)
                    expected = number + 1
                }
                rows += AlbumRow.Track(song, number ?: (index + 1))
            }
            DiscSection(
                disc = if (multiDisc) disc else null,
                rows = rows,
                songCount = discSongs.size,
                durationMs = discSongs.sumOf { it.durationMs.coerceAtLeast(0) },
            )
        }
    }

    /** Tracks the numbering says exist but the library doesn't have. */
    fun missingCount(sections: List<DiscSection>): Int =
        sections.sumOf { s -> s.rows.sumOf { if (it is AlbumRow.Missing) it.to - it.from + 1 else 0 } }

    fun headline(songs: List<Song>): AlbumHeadline {
        val title = mostCommon(songs.map { it.album }) ?: "Album"
        val albumArtist = mostCommon(songs.mapNotNull { it.albumArtist })
        val artists = songs.map { it.artist.trim() }.filter(::isRealName).distinctBy { it.lowercase(Locale.ROOT) }
        val artist = when {
            albumArtist != null -> albumArtist
            artists.size == 1 -> artists[0]
            artists.size > 1 -> VARIOUS_ARTISTS
            else -> null
        }
        // Only link to an artist page that will have songs on it: the page is
        // keyed on the track artist, so an album-artist tag nobody's track
        // carries would open an empty page.
        val link = artist?.takeIf { it != VARIOUS_ARTISTS }?.let { name ->
            songs.firstOrNull { it.artist.trim().equals(name.trim(), ignoreCase = true) }?.artist
        }
        return AlbumHeadline(
            title = title,
            artist = artist,
            artistLink = link,
            year = mostCommon(songs.mapNotNull { it.year?.takeIf { y -> y in 1000..9999 } }),
            genres = ranked(songs.mapNotNull { it.genre }.flatMap(::splitGenres)).take(2),
        )
    }

    /** "ALBUM · 2019". */
    fun eyebrow(headline: AlbumHeadline): String =
        listOfNotNull("ALBUM", headline.year?.toString()).joinToString(" · ")

    fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

    /** "12 songs · 48min · Indie pop". */
    fun summary(songs: List<Song>, headline: AlbumHeadline): String = listOfNotNull(
        songCount(songs.size),
        totalDuration(songs),
        headline.genres.firstOrNull(),
    ).joinToString("  ·  ")

    fun totalDuration(songs: List<Song>): String? {
        if (songs.isEmpty()) return null
        val total = songs.sumOf { it.durationMs.coerceAtLeast(0) }
        return if (total < 60_000) "Under 1min" else formatLongDuration(total)
    }

    /** "Disc 2  ·  10 songs  ·  41min". */
    fun discLabel(section: DiscSection): String = listOfNotNull(
        section.disc?.let { "Disc $it" },
        songCount(section.songCount),
        if (section.durationMs >= 60_000) formatLongDuration(section.durationMs) else null,
    ).joinToString("  ·  ")

    /** "Track 3" / "Tracks 5–7". */
    fun missingLabel(row: AlbumRow.Missing): String =
        if (row.from == row.to) "Track ${row.from} isn't in your library"
        else "Tracks ${row.from}–${row.to} aren't in your library"

    /** The same, without the number: the row prints the number in its own column. */
    fun missingShort(row: AlbumRow.Missing): String =
        if (row.from == row.to) "isn't in your library" else "aren't in your library"

    /**
     * The track's own artist when it isn't the album's: features, splits and
     * compilations. Null when it would only repeat the header.
     */
    fun trackCredit(song: Song, headline: AlbumHeadline): String? {
        val artist = song.artist.trim()
        if (!isRealName(artist)) return null
        if (headline.artist == VARIOUS_ARTISTS) return artist
        return artist.takeUnless { it.equals(headline.artist?.trim(), ignoreCase = true) }
    }

    fun quality(songs: List<Song>): AlbumQuality {
        if (songs.isEmpty()) return AlbumQuality(Lossless.UNKNOWN, null)
        // Bit depth only exists for PCM-based formats, so its presence is real
        // evidence of lossless audio; Home's quality card uses the same rule.
        val lossless = songs.filter { (it.bitDepth ?: 0) > 0 }
        val lossy = songs.filter { (it.bitDepth ?: 0) <= 0 && (it.bitrateKbps ?: 0) > 0 }
        val format = commonFormat(songs)
        return when {
            lossless.size == songs.size -> {
                val typical = mostCommon(lossless.mapNotNull(::resolution))
                AlbumQuality(
                    lossless = Lossless.ALL,
                    label = listOfNotNull(format, range(lossless)).joinToString(" · ").ifEmpty { null },
                    losslessCount = lossless.size,
                    typical = typical,
                )
            }
            lossless.isNotEmpty() -> AlbumQuality(
                lossless = Lossless.SOME,
                label = "Mixed quality",
                losslessCount = lossless.size,
                lossyCount = songs.size - lossless.size,
                typical = if (lossless.size >= songs.size - lossless.size) "lossless" else "lossy",
            )
            lossy.isNotEmpty() -> {
                val rates = lossy.mapNotNull { it.bitrateKbps }.distinct().sorted()
                val bitrate = if (rates.size == 1) "${rates[0]} kbps" else "${rates.first()}–${rates.last()} kbps"
                AlbumQuality(
                    lossless = Lossless.NONE,
                    label = listOfNotNull(format, bitrate).joinToString(" · "),
                    lossyCount = lossy.size,
                    typical = mostCommon(lossy.mapNotNull { it.bitrateKbps?.let { r -> "$r kbps" } }),
                )
            }
            else -> AlbumQuality(Lossless.UNKNOWN, null)
        }
    }

    /**
     * A short note for a track that differs from the rest of the album:
     * the one lossy file on a lossless album, or a 24-bit track among 16-bit
     * ones. Null for the ordinary case, so most rows stay quiet.
     */
    fun rowNote(song: Song, quality: AlbumQuality): String? {
        val isLossless = (song.bitDepth ?: 0) > 0
        return when (quality.lossless) {
            Lossless.SOME -> when {
                quality.typical == "lossless" && !isLossless -> song.bitrateKbps?.let { "$it kbps" } ?: "Lossy"
                quality.typical == "lossy" && isLossless -> "Lossless"
                else -> null
            }
            Lossless.ALL -> resolution(song).takeIf { it != null && it != quality.typical }
            else -> null
        }
    }

    /**
     * Label/value lines for "About this album", in reading order. Lines
     * whose value the tags don't provide are left out rather than shown
     * as "Unknown".
     */
    fun details(songs: List<Song>, headline: AlbumHeadline, sections: List<DiscSection>, quality: AlbumQuality): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        headline.year?.let { out += "Released" to it.toString() }
        if (headline.genres.isNotEmpty()) out += "Genre" to headline.genres.joinToString(", ")
        val discs = sections.size
        out += "Songs" to (songCount(songs.size) + if (discs > 1) " on $discs discs" else "")
        val missing = missingCount(sections)
        if (missing > 0) out += "In your library" to "${songs.size} of ${songs.size + missing} tracks"
        totalDuration(songs)?.let { out += "Length" to it }
        qualityDetail(quality)?.let { out += "Quality" to it }
        composers(songs)?.let { out += "Composer" to it }
        songs.firstNotNullOfOrNull { it.replayGainAlbumDb }?.let { out += "Album gain" to gain(it) }
        return out
    }

    /**
     * The artist's other albums from their songs: newest first, excluding
     * [currentAlbumId], and only albums that are credited to the artist. A
     * guest spot on someone else's record has that record's album artist,
     * so it stays out.
     */
    fun otherAlbums(artistSongs: List<Song>, artist: String, currentAlbumId: Long): List<OtherAlbum> =
        artistSongs
            .filter { it.albumId > 0 && it.albumId != currentAlbumId }
            .groupBy { it.albumId }
            .mapNotNull { (id, songs) ->
                val head = headline(songs)
                if (!head.artist.equals(artist.trim(), ignoreCase = true)) return@mapNotNull null
                OtherAlbum(
                    id = id,
                    title = head.title,
                    year = head.year,
                    artworkUri = songs.firstNotNullOfOrNull { it.artworkUri },
                    songCount = songs.size,
                )
            }
            .sortedWith(compareByDescending<OtherAlbum> { it.year ?: Int.MIN_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    fun qualityDetail(quality: AlbumQuality): String? = when (quality.lossless) {
        Lossless.ALL -> listOfNotNull("Lossless", quality.label).joinToString(" · ")
        Lossless.SOME -> "${quality.losslessCount} lossless, ${quality.lossyCount} lossy"
        Lossless.NONE -> listOfNotNull("Lossy", quality.label).joinToString(" · ")
        Lossless.UNKNOWN -> null
    }

    /** "−7.4 dB", with a real minus sign. */
    fun gain(db: Float): String {
        val text = String.format(Locale.US, "%.1f dB", kotlin.math.abs(db))
        return when {
            db < -0.05f -> "−$text"
            db > 0.05f -> "+$text"
            else -> "0.0 dB"
        }
    }

    /** "44.1 kHz", "96 kHz". */
    fun sampleRate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.US, "%.1f kHz", hz / 1000.0)

    // ---- helpers ------------------------------------------------------------------------

    private fun disc(song: Song): Int = song.discNumber?.takeIf { it > 0 } ?: 1

    private fun resolution(song: Song): String? {
        val depth = song.bitDepth?.takeIf { it > 0 }
        val rate = song.sampleRateHz?.takeIf { it > 0 }
        return when {
            depth != null && rate != null -> "$depth-bit / ${sampleRate(rate)}"
            depth != null -> "$depth-bit"
            rate != null -> sampleRate(rate)
            else -> null
        }
    }

    /** "24-bit / 96 kHz", or "16–24-bit / 44.1–96 kHz" when the tracks differ. */
    private fun range(songs: List<Song>): String? {
        val depths = songs.mapNotNull { it.bitDepth?.takeIf { d -> d > 0 } }.distinct().sorted()
        val rates = songs.mapNotNull { it.sampleRateHz?.takeIf { r -> r > 0 } }.distinct().sorted()
        val depth = when (depths.size) {
            0 -> null
            1 -> "${depths[0]}-bit"
            else -> "${depths.first()}–${depths.last()}-bit"
        }
        val rate = when (rates.size) {
            0 -> null
            1 -> sampleRate(rates[0])
            else -> "${sampleRate(rates.first()).removeSuffix(" kHz")}–${sampleRate(rates.last())}"
        }
        return listOfNotNull(depth, rate).joinToString(" / ").ifEmpty { null }
    }

    /**
     * The container name when every file shows the same one in its path,
     * e.g. SAF documents and downloads ("…/Night Ferry.flac"). MediaStore
     * content URIs carry no extension, and then no name is claimed.
     */
    private fun commonFormat(songs: List<Song>): String? {
        val names = songs.map { extension(it.uri)?.let(::formatName) }
        val first = names.firstOrNull() ?: return null
        return first.takeIf { names.all { it == first } }
    }

    private fun extension(uri: String): String? = uri
        .substringBefore('?').substringBefore('#')
        .substringAfterLast('/')
        // SAF document ids encode the whole path in the last segment
        // ("primary%3AMusic%2FNight%20Ferry.flac").
        .let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
        .substringAfterLast('/')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
        .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }

    private fun formatName(ext: String): String? = when (ext) {
        "flac" -> "FLAC"
        "wav", "wave" -> "WAV"
        "aif", "aiff" -> "AIFF"
        "alac" -> "ALAC"
        "ape" -> "APE"
        "wv" -> "WavPack"
        "dsf", "dff" -> "DSD"
        "mp3" -> "MP3"
        "m4a", "mp4" -> null // ALAC or AAC: the container alone can't say
        "aac" -> "AAC"
        "ogg", "oga" -> "Ogg"
        "opus" -> "Opus"
        else -> null
    }

    private fun composers(songs: List<Song>): String? {
        val names = ranked(songs.mapNotNull { it.composer?.trim()?.takeIf(::isRealName) })
        return when (names.size) {
            0 -> null
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
        }
    }

    private fun splitGenres(raw: String): List<String> =
        raw.split(';', '/', ',').map { it.trim() }.filter { isRealName(it) && !isNumericGenre(it) }

    /** ID3v1 leftovers like "(13)" or "13". */
    private fun isNumericGenre(value: String) = value.trim('(', ')').all(Char::isDigit)

    private fun isRealName(value: String) =
        value.isNotBlank() && !value.equals("<unknown>", ignoreCase = true) && !value.equals("unknown", ignoreCase = true)

    /** The most frequent value; ties keep first appearance. Blank values are ignored. */
    private fun <T : Any> mostCommon(values: List<T>): T? = ranked(values).firstOrNull()

    private fun <T : Any> ranked(values: List<T>): List<T> {
        val counts = LinkedHashMap<Any, Pair<T, Int>>()
        values.forEach { v ->
            if (v is String && v.isBlank()) return@forEach
            val key: Any = if (v is String) v.trim().lowercase(Locale.ROOT) else v
            val (first, n) = counts[key] ?: (v to 0)
            counts[key] = first to n + 1
        }
        return counts.values.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<T, Int>>> { it.value.second }.thenBy { it.index })
            .map { it.value.first }
    }
}
