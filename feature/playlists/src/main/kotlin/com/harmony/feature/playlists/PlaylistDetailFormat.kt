package com.harmony.feature.playlists

import com.harmony.core.model.Song
import com.harmony.core.ui.component.formatLongDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Text and artwork decisions for the playlist detail header, kept free of
 * Compose so they can be unit-tested and so the header composable only has
 * to lay things out.
 */
internal object PlaylistDetailFormat {

    /** "1 song" / "24 songs". */
    fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

    /**
     * Total running time in the app's long form ("1hr 32min"). Anything
     * under a minute reads "Under 1min" rather than the "0min" the shared
     * formatter would print for a list of short clips.
     */
    fun totalDuration(songs: List<Song>): String? {
        if (songs.isEmpty()) return null
        val total = songs.sumOf { it.durationMs.coerceAtLeast(0) }
        return if (total < 60_000) "Under 1min" else formatLongDuration(total)
    }

    /** "24 songs · 1hr 32min". */
    fun summary(songs: List<Song>): String =
        listOfNotNull(songCount(songs.size), totalDuration(songs)).joinToString("  ·  ")

    /**
     * The artists a playlist is mostly made of, most frequent first:
     * "Daft Punk", "Daft Punk and Air", "Daft Punk, Air and Justice",
     * "Daft Punk, Air, Justice and 5 more".
     *
     * Ties keep playlist order, so the line doesn't reshuffle between two
     * equally common artists every time the list re-emits. Blank and
     * "<unknown>" tags are skipped: MediaStore fills those in for untagged
     * files, and naming them would just be noise.
     */
    fun artists(songs: List<Song>, shown: Int = 3): String? {
        val order = LinkedHashMap<String, Int>()
        songs.forEach { song ->
            val name = song.artist.trim()
            if (name.isNotEmpty() && !name.equals("<unknown>", ignoreCase = true)) {
                order[name] = (order[name] ?: 0) + 1
            }
        }
        if (order.isEmpty()) return null
        val ranked = order.entries
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<Map.Entry<String, Int>>> { it.value.value }
                .thenBy { it.index })
            .map { it.value.key }
        val head = ranked.take(shown)
        val rest = ranked.size - head.size
        return when {
            rest > 0 -> head.joinToString(", ") + " and $rest more"
            head.size == 1 -> head[0]
            else -> head.dropLast(1).joinToString(", ") + " and " + head.last()
        }
    }

    /**
     * Up to four covers for the header mosaic: one per ALBUM, in playlist
     * order. Picking per song instead would fill a playlist that opens with
     * four tracks off the same record with four copies of one cover, which
     * reads as a rendering bug rather than a mosaic.
     */
    fun coverArtwork(songs: List<Song>, max: Int = 4): List<String> {
        val seenAlbums = HashSet<Any>()
        val seenUris = HashSet<String>()
        val out = ArrayList<String>(max)
        for (song in songs) {
            val uri = song.artworkUri ?: continue
            // albumId 0/negative means "unknown album": fall back to the
            // artwork itself as the identity so unrelated singles still count.
            val albumKey: Any = if (song.albumId > 0) song.albumId else uri
            if (!seenAlbums.add(albumKey) || !seenUris.add(uri)) continue
            out += uri
            if (out.size == max) break
        }
        return out
    }

    // -----------------------------------------------------------------------
    // The "about this playlist" panel
    // -----------------------------------------------------------------------

    /** How a file sounds, as far as its tags tell. */
    enum class Quality(val badge: String, val label: String) {
        HI_RES("HI-RES", "Hi-Res"),
        LOSSLESS("LOSSLESS", "Lossless"),
        LOSSY("LOSSY", "Lossy"),
        UNKNOWN("", "Unknown"),
    }

    /**
     * A low bitrate decides first: Android reports 16 bits for an MP3 as
     * readily as for a FLAC, but no lossless file runs under ~500 kbps.
     */
    fun quality(song: Song): Quality {
        val kbps = song.bitrateKbps?.takeIf { it > 0 }
        if (kbps != null && kbps <= LOSSY_MAX_KBPS) return Quality.LOSSY
        val bits = song.bitDepth?.takeIf { it > 0 }
        val rate = song.sampleRateHz?.takeIf { it > 0 }
        if (bits != null && rate != null) {
            return if (bits >= 24 || rate > 48_000) Quality.HI_RES else Quality.LOSSLESS
        }
        return if (kbps != null) Quality.LOSSLESS else Quality.UNKNOWN
    }

    /** The row badge: "HI-RES", "LOSSLESS", or a lossy file's bitrate ("320"). */
    fun badge(song: Song): String? = when (val q = quality(song)) {
        Quality.LOSSY -> song.bitrateKbps?.let { "$it" }
        Quality.UNKNOWN -> null
        else -> q.badge
    }

    data class Stats(val songs: Int, val totalMs: Long, val artists: Int, val albums: Int)

    fun stats(songs: List<Song>): Stats = Stats(
        songs = songs.size,
        totalMs = songs.sumOf { it.durationMs.coerceAtLeast(0) },
        artists = songs.mapNotNull { artistKey(it.artist) }.distinct().size,
        albums = songs.map { if (it.albumId > 0) it.albumId.toString() else it.album.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "<unknown>" }.distinct().size,
    )

    /** "1h 29m" for the stat tile; "42m" under an hour. */
    fun compactDuration(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes.coerceAtLeast(if (ms > 0) 1 else 0)}m"
    }

    /** How many songs of each quality, in Hi-Res, Lossless, Lossy, Unknown order; empty kinds left out. */
    fun qualityMix(songs: List<Song>): List<Pair<Quality, Int>> {
        val counts = songs.groupingBy { quality(it) }.eachCount()
        return Quality.entries.mapNotNull { q -> counts[q]?.let { q to it } }
    }

    /**
     * One line under the quality bar: what most of it is, and the best
     * file in it. "Mostly lossless · up to 24-bit / 96 kHz".
     */
    fun qualitySummary(songs: List<Song>): String? {
        val mix = qualityMix(songs).filter { it.first != Quality.UNKNOWN }
        if (mix.isEmpty()) return null
        val top = mix.maxBy { it.second }
        val share = top.second.toFloat() / songs.size
        val lead = when {
            mix.size == 1 && top.second == songs.size -> "All ${top.first.label.lowercase()}"
            share >= 0.5f -> "Mostly ${top.first.label.lowercase()}"
            else -> "A mix of qualities"
        }
        val best = songs.filter { (it.bitDepth ?: 0) > 0 && (it.sampleRateHz ?: 0) > 0 && quality(it) != Quality.LOSSY }
            .maxWithOrNull(compareBy<Song>({ it.bitDepth }, { it.sampleRateHz }))
        return if (best == null) lead else "$lead · up to ${best.bitDepth}-bit / ${khz(best.sampleRateHz!!)} kHz"
    }

    private fun khz(rate: Int): String =
        if (rate % 1000 == 0) "${rate / 1000}" else "${rate / 1000}.${(rate % 1000) / 100}"

    /** Most common genres with how many songs each, most first, ties in playlist order. */
    fun genres(songs: List<Song>, max: Int = 5): List<Pair<String, Int>> {
        val order = LinkedHashMap<String, Pair<String, Int>>()
        songs.forEach { song ->
            val name = song.genre?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) } ?: return@forEach
            val key = name.lowercase()
            val (shown, n) = order[key] ?: (name to 0)
            order[key] = shown to n + 1
        }
        return order.values.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }.thenBy { it.index })
            .take(max)
            .map { it.value }
    }

    /** The years a playlist spans, in bars: one per year, or per five years past [MAX_YEAR_BARS]. */
    data class YearSpread(val from: Int, val to: Int, val bars: List<Pair<Int, Int>>, val peak: Int, val step: Int)

    fun years(songs: List<Song>): YearSpread? {
        val years = songs.mapNotNull { it.year?.takeIf { y -> y in 1900..2100 } }
        if (years.isEmpty()) return null
        val from = years.min()
        val to = years.max()
        val step = if (to - from + 1 > MAX_YEAR_BARS) 5 else 1
        val start = from - from % step
        val counts = years.groupingBy { it - (it - start) % step }.eachCount()
        val bars = (start..to step step).map { it to (counts[it] ?: 0) }
        val peak = years.groupingBy { it }.eachCount().maxBy { it.value }.key
        return YearSpread(from, to, bars, peak, step)
    }

    /** "2016 – 2024 · most from 2019", or just "2019". */
    fun yearsCaption(spread: YearSpread): String =
        if (spread.from == spread.to) "All from ${spread.from}"
        else "${spread.from} – ${spread.to} · most from ${spread.peak}"

    data class ArtistShare(val name: String, val songs: Int, val artworkUri: String?)

    /** The artists with the most songs here, each with the cover of their first song. */
    fun topArtists(songs: List<Song>, max: Int = 8): List<ArtistShare> {
        val order = LinkedHashMap<String, ArtistShare>()
        songs.forEach { song ->
            val key = artistKey(song.artist) ?: return@forEach
            val seen = order[key]
            order[key] = if (seen == null) ArtistShare(song.artist.trim(), 1, song.artworkUri)
            else seen.copy(songs = seen.songs + 1, artworkUri = seen.artworkUri ?: song.artworkUri)
        }
        return order.values.withIndex()
            .sortedWith(compareByDescending<IndexedValue<ArtistShare>> { it.value.songs }.thenBy { it.index })
            .take(max)
            .map { it.value }
    }

    private fun artistKey(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) }?.lowercase()

    /** "Created 12 March 2025". */
    fun created(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (epochMs <= 0) return null
        val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        return "Created ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${date.year}"
    }

    /** "01" … "99", then "100": the row's place in the playlist. */
    fun position(index: Int): String = (index + 1).toString().padStart(2, '0')

    private const val LOSSY_MAX_KBPS = 500
    private const val MAX_YEAR_BARS = 24
}
