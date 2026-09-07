package com.harmony.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.Playlist
import com.harmony.domain.library.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch { playlistRepository.addSongs(playlistId, listOf(songId)) }
    }

    fun createPlaylistAndAddSong(name: String, songId: Long) {
        viewModelScope.launch {
            val id = playlistRepository.create(name.trim())
            playlistRepository.addSongs(id, listOf(songId))
        }
    }
}

/**
 * One reusable "add this song to a playlist" sheet, shared by every song
 * list in the library (Songs tab, search results, album/artist detail) —
 * one implementation instead of duplicating this per screen.
 *
 * Tapping a playlist adds immediately (no separate "confirm" step) and shows
 * a brief inline checkmark rather than dismissing the sheet — adding a song
 * to two or three playlists in one go shouldn't require reopening this sheet
 * each time.
 */
@Composable
fun AddToPlaylistSheet(
    songId: Long,
    onDismiss: () -> Unit,
    viewModel: AddToPlaylistViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var justAdded by remember { mutableStateOf<Long?>(null) }
    var showCreateField by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Add to playlist", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onDismiss) { Text("Done") }
        }

        if (playlists.isEmpty() && !showCreateField) {
            Text(
                "You don't have any playlists yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = { Text("${playlist.songCount} songs") },
                    leadingContent = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
                    trailingContent = {
                        if (justAdded == playlist.id) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "Added",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        viewModel.addSongToPlaylist(playlist.id, songId)
                        justAdded = playlist.id
                    },
                )
            }
        }

        if (showCreateField) {
            var name by remember { mutableStateOf("") }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("New playlist name") },
                )
                Button(
                    onClick = {
                        viewModel.createPlaylistAndAddSong(name, songId)
                        showCreateField = false
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Create") }
            }
        } else {
            TextButton(
                onClick = { showCreateField = true },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("New playlist")
            }
        }
    }
}
