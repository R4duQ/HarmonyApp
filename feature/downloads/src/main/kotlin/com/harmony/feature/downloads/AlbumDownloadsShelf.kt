package com.harmony.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.theme.CyberGlassColors

/**
 * Album downloads in the Downloads queue: one collapsible card per album
 * (a 15-track album is one card, not 15 rows), the running and queued ones
 * first. Tap a card's arrow for its tracks, the card for the album sheet.
 *
 * Finished albums show the latest few; "Show all" lists every album you
 * opened or downloaded (Discover's included).
 */
@Composable
fun AlbumDownloadsShelf(
    onOpenAlbum: (String) -> Unit,
    viewModel: AlbumQueueViewModel = hiltViewModel(),
) {
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    // Which cards are open survives the Tracks/Albums switch and rotation.
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    if (batches.isEmpty()) return
    val glass = CyberGlassColors
    val ordered = batches.sortedWith(compareBy<AlbumBatch> { rank(it.state) })
    val shown = if (showAll) ordered else ordered.filter { it.active || it.state == AlbumBatchState.PAUSED || it.failed > 0 } +
        ordered.filterNot { it.active || it.state == AlbumBatchState.PAUSED || it.failed > 0 }.take(RECENT_FINISHED)
    val active = batches.count { it.active }

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ALBUM QUEUE", color = glass.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.4.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (active > 0) Text("$active active", color = glass.LiveInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        message?.let { CyberNotice(it, error = true, onDismiss = viewModel::dismissMessage) }
        shown.forEach { batch ->
            AlbumBatchCard(
                batch = batch,
                expanded = batch.albumId in expanded,
                onToggle = { expanded = if (batch.albumId in expanded) expanded - batch.albumId else expanded + batch.albumId },
                onOpen = { onOpenAlbum(batch.albumId) },
                onPause = { viewModel.pause(batch.albumId) },
                onResume = { viewModel.resume(batch.albumId) },
                onCancel = { viewModel.cancel(batch.albumId) },
                onRetryTrack = { viewModel.retryTrack(batch.albumId, it) },
                onSkipTrack = { viewModel.skipTrack(batch.albumId, it) },
            )
        }
        if (ordered.size > shown.size || showAll) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show fewer" else "Show all ${ordered.size} albums", color = glass.Cyan)
            }
        }
    }
}

private const val RECENT_FINISHED = 3

/** Running first, then waiting, then needing attention, then done. */
private fun rank(state: AlbumBatchState): Int = when (state) {
    AlbumBatchState.DOWNLOADING, AlbumBatchState.INDEXING -> 0
    AlbumBatchState.QUEUED -> 1
    AlbumBatchState.PAUSED, AlbumBatchState.PARTIAL, AlbumBatchState.FAILED -> 2
    AlbumBatchState.READY -> 3
    AlbumBatchState.COMPLETED -> 4
}
