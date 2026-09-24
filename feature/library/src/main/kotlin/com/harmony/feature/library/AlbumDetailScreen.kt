package com.harmony.feature.library

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.harmony.core.model.Song
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.component.amberPalette
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.playback.usecase.PlaySongsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val playSongs: PlaySongsUseCase,
    private val playback: PlaybackController,
    private val fileActions: LibraryFileActions,
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    /** Null until the first read, so the page shows a skeleton rather than "not in your library". */
    val songs: StateFlow<List<Song>?> = libraryRepository.observeSongsByAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Which song is current and whether it is playing, for the row meter and the turning record. */
    val nowPlaying: StateFlow<Pair<Long?, Boolean>> = playback.playerState
        .map { it.currentSong?.id to it.isPlaying }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null to false)

    internal val moreByArtist: StateFlow<List<OtherAlbum>> = songs
        .filterNotNull()
        .map { if (it.isEmpty()) null else AlbumDetailFormat.headline(it).artistLink }
        .distinctUntilChanged()
        .flatMapLatest { artist ->
            if (artist == null) flowOf(emptyList())
            else libraryRepository.observeSongsByArtist(artist).map { AlbumDetailFormat.otherAlbums(it, artist, albumId) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun play(list: List<Song>, index: Int) {
        viewModelScope.launch { playSongs(list, index) }
    }

    /** Shuffle the album: same songs, random order. */
    fun shuffle(list: List<Song>) {
        if (list.isEmpty()) return
        viewModelScope.launch { playSongs(list.shuffled(), 0) }
    }

    /** Insert directly after the current song. */
    fun playNext(song: Song) {
        viewModelScope.launch { playback.addNext(song) }
    }

    /**
     * The whole album right after the current song, in album order. Each
     * insert goes directly after the current song, so inserting last track
     * first leaves them in order.
     */
    fun playAlbumNext(list: List<Song>) {
        viewModelScope.launch { list.asReversed().forEach { playback.addNext(it) } }
    }

    fun addAlbumToQueue(list: List<Song>) {
        viewModelScope.launch { playback.addToQueueAll(list) }
    }

    /** Android asks the user to confirm before any file is deleted. */
    fun delete(list: List<Song>) {
        if (list.isNotEmpty()) fileActions.requestDelete(list)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Album detail. The layout lives in [AlbumDetailContent]; this wires it to
 * the library, the player and the add-to-playlist sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val moreByArtist by viewModel.moreByArtist.collectAsStateWithLifecycle()
    var addToPlaylist by remember { mutableStateOf<List<Long>?>(null) }

    val back by rememberUpdatedState(onBack)
    val openArtist by rememberUpdatedState(onOpenArtist)
    val openAlbum by rememberUpdatedState(onOpenAlbum)
    val actions = remember(viewModel) {
        AlbumDetailActions(
            onBack = { back() },
            onPlay = viewModel::play,
            onShuffle = viewModel::shuffle,
            onPlayNext = viewModel::playNext,
            onAddToPlaylist = { addToPlaylist = it },
            onDelete = viewModel::delete,
            onPlayAlbumNext = viewModel::playAlbumNext,
            onAddAlbumToQueue = viewModel::addAlbumToQueue,
            onOpenArtist = { openArtist(it) },
            onOpenAlbum = { openAlbum(it) },
        )
    }

    AlbumDetailContent(
        ui = AlbumDetailUi(
            songs = songs,
            nowPlayingId = nowPlaying.first,
            isPlaying = nowPlaying.second,
            moreByArtist = moreByArtist,
        ),
        palette = amberPalette(),
        actions = actions,
        bottomPadding = LocalFloatingChromeHeight.current,
    )

    addToPlaylist?.let { ids ->
        ModalBottomSheet(onDismissRequest = { addToPlaylist = null }) {
            AddToPlaylistSheet(songIds = ids, onDismiss = { addToPlaylist = null })
        }
    }
}
