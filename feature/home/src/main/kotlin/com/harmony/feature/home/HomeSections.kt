package com.harmony.feature.home

import com.harmony.core.model.Playlist
import com.harmony.core.model.Song

/** An album as Home shows it: built from real songs, never from a guess. */
data class HomeAlbum(
    val id: Long,
    val title: String,
    val artist: String,
    val artworkUri: String?,
)

/** A user playlist with the numbers the Playlists screen already computes. */
data class HomePlaylist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val durationMs: Long,
    val artwork: List<String>,
)

/**
 * What goes in which section. Kept free of Android and Compose so the
 * de-duplication rules can be unit-tested.
 */
internal object HomeSections {

    const val ROW_LIMIT = 10

    /**
     * "Recently added" albums, newest first, from the Recently Added smart
     * list (songs ordered by the date the scanner first saw them).
     *
     * One entry per album; the album's artist is its album-artist tag when
     * there is one, so a compilation reads "Various Artists" rather than
     * whichever track happened to be added first. Songs without an album id
     * are loose files, not albums, and are left out rather than shown as
     * one-track "albums".
     */
    fun recentAlbums(recentlyAdded: List<Song>, limit: Int = ROW_LIMIT): List<HomeAlbum> {
        val byAlbum = LinkedHashMap<Long, MutableList<Song>>()
        for (song in recentlyAdded) {
            if (song.albumId <= 0 || song.album.isBlank()) continue
            byAlbum.getOrPut(song.albumId) { mutableListOf() } += song
        }
        return byAlbum.entries.take(limit).map { (id, songs) ->
            val first = songs.first()
            HomeAlbum(
                id = id,
                title = first.album,
                artist = songs.firstNotNullOfOrNull { it.albumArtist?.takeIf(String::isNotBlank) }
                    ?: first.artist,
                artworkUri = songs.firstNotNullOfOrNull { it.artworkUri },
            )
        }
    }

    /**
     * Recently played, minus the song the Continue card is already showing
     * and minus repeats — the history can hold the same track several times
     * over, and a row of five identical covers says nothing.
     */
    fun recentlyPlayed(history: List<Song>, featured: Song?, limit: Int = ROW_LIMIT): List<Song> =
        history.asSequence()
            .filter { it.id != featured?.id }
            .distinctBy { it.id }
            .take(limit)
            .toList()

    /**
     * Newest playlists first — the ones being built right now are the ones
     * worth a shortcut; the full alphabetical list is one tap away.
     */
    fun playlists(
        playlists: List<Playlist>,
        artwork: Map<Long, List<String>>,
        durations: Map<Long, Long>,
        limit: Int = ROW_LIMIT,
    ): List<HomePlaylist> =
        playlists.sortedByDescending { it.createdAt }.take(limit).map {
            HomePlaylist(
                id = it.id,
                name = it.name,
                songCount = it.songCount,
                durationMs = durations[it.id] ?: 0L,
                artwork = artwork[it.id].orEmpty(),
            )
        }

    /** "1,306 songs · 120 albums · 84 artists", leaving out zeros. */
    fun librarySummary(stats: LibraryStats): String = listOfNotNull(
        plural(stats.songs, "song"),
        plural(stats.albums, "album"),
        plural(stats.artists, "artist"),
    ).joinToString("  ·  ")

    fun plural(count: Int, noun: String): String? = when (count) {
        0 -> null
        1 -> "1 $noun"
        else -> "${formatCount(count)} ${noun}s"
    }

    /** "12 songs · 48min" for a playlist card. */
    fun playlistMeta(playlist: HomePlaylist): String = listOfNotNull(
        if (playlist.songCount == 1) "1 song" else "${formatCount(playlist.songCount)} songs",
        playlist.durationMs.takeIf { it >= 60_000 }?.let(::formatMinutes),
    ).joinToString("  ·  ")

    /** "1hr 32min" / "48min" — the same long form the rest of the app uses. */
    fun formatMinutes(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}hr ${minutes}min" else "${minutes}min"
    }
}

internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..21 -> "Good evening"
    else -> "Good night"
}

/** Thousands separators: "1,306". */
internal fun formatCount(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()
