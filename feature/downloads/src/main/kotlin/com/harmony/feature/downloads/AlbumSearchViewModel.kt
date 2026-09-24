package com.harmony.feature.downloads

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.domain.library.repository.AlbumJourneyRepository
import com.harmony.domain.library.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumSearchState(
    val query: String = "",
    val results: List<AlbumEdition> = emptyList(),
    val searching: Boolean = false,
    val openingId: String? = null,
    val searched: Boolean = false,
    val error: String? = null,
) {
    val busy get() = searching || openingId != null
}

@HiltViewModel
class AlbumSearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val metadata: AlbumMetadataClient,
    private val albums: AlbumJourneyRepository,
    private val library: LibraryRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AlbumSearchState(query = savedState["albumQuery"] ?: ""))
    val state = mutableState.asStateFlow()

    fun setQuery(query: String) {
        if (state.value.busy) return
        savedState["albumQuery"] = query.take(200)
        mutableState.value = AlbumSearchState(query = query.take(200))
    }

    fun search() {
        if (state.value.busy) return
        val query = state.value.query.trim()
        if (query.length < 2) {
            mutableState.value = state.value.copy(error = "Enter the album title and artist.")
            return
        }
        mutableState.value = state.value.copy(searching = true, error = null, results = emptyList())
        viewModelScope.launch {
            try {
                mutableState.value = state.value.copy(results = metadata.searchAlbums(query), searched = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(error = e.message ?: "Album search failed. Try again.")
            } finally { mutableState.value = state.value.copy(searching = false) }
        }
    }

    fun open(edition: AlbumEdition, source: DownloadSource, format: SpotiFlacOutputFormat, onOpen: (String) -> Unit) {
        if (state.value.busy || edition !in state.value.results) return
        if (source !in TransferCapableSources) return
        mutableState.value = state.value.copy(openingId = edition.id, error = null)
        viewModelScope.launch {
            try {
                // Reopening an edition must not reset selections, published
                // files, a running job, or a Discover listening journey.
                val existing = albums.journeys.value.firstOrNull { it.editionId == edition.id }
                val id = if (existing != null) existing.id else {
                    val loaded = metadata.load(downloadsAlbumId(edition.id), edition)
                    val songs = library.observeSongs().first()
                    val latest = albums.journeys.value.firstOrNull { it.editionId == edition.id }
                    if (latest != null) latest.id else {
                        albums.save(loaded.copy(source = source.name,
                            format = AlbumDownloadPolicy.formatFor(source, format).name,
                            tracks = loaded.tracks.map { track ->
                                AlbumTrackMatcher.match(track, loaded.title, songs)?.let { song ->
                                    track.copy(uri = song.uri, status = "Already in library")
                                } ?: track
                            }))
                        loaded.id
                    }
                }
                onOpen(id) // Navigation happens only after the full tracklist is persisted.
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(error = e.message ?: "Could not open this album. Retry.")
            } finally { mutableState.value = state.value.copy(openingId = null) }
        }
    }
}
