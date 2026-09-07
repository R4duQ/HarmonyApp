package com.harmony.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.harmony.core.model.Album
import com.harmony.core.model.Artist
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.SearchResults
import com.harmony.domain.playback.usecase.PlaySongsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the whole library area (tabs + search). Each tab is its own
 * stateIn-cached flow: Room emits on any change, so the UI is always current
 * with no refresh logic anywhere. WhileSubscribed(5s) keeps flows warm
 * across config changes but releases DB observers when the user leaves.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playSongs: PlaySongsUseCase,
    private val playback: com.harmony.domain.playback.PlaybackController,
    private val fileActions: LibraryFileActions,
) : ViewModel() {

    fun deleteSong(song: Song) { fileActions.requestDelete(listOf(song)) }

    /**
     * Insert directly AFTER the current song ("play next") rather than
     * appending to the end — a swipe means "I want this one soon," and
     * burying it behind a long queue defeats the point.
     */
    fun addToQueue(song: Song) {
        viewModelScope.launch { playback.addNext(song) }
    }

    /** Paged: the songs tab must scroll 20k rows without holding them all in memory. */
    val songsPaged: Flow<PagingData<Song>> =
        libraryRepository.observeSongsPaged().cachedIn(viewModelScope)

    val albums: StateFlow<List<Album>> = libraryRepository.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<Artist>> = libraryRepository.observeArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /**
     * Trimming here rather than in the repository means "bea" and "bea "
     * are the same query, so the trailing space a phone keyboard inserts
     * after autocomplete does not re-run the whole search.
     */
    private val trimmedQuery: Flow<String> = _searchQuery
        .map { it.trim() }
        .distinctUntilChanged()

    /**
     * The results together with the query they belong to. Keeping the two
     * paired is what lets the UI tell "no matches" apart from "matches for
     * the query you were typing a moment ago" — see [isSearching].
     */
    private data class QueryResults(val query: String, val results: SearchResults)

    private val queryResults: StateFlow<QueryResults> = trimmedQuery
        // Clearing the field should empty the list instantly; only real
        // typing is worth waiting on. 90ms rather than the old 150ms: with
        // the FTS prefix query fixed the first pass is an index lookup, so a
        // shorter wait no longer costs a full scan per character.
        .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .flatMapLatest { query ->
            if (query.isEmpty()) flowOf(QueryResults("", SearchResults()))
            else libraryRepository.search(query).map { QueryResults(query, it) }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            QueryResults("", SearchResults()),
        )

    val searchResults: StateFlow<SearchResults> = queryResults
        .map { it.results }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /**
     * True while the text in the field is ahead of the results on screen.
     *
     * The previous list stays visible during that window instead of being
     * cleared, so typing reads as a list narrowing down rather than as it
     * blanking and refilling on every character.
     */
    val isSearching: StateFlow<Boolean> = combine(trimmedQuery, queryResults) { typed, shown ->
        typed.isNotEmpty() && typed != shown.query
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /** Tap a song in a fully-loaded list: that list becomes the queue, starting there. */
    fun onSongClick(list: List<Song>, index: Int) {
        viewModelScope.launch { playSongs(list, index) }
    }

    /**
     * Tap in the PAGED all-songs list: we don't have the full 20k list in
     * memory and don't want it. Queue a window around the tapped song
     * (it + the next QUEUE_WINDOW alphabetical successors from the DB);
     * Smart Shuffle or repeat-all takes over past the window. This bounds
     * queue memory instead of cloning the library into the player.
     */
    fun onPagedSongClick(song: Song) {
        viewModelScope.launch {
            val all = libraryRepository.observeSongs()
            // Single snapshot, windowed immediately; the flow itself is cold.
            all.first().let { snapshot ->
                val index = snapshot.indexOfFirst { it.id == song.id }
                if (index < 0) return@let
                val window = snapshot.subList(
                    index,
                    (index + QUEUE_WINDOW).coerceAtMost(snapshot.size),
                )
                playSongs(window.toList(), 0)
            }
        }
    }

    private companion object {
        const val QUEUE_WINDOW = 500
        const val SEARCH_DEBOUNCE_MS = 90L
    }
}
