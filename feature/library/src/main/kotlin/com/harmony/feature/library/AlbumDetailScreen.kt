package com.harmony.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSongCard
import com.harmony.core.ui.component.EmptyState
import com.harmony.core.ui.component.amberPalette
import com.harmony.core.ui.component.formatLongDuration
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.playback.usecase.PlaySongsUseCase
import com.harmony.core.ui.component.DetailCardScaffold
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val playSongs: PlaySongsUseCase,
    private val playback: com.harmony.domain.playback.PlaybackController,
    private val fileActions: LibraryFileActions,
) : ViewModel() {

    fun deleteSong(song: Song) { fileActions.requestDelete(listOf(song)) }
    fun deleteAlbum(songs: List<Song>) { fileActions.requestDelete(songs) }

    /** Insert directly AFTER the current song ("play next"), not at the end. */
    fun addToQueue(song: Song) {
        viewModelScope.launch { playback.addNext(song) }
    }
    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    val songs: StateFlow<List<Song>> = libraryRepository.observeSongsByAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play(list: List<Song>, index: Int) = viewModelScope.launch { playSongs(list, index) }

    /** Shuffle the album: same list, random entry point. */
    fun shuffle(list: List<Song>) {
        if (list.isEmpty()) return
        viewModelScope.launch { playSongs(list.shuffled(), 0) }
    }
}

/**
 * Album detail in the amber editorial treatment, matching Library and the
 * artist page: framed cover, album title, artist and stats, then the track
 * list as outlined cards.
 *
 * Title and artist come from the songs themselves rather than from an
 * album-by-id query — every song already carries `album` and `albumArtist`,
 * and the flow re-emits on any change, so there's nothing to keep in sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(viewModel: AlbumDetailViewModel = hiltViewModel()) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    var addToPlaylistSongId by remember { mutableStateOf<Long?>(null) }
    val palette = amberPalette()

    val first = songs.firstOrNull()
    val totalMs = remember(songs) { songs.sumOf { it.durationMs } }

    DetailCardScaffold(
        title = first?.album ?: "Album",
        palette = palette,
        modifier = Modifier.background(palette.field),
        contentPadding = PaddingValues(bottom = LocalFloatingChromeHeight.current),
        header = {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp)) {
                Text(
                    "ALBUM",
                    fontSize = 11.sp,
                    letterSpacing = 2.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(96.dp)
                            .border(1.5.dp, palette.line, RoundedCornerShape(16.dp)),
                    ) {
                        Artwork(
                            artworkUri = first?.artworkUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(1.5.dp),
                            cornerRadius = 14.dp,
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                    ) {
                        Text(
                            first?.album ?: "Album",
                            fontSize = 26.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.7).sp,
                            color = palette.ink,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            first?.albumArtist ?: first?.artist ?: "",
                            fontSize = 13.sp,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        Text(
                            "${songs.size} songs  •  ${formatLongDuration(totalMs)}",
                            fontSize = 12.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (songs.isNotEmpty()) {
                    Row(Modifier.padding(top = 14.dp)) {
                        EditorialPill(
                            text = "Play",
                            icon = Icons.Rounded.PlayArrow,
                            onClick = { viewModel.play(songs, 0) },
                            palette = palette,
                        )
                        Box(Modifier.size(10.dp))
                        EditorialPill(
                            text = "Shuffle",
                            icon = Icons.Rounded.Shuffle,
                            onClick = { viewModel.shuffle(songs) },
                            palette = palette,
                        )
                    }
                }
            }
        },
    ) {
        if (songs.isEmpty()) {
            item {
                EmptyState("Nothing here", "This album has no tracks in your library.")
            }
        }

        if (songs.isNotEmpty()) item {
            androidx.compose.material3.TextButton(onClick = { viewModel.deleteAlbum(songs) }, modifier = Modifier.padding(horizontal = 22.dp)) {
                Text("Delete album from phone", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            EditorialSongCard(
                song = song,
                palette = palette,
                onClick = { viewModel.play(songs, index) },
                onPlayNext = { viewModel.addToQueue(song) },
                onAddToPlaylist = { addToPlaylistSongId = song.id },
                onRemove = { viewModel.deleteSong(song) },
                removeLabel = "Delete from phone",
            )
        }
    }

    addToPlaylistSongId?.let { id ->
        ModalBottomSheet(onDismissRequest = { addToPlaylistSongId = null }) {
            AddToPlaylistSheet(songId = id, onDismiss = { addToPlaylistSongId = null })
        }
    }
}
