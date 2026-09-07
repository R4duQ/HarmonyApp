package com.harmony.feature.discover.provider

import com.harmony.feature.discover.model.*
import kotlin.random.Random

/** Pure transitions: votes, progress and the reveal are committed as one preference update. */
object SwipeRoundEngine {
    fun rate(preferences: DiscoveryPreferences, songId: String, liked: Boolean?): DiscoveryPreferences {
        require(songId in SongTasteEngine.songIds)
        val round = preferences.round
        if (liked == null) {
            // Only the current round's final choice can be undone, including the vote that revealed an album.
            if (round.songIds.lastOrNull() != songId) return preferences
            return preferences.copy(likedSongs = preferences.likedSongs - songId,
                dislikedSongs = preferences.dislikedSongs - songId,
                round = round.copy(songIds = round.songIds.dropLast(1), completed = false, albumId = null))
        }
        if (round.completed || songId in preferences.likedSongs || songId in preferences.dislikedSongs) return preferences
        val next = preferences.copy(
            likedSongs = if (liked) preferences.likedSongs + songId else preferences.likedSongs,
            dislikedSongs = if (!liked) preferences.dislikedSongs + songId else preferences.dislikedSongs,
            round = round.copy(songIds = round.songIds + songId),
        )
        return if (next.round.songIds.size >= SwipeRound.SIZE) finish(next) else next
    }

    fun next(preferences: DiscoveryPreferences): DiscoveryPreferences {
        if (!preferences.round.completed || !hasUnratedSongs(preferences)) return preferences
        return preferences.copy(
            round = SwipeRound(number = preferences.round.number + 1),
            recommendedAlbums = preferences.recommendedAlbums + listOfNotNull(preferences.round.albumId),
        )
    }

    fun canFinishRemaining(preferences: DiscoveryPreferences): Boolean = !preferences.round.completed &&
        (preferences.likedSongs.isNotEmpty() || preferences.dislikedSongs.isNotEmpty()) &&
        !hasUnratedSongs(preferences)

    fun hasUnratedSongs(preferences: DiscoveryPreferences): Boolean =
        SongTasteEngine.songIds.any { it !in preferences.likedSongs && it !in preferences.dislikedSongs }

    /** A finite catalogue can end with fewer than ten unrated songs. It must not strand a round. */
    fun finishRemaining(preferences: DiscoveryPreferences): DiscoveryPreferences =
        if (canFinishRemaining(preferences)) finish(preferences) else preferences

    private fun finish(preferences: DiscoveryPreferences): DiscoveryPreferences = preferences.copy(
        round = preferences.round.copy(completed = true, albumId = RoundAlbumRecommender.choose(preferences)?.id),
    )
}

object RoundAlbumRecommender {
    private fun recent(preferences: DiscoveryPreferences): DiscoveryPreferences {
        val ids = preferences.round.songIds.toSet()
        return DiscoveryPreferences(likedSongs = preferences.likedSongs.intersect(ids),
            dislikedSongs = preferences.dislikedSongs.intersect(ids))
    }

    fun choose(preferences: DiscoveryPreferences): DiscoverAlbum? {
        val albums = ShflAlbumCatalog.albums.filterNot { it.id in preferences.listened }
        if (albums.isEmpty()) return null
        val round = recent(preferences)
        val latest = SongTasteEngine.scores(albums, round)
        val history = SongTasteEngine.scores(albums, preferences)
        val recentLikes = songs(round.likedSongs)
        val recentDislikes = songs(round.dislikedSongs)
        // Do not lead with an album explicitly rejected this round if other options exist.
        val rejected = recentDislikes.map { it.album.id }.toSet() - recentLikes.map { it.album.id }.toSet()
        val candidates = albums.filterNot { it.id in rejected }.ifEmpty { albums }
        val positive = candidates.filter { latest.getValue(it.id) > 0 || history.getValue(it.id) > 0 }
            .ifEmpty { candidates }
        val fresh = positive.filterNot { it.id in preferences.recommendedAlbums }.ifEmpty { positive }
        // Current choices carry three times the weight of history. A persisted ID freezes ties after reveal.
        return fresh.shuffled(Random(preferences.round.number)).maxByOrNull {
            latest.getValue(it.id) * 3 + history.getValue(it.id)
        }
    }

    fun explain(preferences: DiscoveryPreferences): ExplainedAlbum? {
        if (!preferences.round.completed) return null
        val album = ShflAlbumCatalog.albums.firstOrNull { it.id == preferences.round.albumId } ?: return null
        val latest = recent(preferences)
        val recentLikes = songs(latest.likedSongs)
        val earlierLikes = songs(preferences.likedSongs - latest.likedSongs)
        val recentGenres = positiveGenres(album, latest)
        val earlierGenres = positiveGenres(album, preferences)
        fun supporting(likes: List<TasteSong>, genres: Set<AlbumGenre>) = likes.filter {
            it.album.id == album.id || it.album.artist == album.artist || it.album.genres.any { g -> g in genres }
        }.sortedByDescending { if (it.album.id == album.id) 3 else if (it.album.artist == album.artist) 2 else 1 }.take(2)
        val now = supporting(recentLikes, recentGenres)
        val earlier = supporting(earlierLikes, earlierGenres)
        val evidence = now.ifEmpty { earlier }
        val direct = evidence.any { it.album.id == album.id }
        val artist = evidence.any { it.album.artist == album.artist }
        val styles = (if (now.isNotEmpty()) recentGenres else earlierGenres).take(2).joinToString(" and ") { it.label }
        val reason = when {
            now.isNotEmpty() -> "You liked ${titles(now)} this round. " + when {
                direct -> "This is the record behind the music you liked — a chance to discover what surrounds it."
                artist -> "Your interest in ${album.artist} makes this record a natural next full listen."
                else -> "$styles came through positively in your choices, putting this album ahead of less compatible styles."
            }
            earlier.isNotEmpty() -> (if (preferences.round.songIds.isEmpty()) "This pick draws on your saved song choices. "
                else if (recentLikes.isEmpty()) "No likes in this round, so this pick draws on your earlier choices. "
                else "Your latest choices were mixed; your earlier likes provide another lead. ") +
                "You liked ${titles(earlier)}" + when {
                    direct -> "; this album lets you explore the rest of that record."
                    artist -> ", which points back to ${album.artist}."
                    else -> ", connecting this record to your interest in $styles."
                }
            recentLikes.isEmpty() -> (if (preferences.round.songIds.isEmpty()) "Your saved choices contain no likes. "
                else "You passed on this round's songs. ") +
                "This is an exploratory pick, giving less weight to the styles you rejected. " +
                "A few likes would give Harmony a clearer lead."
            else -> "Your likes point in several directions, without a clear link to another unplayed album in this selection. " +
                "Treat this as an exploratory listen while Harmony learns more from your next round."
        }
        return ExplainedAlbum(album, reason, evidence, evidence.isEmpty())
    }

    private fun songs(ids: Set<String>) = SongTasteEngine.songs.filter { it.id in ids }
    private fun titles(songs: List<TasteSong>) = songs.joinToString(" and ") { "“${it.title}” by ${it.album.artist}" }
    private fun positiveGenres(album: DiscoverAlbum, preferences: DiscoveryPreferences): Set<AlbumGenre> {
        val likes = songs(preferences.likedSongs)
        val dislikes = songs(preferences.dislikedSongs)
        return album.genres.filterTo(linkedSetOf()) { genre ->
            likes.count { genre in it.album.genres } > dislikes.count { genre in it.album.genres }
        }
    }
}
