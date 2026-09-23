package com.harmony.feature.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.GlassCard

@Composable
internal fun AlbumSearchSection(
    source: DownloadSource,
    format: SpotiFlacOutputFormat,
    palette: EditorialPalette,
    onOpenAlbum: (String) -> Unit,
    viewModel: AlbumSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(palette = palette) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Download an album", color = palette.ink, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text("Search the album and artist, choose the exact edition, then select all tracks or only the songs you want.", color = palette.muted)
                Text("Audio source: ${source.displayName}. Album search uses public metadata; it does not download audio.", color = palette.muted, fontSize = 12.sp)
                OutlinedTextField(value = state.query, onValueChange = viewModel::setQuery,
                    label = { Text("Album title and artist") }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) })
                Button(onClick = viewModel::search, enabled = !state.busy && state.query.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (state.searching) "Searching albums…" else "Search albums")
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = palette.ink)
                state.error?.let { Text(it, color = palette.ink) }
                if (state.searched && state.results.isEmpty() && !state.busy && state.error == null) {
                    Text("No albums found. Try the artist and a shorter album title.", color = palette.muted)
                }
                if (state.results.isNotEmpty()) Text("${state.results.size} editions · open one to choose tracks", color = palette.muted, fontSize = 12.sp)
            }
        }
        state.results.forEach { edition ->
            NotificationPill(title = edition.title,
                detail = if (state.openingId == edition.id) "Loading complete tracklist…" else "${edition.artist} · Choose tracks",
                coverUrl = edition.cover, palette = palette, enabled = !state.busy,
                onClick = { viewModel.open(edition, source, format, onOpenAlbum) },
                action = { PillAction(icon = Icons.Rounded.ChevronRight,
                    contentDescription = "Choose tracks from ${edition.title} by ${edition.artist}", palette = palette,
                    enabled = !state.busy, onClick = { viewModel.open(edition, source, format, onOpenAlbum) }) })
        }
    }
}
