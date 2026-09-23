package com.harmony.feature.playlists

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.core.ui.component.MiniPlayerClearance
import com.harmony.core.ui.component.greenPalette
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.playback.usecase.PlaySongsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Detail for both user playlists (playlistId) and smart playlists
 * (smartType) — one screen, because they render identically and only the
 * source flow differs. Route args decide which flow feeds it.
 *
 * Editing (add / remove / reorder / export) is offered ONLY for user
 * playlists: smart playlists are live queries, so "remove" or "reorder"
 * there would be meaningless — the query would just recompute the same
 * result.
 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val playSongs: PlaySongsUseCase,
    private val playback: com.harmony.domain.playback.PlaybackController,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle["playlistId"] ?: -1L
    private val smartTypeArg: String? = savedStateHandle["smartType"]

    /**
     * Parsed defensively: SmartPlaylistType.valueOf on an unknown name (an
     * old deep link, a renamed enum) used to crash the page on open. Now it
     * shows the "playlist is gone" state instead.
     */
    private val smartType: SmartPlaylistType? =
        smartTypeArg?.let { name -> SmartPlaylistType.entries.firstOrNull { it.name == name } }

    /** True for real, editable playlists; false for the live smart queries. */
    val isEditable: Boolean = smartTypeArg == null && savedStateHandle.get<Long>("playlistId") != null

    val canExport: Boolean get() = isEditable

    private val songs: Flow<List<Song>> = when {
        smartType != null -> playlistRepository.observeSmartPlaylist(smartType)
        smartTypeArg != null -> flowOf(emptyList())
        else -> playlistRepository.observePlaylistSongs(playlistId)
    }

    /** (title, missing). */
    private val header: Flow<Pair<String, Boolean>> = when {
        smartType != null -> flowOf(smartType.displayTitle() to false)
        smartTypeArg != null -> flowOf("Playlist" to true)
        else -> playlistRepository.observePlaylists()
            .map { all ->
                val found = all.firstOrNull { it.id == playlistId }
                (found?.name ?: "Playlist") to (found == null)
            }
    }

    /**
     * Nothing is emitted until the songs AND the header have both arrived,
     * so the initial value below — songs = null — is the loading state, and
     * "empty" only ever means genuinely empty. (The old page started from
     * emptyList(), so every open flashed "This playlist is empty" first.)
     * No onStart placeholders either: after the 5s stop timeout they would
     * re-emit null and flash the skeleton when coming back from Now Playing.
     */
    internal val uiState: StateFlow<PlaylistDetailUi> = combine(
        songs,
        header,
        playback.playerState.map { it.currentSong?.id }.distinctUntilChanged(),
    ) { songs, (title, missing), nowPlaying ->
        PlaylistDetailUi(
            title = title,
            smartType = smartType,
            editable = isEditable && !missing,
            songs = if (missing) emptyList() else songs,
            missing = missing,
            nowPlayingId = nowPlaying,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaylistDetailUi(
            title = smartType?.displayTitle(),
            smartType = smartType,
            editable = isEditable,
            songs = null,
        ),
    )

    fun play(list: List<Song>, index: Int) {
        if (index !in list.indices) return
        viewModelScope.launch { playSongs(list, index) }
    }

    /** Same list, random order — matches the album page's Shuffle. */
    fun shuffle(list: List<Song>) {
        if (list.isEmpty()) return
        viewModelScope.launch { playSongs(list.shuffled(), 0) }
    }

    /** Insert directly AFTER the current song ("play next"), not at the end. */
    fun addToQueue(song: Song) {
        viewModelScope.launch { playback.addNext(song) }
    }

    fun addSongs(songIds: List<Long>) {
        if (!isEditable || songIds.isEmpty()) return
        viewModelScope.launch { playlistRepository.addSongs(playlistId, songIds) }
    }

    fun removeSong(songId: Long) {
        if (!isEditable) return
        viewModelScope.launch { playlistRepository.removeSong(playlistId, songId) }
    }

    /**
     * One write per drop. The reorder is previewed locally while dragging;
     * writing on every row crossed round-tripped through Room and re-emitted
     * the list mid-gesture, which is what made the drag fight the finger.
     */
    fun moveSong(from: Int, to: Int) {
        if (!isEditable) return
        viewModelScope.launch { playlistRepository.moveSong(playlistId, from, to) }
    }

    suspend fun exportContent(): String = playlistRepository.exportM3u(playlistId)

    /** "Road Trip.m3u" rather than every export being called "playlist.m3u". */
    fun exportFileName(): String {
        val title = uiState.value.title?.takeIf { it.isNotBlank() } ?: "playlist"
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().take(80)
        return "${safe.ifEmpty { "playlist" }}.m3u"
    }
}

/**
 * Playlist detail. [onBack] must pop THIS page off the real back stack (the
 * caller guards against popping twice); the page calls it at most once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddSongs by rememberSaveable { mutableStateOf(false) }
    val palette = greenPalette()
    val currentOnBack by rememberUpdatedState(onBack)

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Off the main thread (it's file I/O through a content provider,
            // possibly a cloud one), and reported: a failed export used to
            // either freeze the UI or fail silently.
            val ok = runCatching {
                val m3u = viewModel.exportContent()
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("No output stream for $uri")
                    out.use { it.write(m3u.toByteArray()) }
                }
            }.isSuccess
            Toast.makeText(
                context,
                if (ok) "Playlist exported" else "Couldn't export the playlist",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val actions = remember(viewModel) {
        PlaylistDetailActions(
            onBack = { currentOnBack() },
            onPlay = viewModel::play,
            onShuffle = viewModel::shuffle,
            onPlayNext = viewModel::addToQueue,
            onRemove = { viewModel.removeSong(it.id) },
            onMove = viewModel::moveSong,
            onAddSongs = { showAddSongs = true },
            onExport = { if (viewModel.canExport) exportPicker.launch(viewModel.exportFileName()) },
        )
    }

    PlaylistDetailContent(
        ui = ui,
        palette = palette,
        actions = actions,
        bottomPadding = MiniPlayerClearance,
        // Saveable: coming back from Now Playing lands on the same row.
        listState = rememberLazyListState(),
    )

    if (showAddSongs) {
        ModalBottomSheet(onDismissRequest = { showAddSongs = false }) {
            AddSongsToPlaylistSheet(
                onConfirm = { ids ->
                    viewModel.addSongs(ids)
                    showAddSongs = false
                },
                onDismiss = { showAddSongs = false },
            )
        }
    }
}

internal fun SmartPlaylistType.displayTitle(): String = when (this) {
    SmartPlaylistType.FAVORITES -> "Favorites"
    SmartPlaylistType.MOST_PLAYED -> "Most Played"
    SmartPlaylistType.RECENTLY_ADDED -> "Recently Added"
    SmartPlaylistType.RECENTLY_PLAYED -> "Recently Played"
    SmartPlaylistType.HIGHEST_ENERGY -> "Highest Energy"
    SmartPlaylistType.LOWEST_ENERGY -> "Lowest Energy"
}
