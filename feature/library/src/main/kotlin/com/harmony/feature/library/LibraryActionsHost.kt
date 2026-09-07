package com.harmony.feature.library

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One activity-level host: a finished album can be reviewed from any screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryActionsHost(viewModel: LibraryActionsViewModel = hiltViewModel()) {
    val journeys by viewModel.journeys.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val reviewId by viewModel.reviewAlbumId.collectAsStateWithLifecycle()
    val keep by viewModel.selectedToKeep.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    val consent by viewModel.consent.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val lifecycleState by lifecycle.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.consentResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consent, resumed) {
        if (resumed) consent?.consent?.let { sender ->
            viewModel.consentLaunched()
            try { launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            catch (_: Exception) { viewModel.consentResult(false) }
        }
    }
    val ready = journeys.firstOrNull { album ->
        album.readyForReview && album.remindAfter <= System.currentTimeMillis() &&
            (!player.isPlaying || album.tracks.none { it.uri == player.currentSong?.uri })
    }
    LaunchedEffect(ready?.id, reviewId, busy, confirmation, resumed) {
        if (resumed && ready != null && reviewId == null && !busy && confirmation.isEmpty()) viewModel.openReview(ready.id)
    }
    val album = journeys.firstOrNull { it.id == reviewId }
    if (album != null && confirmation.isEmpty() && !busy) {
        ModalBottomSheet(onDismissRequest = viewModel::later, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 22.dp)) {
                Text(if (album.readyForReview) "The last track. Your choice." else "Make room for your next album.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(album.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text("Keep the whole album, delete it from your phone, or keep only the checked songs. Favorites and files you already owned start checked.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(album.tracks.filter { it.uri != null }, key = { it.id }) { track ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = track.uri in keep, onCheckedChange = { track.uri?.let(viewModel::toggleKeep) })
                            Column(Modifier.weight(1f)) {
                                Text(track.title, fontWeight = FontWeight.Medium)
                                Text(if (!track.downloaded) "Already in your library" else "Downloaded for this album", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                val toDelete = album.tracks.count { it.uri != null && it.uri !in keep }
                Button(onClick = viewModel::keepAll, modifier = Modifier.fillMaxWidth()) { Text("Keep the whole album") }
                OutlinedButton(onClick = { viewModel.deleteUnselected() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (toDelete == 0) "Keep selected songs" else "Keep selected · delete $toDelete")
                }
                TextButton(onClick = { viewModel.deleteUnselected(all = true) }, modifier = Modifier.fillMaxWidth(),
                    enabled = album.tracks.any { it.uri != null }) { Text("Delete the whole album", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = viewModel::later, modifier = Modifier.fillMaxWidth()) { Text("Decide later") }
            }
        }
    }
    if (confirmation.isNotEmpty() && !busy) {
        val songs by viewModel.localSongs.collectAsStateWithLifecycle()
        AlertDialog(
        onDismissRequest = viewModel::cancelConfirmation,
        title = { Text("Delete ${confirmation.size} ${if (confirmation.size == 1) "song" else "songs"} from your phone?") },
        text = { Column {
            Text("This deletes the audio files and removes them from Library, playlists, favorites and the playback queue. This cannot be undone in Harmony.")
            val byUri = songs.associateBy { it.uri }
            val titles = journeys.flatMap { it.tracks }.associate { it.uri to it.title }
            Text(confirmation.take(5).joinToString("\n") { byUri[it]?.title ?: titles[it] ?: it.substringAfterLast('/') },
                modifier = Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium)
            if (confirmation.size > 5) Text("And ${confirmation.size - 5} more.")
            Text("Android may ask for permission next.", modifier = Modifier.padding(top = 12.dp))
        } },
        confirmButton = { TextButton(onClick = viewModel::confirmDeletion) { Text("Delete files", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = viewModel::cancelConfirmation) { Text("Cancel") } },
        )
    }
    if (busy && consent == null && resumed) androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.large) { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text("Updating your library…")
        } }
    }
    if (message != null && !busy && reviewId == null) AlertDialog(
        onDismissRequest = { viewModel.message.value = null }, title = { Text("Library updated") },
        text = { Text(message.orEmpty()) }, confirmButton = { TextButton(onClick = { viewModel.message.value = null }) { Text("OK") } },
    )
}
