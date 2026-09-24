package com.harmony.feature.playlists

import com.harmony.core.model.Song
import com.harmony.core.ui.component.formatLongDuration

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
}
