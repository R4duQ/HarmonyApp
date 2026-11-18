package com.harmony.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.amberPalette
import com.harmony.core.ui.component.formatLongDuration
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.playback.usecase.PlaySongsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val playSongs: PlaySongsUseCase,
    private val playback: com.harmony.domain.playback.PlaybackController,
) : ViewModel() {

    /** Insert directly AFTER the current song ("play next"), not at the end. */
    fun addToQueue(song: Song) {
        viewModelScope.launch { playback.addNext(song) }
    }

    val artistName: String = android.net.Uri.decode(checkNotNull(savedStateHandle["artistName"]))

    val songs: StateFlow<List<Song>> = libraryRepository.observeSongsByArtist(artistName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play(list: List<Song>, index: Int) = viewModelScope.launch { playSongs(list, index) }
}

/** One row of the artist page: an album with its aggregate stats. */
private data class ArtistAlbum(
    val albumId: Long,
    val name: String,
    val artworkUri: String?,
    val songs: List<Song>,
) {
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }
}

/**
 * The artist page: back, the artist's name with how much of them you own,
 * Play all and Shuffle, then the albums newest first as outlined cards
 * (framed artwork, "2021 · 10 songs · 36min", circled play).
 *
 * Albums are derived by grouping the artist's songs client-side (keeping
 * disc/track order within each group) rather than adding an
 * albums-by-artist query to LibraryRepository: the songs flow already
 * carries every field the cards need, an artist's catalog is small, and
 * Room re-emits the flow on any change so the grouping stays current for
 * free.
 *
 * Tap targets: the card opens the album page; the circle plays the album
 * from the top. The per-song list (with play-next and add-to-playlist)
 * lives one tap away on the album page, so this screen doesn't repeat it.
 */
@Composable
fun ArtistDetailScreen(
    onAlbumClick: (Long) -> Unit = {},
    /** The way back, shown as on the album page; null leaves it to the system Back. */
    onBack: (() -> Unit)? = null,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val palette = amberPalette()

    val albums = remember(songs) {
        songs
            .sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
            .groupBy { it.albumId }
            .map { (albumId, albumSongs) ->
                ArtistAlbum(
                    albumId = albumId,
                    name = albumSongs.first().album,
                    artworkUri = albumSongs.firstNotNullOfOrNull { it.artworkUri },
                    songs = albumSongs,
                )
            }
            // Newest release first, undated albums last — the order a
            // listener thinks of a discography in.
            .sortedByDescending { album -> album.songs.firstNotNullOfOrNull { it.year } ?: Int.MIN_VALUE }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.field),
        contentPadding = PaddingValues(bottom = LocalFloatingChromeHeight.current),
    ) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
                if (onBack != null) {
                    EditorialCircleButton(onClick = onBack, contentDescription = "Back", palette = palette, size = 48.dp) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Text(
                    viewModel.artistName,
                    style = MaterialTheme.typography.displaySmall,
                    color = palette.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
                Text(
                    listOf(
                        if (albums.size == 1) "1 album" else "${albums.size} albums",
                        if (songs.size == 1) "1 song" else "${songs.size} songs",
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // The page's one primary action: everything by this artist.
                if (songs.isNotEmpty()) {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Button(
                            onClick = { viewModel.play(albums.flatMap { it.songs }, 0) },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text("Play all", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                        }
                        androidx.compose.material3.OutlinedButton(
                            onClick = { viewModel.play(albums.flatMap { it.songs }.shuffled(), 0) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, palette.line),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = palette.ink, modifier = Modifier.size(20.dp))
                            Text("Shuffle", style = MaterialTheme.typography.labelLarge, color = palette.ink, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }

        items(albums, key = { it.albumId }) { album ->
            EditorialCard(
                palette = palette,
                onClick = { onAlbumClick(album.albumId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .border(1.5.dp, palette.line, RoundedCornerShape(12.dp)),
                    ) {
                        Artwork(
                            artworkUri = album.artworkUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(1.5.dp),
                            cornerRadius = 10.dp,
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(
                            album.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                album.songs.firstNotNullOfOrNull { it.year }?.toString(),
                                if (album.songs.size == 1) "1 song" else "${album.songs.size} songs",
                                formatLongDuration(album.totalDurationMs),
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    EditorialCircleButton(
                        onClick = { viewModel.play(album.songs, 0) },
                        contentDescription = "Play ${album.name}",
                        palette = palette,
                        size = 44.dp,
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
