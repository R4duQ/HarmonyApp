package com.harmony.feature.playlists

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AddSongsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /**
     * Empty query = the whole library (capped), otherwise FTS search results.
     * The cap matters: this sheet is for picking a handful of songs, and
     * rendering 20k rows in a bottom sheet is wasted work — searching is the
     * intended path for large libraries.
     */
    val songs: StateFlow<List<Song>> = _query
        .debounce(200)
        .flatMapLatest { q ->
            if (q.isBlank()) {
                libraryRepository.observeSongs().map { it.take(BROWSE_LIMIT) }
            } else {
                libraryRepository.search(q).map { it.songs }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    private companion object {
        const val BROWSE_LIMIT = 300
    }
}

/**
 * Multi-select song picker for adding to a playlist. Selection is held here
 * and committed once via [onConfirm] rather than writing on every tap —
 * picking ten songs shouldn't mean ten separate DB writes, and it lets the
 * user change their mind before committing.
 */
@Composable
fun AddSongsToPlaylistSheet(
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
    viewModel: AddSongsViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<Long>()) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Add songs", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("Search your library") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
        )

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            items(songs, key = { it.id }) { song ->
                val isSelected = song.id in selected
                ListItem(
                    headlineContent = {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Icon(
                            if (isSelected) Icons.Rounded.CheckCircle
                            else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable {
                        selected = if (isSelected) selected - song.id else selected + song.id
                    },
                )
            }
        }

        Button(
            onClick = { onConfirm(selected.toList()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                if (selected.isEmpty()) "Select songs to add"
                else "Add ${selected.size} song${if (selected.size == 1) "" else "s"}"
            )
        }
    }
}
