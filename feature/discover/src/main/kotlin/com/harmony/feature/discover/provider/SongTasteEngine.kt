package com.harmony.feature.discover.provider

import com.harmony.feature.discover.model.*
import kotlin.random.Random

object SongTasteEngine {
    // These two catalogue labels describe one recording. Keep the other song slots stable on upgrade.
    private val voteAliases = mapOf("Hounds-of-Love:1" to "Hounds-of-Love:0")
    val songs: List<TasteSong> = ShflAlbumCatalog.albums.flatMap { album ->
        album.entryTracks.distinct().mapIndexed { index, title -> TasteSong(album, title, index) }
    }.filterNot { it.id in voteAliases }
    val songIds: Set<String> = songs.map { it.id }.toSet()

    fun canonicalSongId(id: String): String? = (voteAliases[id] ?: id).takeIf { it in songIds }

    fun unplayed(preferences: DiscoveryPreferences, genre: AlbumGenre?, seed: Long): List<TasteSong> {
        val heardAlbums = songs.filter { it.id in preferences.round.songIds }.map { it.album.id }.toSet()
        return songs.filter { it.id !in preferences.likedSongs && it.id !in preferences.dislikedSongs &&
            (genre == null || genre in it.album.genres) }.shuffled(Random(seed + preferences.round.number))
            .sortedBy { candidate ->
                // Hear several artists before returning to another single from the same record.
                candidate.album.id in heardAlbums
            }
    }

    /** Normalize by the number of decisions, so a busy genre cannot drown out all the others. */
    fun scores(albums: List<DiscoverAlbum>, preferences: DiscoveryPreferences): Map<String, Int> {
        val decisions = songs.mapNotNull { song ->
            when (song.id) {
                in preferences.likedSongs -> song to 1.0
                in preferences.dislikedSongs -> song to -1.0
                else -> null
            }
        }
        return albums.associate { album ->
            val direct = decisions.filter { it.first.album.id == album.id }.map { it.second }
            val artist = decisions.filter { it.first.album.artist == album.artist }.map { it.second }
            val genres = album.genres.mapNotNull { genre ->
                decisions.filter { genre in it.first.album.genres }.map { it.second }.takeIf { it.isNotEmpty() }?.average()
            }
            album.id to ((direct.averageOrZero() * 65) + (artist.averageOrZero() * 30) +
                (genres.averageOrZero() * 35)).toInt()
        }
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}

/** Explicit batches, never just a new ordering of the same first 24 records. */
data class AlbumBatchHistory(
    val seen: Set<String> = emptySet(),
    // At a cycle boundary, delay the previous page without counting it as visited again.
    val deferred: Set<String> = emptySet(),
)

object AlbumBatches {
    const val SIZE = 24
    /** Removing the final unseen bookmark must reveal the other saved albums again. */
    fun activeSeen(ranked: List<AlbumSuggestion>, seen: Set<String>): Set<String> =
        if (ranked.any { it.album.id !in seen }) seen else emptySet()

    fun page(ranked: List<AlbumSuggestion>, history: AlbumBatchHistory): List<AlbumSuggestion> {
        val active = activeSeen(ranked, history.seen)
        val available = ranked.filterNot { it.album.id in active }
        val fresh = available.filterNot { it.album.id in history.deferred }
        return fresh.ifEmpty { available }.take(SIZE)
    }

    fun advance(ranked: List<AlbumSuggestion>, history: AlbumBatchHistory, current: List<AlbumSuggestion>): AlbumBatchHistory {
        val currentIds = current.map { it.album.id }.toSet()
        val updated = activeSeen(ranked, history.seen) + currentIds
        return if (ranked.any { it.album.id !in updated }) AlbumBatchHistory(seen = updated)
        else AlbumBatchHistory(deferred = currentIds)
    }
}
