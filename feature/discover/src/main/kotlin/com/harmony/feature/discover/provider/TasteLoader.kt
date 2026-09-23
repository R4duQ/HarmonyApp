package com.harmony.feature.discover.provider

import com.harmony.core.model.Song
import com.harmony.domain.library.discovery.TasteInputs
import com.harmony.domain.library.discovery.TasteProfile
import com.harmony.domain.library.discovery.TasteProfileBuilder
import com.harmony.domain.library.repository.FavoritesRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.library.repository.SongDiscoveryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Reads the signals the app already stores and builds the profile off the
 * main thread. A signal that can't be read is skipped, not guessed.
 */
class TasteLoader @Inject constructor(
    private val history: PlaybackHistoryRepository,
    private val favorites: FavoritesRepository,
    private val playlists: PlaylistRepository,
) {
    suspend fun load(
        library: List<Song>,
        state: SongDiscoveryState,
        pickedArtists: List<String>,
        pickedGenres: List<String>,
        now: Long,
    ): TasteProfile = withContext(Dispatchers.Default) {
        val events = read { history.listeningEvents(EVENT_LIMIT) }.orEmpty()
        val favoriteIds = read { favorites.observeFavorites().first().map { it.id }.toSet() }.orEmpty()
        val playlistIds = read { playlists.userPlaylistSongIds() }.orEmpty()
        TasteProfileBuilder.build(TasteInputs(library, events, favoriteIds, playlistIds,
            state.votes.takeLast(500), state.feedback, pickedArtists, pickedGenres, now))
    }

    private suspend fun <T> read(block: suspend () -> T): T? = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private companion object { const val EVENT_LIMIT = 5_000 }
}
