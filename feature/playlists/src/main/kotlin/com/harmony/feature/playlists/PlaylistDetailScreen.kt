package com.harmony.feature.playlists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSongCard
import com.harmony.core.ui.component.EmptyState
import com.harmony.core.ui.component.formatLongDuration
import com.harmony.core.ui.component.greenPalette
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.playback.usecase.PlaySongsUseCase
import com.harmony.core.ui.component.MiniPlayerClearance
import com.harmony.core.ui.component.DetailCardScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Detail for both user playlists (playlistId) and smart playlists
 * (smartType) — one screen, because they render identically and only the
 * source flow differs. Route args decide which flow feeds it.
 *
 * Editing (add / remove / reorder) is offered ONLY for user playlists:
 * smart playlists are live queries, so "remove" or "reorder" there would be
 * meaningless — the query would just recompute the same result.
 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val playSongs: PlaySongsUseCase,
    private val playback: com.harmony.domain.playback.PlaybackController,
) : ViewModel() {

    /** Insert directly AFTER the current song ("play next"), not at the end. */
    fun addToQueue(song: Song) {
        viewModelScope.launch { playback.addNext(song) }
    }

    private val playlistId: Long = savedStateHandle["playlistId"] ?: -1L
    private val smartType: String? = savedStateHandle["smartType"]

    /** True for real, editable playlists; false for the live smart queries. */
    val isEditable: Boolean = smartType == null && playlistId > 0

    val songs: StateFlow<List<Song>> = when {
        smartType != null ->
            playlistRepository.observeSmartPlaylist(SmartPlaylistType.valueOf(smartType))
        else -> playlistRepository.observePlaylistSongs(playlistId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The screen needs its own name in the header now that it has one. Smart
     * playlists get it from the enum; user playlists read it off the same
     * observePlaylists flow the list screen uses, so a rename elsewhere shows
     * up here without any extra plumbing.
     */
    val title: StateFlow<String> = if (smartType != null) {
        MutableStateFlow(SmartPlaylistType.valueOf(smartType).displayTitle())
    } else {
        playlistRepository.observePlaylists()
            .map { all -> all.firstOrNull { it.id == playlistId }?.name ?: "Playlist" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Playlist")
    }

    fun play(list: List<Song>, index: Int) = viewModelScope.launch { playSongs(list, index) }

    fun addSongs(songIds: List<Long>) {
        if (!isEditable || songIds.isEmpty()) return
        viewModelScope.launch { playlistRepository.addSongs(playlistId, songIds) }
    }

    fun removeSong(songId: Long) {
        if (!isEditable) return
        viewModelScope.launch { playlistRepository.removeSong(playlistId, songId) }
    }

    fun moveSong(from: Int, to: Int) {
        if (!isEditable) return
        viewModelScope.launch { playlistRepository.moveSong(playlistId, from, to) }
    }

    val canExport: Boolean get() = playlistId > 0

    suspend fun exportContent(): String = playlistRepository.exportM3u(playlistId)
}

/**
 * Playlist detail in the green editorial treatment, matching the Playlists
 * list it opens from.
 *
 * The old header was two bare TextButtons ("Add songs", "Export M3U") on a
 * blank surface, which told you nothing about which playlist you were in.
 * It's now a proper header: eyebrow, the playlist's name at display size,
 * song count and running time, and the Play pill — with add and export as
 * circled buttons on the right, matching the Playlists screen's own header.
 *
 * Reorder still works the same way: long-press the grip, which is scoped to
 * the handle alone so it can't fight the list's scrolling. Remove moved from
 * a permanent ✕ per row into each card's ⋮ menu — a destructive control
 * shouldn't be the easiest thing to hit on a row you're trying to drag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(viewModel: PlaylistDetailViewModel = hiltViewModel()) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddSongs by remember { mutableStateOf(false) }
    val palette = greenPalette()

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val m3u = viewModel.exportContent()
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(m3u.toByteArray())
                }
            }
        }
    }

    // Drag-reorder state (user playlists only).
    //
    // The reorder is previewed LOCALLY and written once on drop. The previous
    // version called moveSong on every row crossed, so each step round-tripped
    // through Room and re-emitted the list mid-gesture — the list rearranging
    // underneath your finger is what made it feel like it was fighting you.
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var dragFrom by remember { mutableIntStateOf(-1) }   // where the drag began
    var dragTo by remember { mutableIntStateOf(-1) }     // where it sits now
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val totalMs = remember(songs) { songs.sumOf { it.durationMs } }

    // Hoisted out of the (former) else-branch: `display` needs remember,
    // which cannot be called from inside a LazyListScope block.
    val display = remember(songs, dragFrom, dragTo) {
        if (dragFrom < 0 || dragTo < 0 || dragFrom == dragTo) {
            songs
        } else {
            songs.toMutableList().apply { add(dragTo, removeAt(dragFrom)) }
        }
    }

    DetailCardScaffold(
        title = title,
        palette = palette,
        modifier = Modifier.background(palette.field),
        listState = listState,
        contentPadding = PaddingValues(top = 10.dp, bottom = MiniPlayerClearance),
        header = {
        Column {
        // ---- Header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 20.dp, top = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (viewModel.isEditable) "PLAYLIST" else "SMART PLAYLIST",
                    fontSize = 11.sp,
                    letterSpacing = 2.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                Text(
                    title,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.9).sp,
                    color = palette.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    if (songs.isEmpty()) {
                        if (viewModel.isEditable) "No songs yet" else "Updates itself"
                    } else {
                        "${songs.size} songs  •  ${formatLongDuration(totalMs)}" +
                            if (viewModel.isEditable) "" else "  •  updates itself"
                    },
                    fontSize = 12.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            ) {
                if (viewModel.canExport) {
                    EditorialCircleButton(
                        onClick = { exportPicker.launch("playlist.m3u") },
                        contentDescription = "Export as M3U file",
                        palette = palette,
                    ) {
                        Icon(
                            Icons.Rounded.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                if (viewModel.isEditable) {
                    EditorialCircleButton(
                        onClick = { showAddSongs = true },
                        contentDescription = "Add songs",
                        palette = palette,
                        filled = true,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }
        }

        if (songs.isNotEmpty()) {
            Box(Modifier.padding(start = 22.dp, top = 14.dp)) {
                EditorialPill(
                    text = "Play",
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { viewModel.play(songs, 0) },
                    palette = palette,
                )
            }
        }

        }
        },
    ) {
        if (songs.isEmpty()) {
            // Message depends on the playlist KIND — the energy-analysis text
            // is nonsense for a user playlist you just created and explains
            // nothing about how to fill it.
            item {
            if (viewModel.isEditable) {
                Column(
                    Modifier
                        // fillMaxWidth + a fixed height, NOT fillMaxSize:
                        // this now lives inside a LazyColumn item, where the
                        // height constraint is unbounded and fillMaxSize
                        // throws.
                        .fillMaxWidth()
                        .height(320.dp)
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "This playlist is empty",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                    )
                    Text(
                        "Add songs from your library, or use the ⋮ menu on any song elsewhere in the app.",
                        fontSize = 13.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    EditorialPill(
                        text = "Add songs",
                        icon = Icons.Rounded.Add,
                        onClick = { showAddSongs = true },
                        palette = palette,
                    )
                }
            } else {
                EmptyState(
                    "Nothing here yet",
                    "Energy-based playlists fill in as audio analysis completes in the background.",
                )
            }
            }
        } else {
                itemsIndexed(display, key = { _, s -> s.id }) { index, song ->
                    val isDragging = index == dragTo && dragFrom >= 0
                    // The gesture must read the row's CURRENT index and the
                    // list's CURRENT length without those values keying the
                    // pointerInput. Keying on index restarts the pointer
                    // handler the instant the row changes slot, which
                    // cancels the drag one position in — the row appearing
                    // to freeze mid-move.
                    val currentIndex by rememberUpdatedState(index)
                    val lastIndex by rememberUpdatedState(display.lastIndex)
                    EditorialSongCard(
                        song = song,
                        palette = palette,
                        onClick = { viewModel.play(display, index) },
                        onPlayNext = { viewModel.addToQueue(song) },
                        onRemove = if (viewModel.isEditable) {
                            { viewModel.removeSong(song.id) }
                        } else null,
                        removeLabel = "Remove from playlist",
                        dragHandle = if (!viewModel.isEditable) null else {
                            {
                                Box(
                                    // 44dp: a 22dp icon is well under the
                                    // minimum comfortable touch target, and
                                    // this one has to be hit precisely enough
                                    // to start a drag rather than a tap.
                                    modifier = Modifier
                                        .size(44.dp)
                                        // Keyed on the song, which doesn't
                                        // change while it's being dragged.
                                        .pointerInput(song.id) {
                                            var rowHeight = 1f
                                            // Immediate drag, not
                                            // after-long-press: the handle is
                                            // a dedicated target, so there's
                                            // nothing to disambiguate from and
                                            // no reason to make the user wait.
                                            detectDragGestures(
                                                onDragStart = {
                                                    dragFrom = currentIndex
                                                    dragTo = currentIndex
                                                    dragOffset = 0f
                                                    rowHeight = listState.layoutInfo
                                                        .visibleItemsInfo
                                                        .firstOrNull { it.index == currentIndex }
                                                        ?.size?.toFloat() ?: 1f
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.y
                                                    while (dragOffset > rowHeight / 2 &&
                                                        dragTo < lastIndex
                                                    ) {
                                                        dragTo += 1
                                                        dragOffset -= rowHeight
                                                        haptics.performHapticFeedback(
                                                            HapticFeedbackType.TextHandleMove
                                                        )
                                                    }
                                                    while (dragOffset < -rowHeight / 2 &&
                                                        dragTo > 0
                                                    ) {
                                                        dragTo -= 1
                                                        dragOffset += rowHeight
                                                        haptics.performHapticFeedback(
                                                            HapticFeedbackType.TextHandleMove
                                                        )
                                                    }
                                                },
                                                // One write, on drop.
                                                onDragEnd = {
                                                    if (dragFrom >= 0 && dragTo >= 0 &&
                                                        dragFrom != dragTo
                                                    ) {
                                                        viewModel.moveSong(dragFrom, dragTo)
                                                    }
                                                    dragFrom = -1; dragTo = -1; dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    dragFrom = -1; dragTo = -1; dragOffset = 0f
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        contentDescription = "Reorder ${song.title}",
                                        tint = if (isDragging) palette.ink else palette.muted,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffset else 0f
                            },
                    )
                }
        }
    }

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
