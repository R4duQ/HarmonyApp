package com.harmony.feature.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AlbumDownloadsShelf(onOpenAlbum: (String) -> Unit, viewModel: AlbumDownloadViewModel = hiltViewModel()) {
    val albums by viewModel.journeys.collectAsStateWithLifecycle()
    val work by viewModel.jobs.collectAsStateWithLifecycle()
    if (albums.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Your Discover albums", style = MaterialTheme.typography.titleMedium)
        albums.asReversed().forEach { album ->
            TextButton(onClick = { onOpenAlbum(album.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(album.title)
                    Text("${album.availableCount}/${album.tracks.size} downloaded · ${album.heardCount}/${album.tracks.size} heard" +
                        if (work.any { !it.state.isFinished && album.id in it.tags }) " · In progress" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
