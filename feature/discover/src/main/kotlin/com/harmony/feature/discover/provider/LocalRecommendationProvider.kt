package com.harmony.feature.discover.provider

import com.harmony.core.model.Song
import com.harmony.core.model.SmartPlaylistType
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** A titled row of tracks on the Discover screen. */
data class DiscoverSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
)

/** A genre the user actually has music in, with how many of the sampled tracks carry it. */
data class GenreChip(val name: String, val count: Int)

/**
 * Where Discover's recommendations come from.
 *
 * One implementation today, and it reads nothing but the user's own library
 * and listening history. The interface exists so an external metadata service
 * could be added later as a second implementation — the ViewModel merges a
 * list of providers, so adding one is a DI change rather than a UI rewrite.
 *
 * Note what is absent: no HTTP client, no account, no API key. Nothing here
 * leaves the device, which is also why none of these sections can be
 * "wrong" in the way an external recommender can — they are descriptions of
 * the user's own library, not predictions about it.
 */
interface RecommendationProvider {
    val id: String

    /** Re-emits whenever the underlying library or history changes. */
    fun sections(): Flow<List<DiscoverSection>>
}

/**
 * Recommendations derived entirely from local data.
 *
 * Every section is backed by a Room flow that already exists, so all of this
 * re-computes automatically when a scan finishes or a play is recorded, and
 * none of it touches the filesystem.
 */
class LocalLibraryRecommendationProvider @Inject constructor(
    private val playlists: PlaylistRepository,
    private val history: PlaybackHistoryRepository,
    private val library: LibraryRepository,
) : RecommendationProvider {

    override val id: String = "local-library"

    override fun sections(): Flow<List<DiscoverSection>> = combine(
        playlists.observeSmartPlaylist(SmartPlaylistType.RECENTLY_ADDED, ROW_LIMIT),
        playlists.observeSmartPlaylist(SmartPlaylistType.MOST_PLAYED, REDISCOVER_SCAN),
        playlists.observeSmartPlaylist(SmartPlaylistType.FAVORITES, ROW_LIMIT),
        library.observeRecentDownloads(ROW_LIMIT),
    ) { recentlyAdded, mostPlayed, favorites, downloads ->
        Sections(recentlyAdded, mostPlayed, favorites, downloads)
    }.map { (recentlyAdded, mostPlayed, favorites, downloads) ->
        buildList {
            // Downloads first: a track that arrived in the last few minutes is
            // the one you are most likely to want, and Soulseek/SpotiFLAC
            // arrivals are otherwise indistinguishable from files that were
            // simply rescanned off the SD card.
            if (downloads.isNotEmpty()) {
                add(
                    DiscoverSection(
                        id = "recent-downloads",
                        title = "Just downloaded",
                        subtitle = "Straight from Soulseek and SpotiFLAC",
                        songs = downloads,
                    ),
                )
            }
            if (recentlyAdded.isNotEmpty()) {
                add(
                    DiscoverSection(
                        id = "recently-added",
                        title = "Fresh in your library",
                        subtitle = "Newest arrivals, downloads included",
                        songs = recentlyAdded.take(ROW_LIMIT),
                    ),
                )
            }
            if (mostPlayed.isNotEmpty()) {
                add(
                    DiscoverSection(
                        id = "most-played",
                        title = "On repeat",
                        subtitle = "What you come back to",
                        songs = mostPlayed.take(ROW_LIMIT),
                    ),
                )
            }
            // Rediscover: tracks with real play history that have gone quiet.
            // Computed from the most-played set rather than the whole library,
            // because a track has to have been loved before it can be
            // rediscovered — and because scanning 20k rows for this would be
            // the kind of work the dashboard is supposed to avoid.
            val rediscover = rediscoverFrom(mostPlayed)
            if (rediscover.isNotEmpty()) {
                add(
                    DiscoverSection(
                        id = "rediscover",
                        title = "Rediscover",
                        subtitle = "Loved once, not played in a while",
                        songs = rediscover,
                    ),
                )
            }
            if (favorites.isNotEmpty()) {
                add(
                    DiscoverSection(
                        id = "favorites",
                        title = "Your favourites",
                        subtitle = "Everything you starred",
                        songs = favorites.take(ROW_LIMIT),
                    ),
                )
            }
        }
    }

    private suspend fun rediscoverFrom(candidates: List<Song>): List<Song> {
        if (candidates.isEmpty()) return emptyList()
        val stats = history.behaviorStats(candidates.map { it.id })
        val cutoff = System.currentTimeMillis() - REDISCOVER_QUIET_MS
        return candidates
            .mapNotNull { song ->
                val lastPlayed = stats[song.id]?.lastPlayedAt ?: return@mapNotNull null
                if (lastPlayed < cutoff) song to lastPlayed else null
            }
            // Quietest first: the track you have forgotten hardest.
            .sortedBy { it.second }
            .take(ROW_LIMIT)
            .map { it.first }
    }

    private companion object {
        const val ROW_LIMIT = 20
        /** How deep to look for rediscovery candidates before filtering by recency. */
        const val REDISCOVER_SCAN = 200
        const val REDISCOVER_QUIET_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** Carrier for the four source flows; Triple only holds three. */
private data class Sections(
    val recentlyAdded: List<Song>,
    val mostPlayed: List<Song>,
    val favorites: List<Song>,
    val downloads: List<Song>,
)

/** Genre chips built from whatever tracks the sections already loaded. */
internal fun genreChipsFrom(sections: List<DiscoverSection>, limit: Int = 12): List<GenreChip> {
    val counts = HashMap<String, Int>()
    sections.asSequence()
        .flatMap { it.songs.asSequence() }
        .distinctBy { it.id }
        .forEach { song ->
            song.genre
                ?.split(',', ';', '/', '|')
                ?.map { it.trim() }
                ?.filter { it.length >= 2 }
                ?.forEach { tag ->
                    val key = tag.replaceFirstChar { c -> c.uppercaseChar() }
                    counts[key] = (counts[key] ?: 0) + 1
                }
        }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { GenreChip(it.key, it.value) }
}
