package com.harmony.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.MaterialTheme
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
 * The albums on their way, in the same words as the Activity sheet: running
 * and waiting first, then the latest finished. Tap one for its page; Pause,
 * Resume and Stop sit on the row.
 */
@Composable
fun AlbumDownloadsShelf(
    onOpenAlbum: (String) -> Unit,
    viewModel: AlbumQueueViewModel = hiltViewModel(),
) {
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showAll by rememberSaveable { mutableStateOf(false) }
    if (batches.isEmpty()) return
    val r = com.harmony.core.ui.theme.Room
    val ordered = batches.sortedWith(compareBy<AlbumBatch> { rank(it.state) })
    val shown = if (showAll) ordered else ordered.filter { it.active || it.state == AlbumBatchState.PAUSED || it.failed > 0 } +
        ordered.filterNot { it.active || it.state == AlbumBatchState.PAUSED || it.failed > 0 }.take(RECENT_FINISHED)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.harmony.core.ui.room.RoomSectionTitle(androidx.compose.ui.res.stringResource(R.string.albums_on_the_way))
        message?.let { com.harmony.core.ui.room.StatusLine(com.harmony.core.ui.room.StatusTone.Error, it,
            action = androidx.compose.ui.res.stringResource(R.string.activity_dismiss), onAction = viewModel::dismissMessage) }
        shown.forEach { batch ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpenAlbum(batch.albumId) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtwork(batch.coverUrl.ifBlank { null }, 56.dp, corner = 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(batch.title, style = com.harmony.core.ui.theme.RoomType.title, color = r.ink, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${batch.artist} · ${batch.statusLabel}", style = com.harmony.core.ui.theme.RoomType.bodySmall,
                        color = if (batch.failed > 0) r.wine else r.muted, maxLines = 2)
                    if (batch.active) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { batch.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(androidx.compose.foundation.shape.CircleShape),
                            color = r.amber, trackColor = r.raised, gapSize = 0.dp, drawStopIndicator = {},
                        )
                    }
                }
                when {
                    batch.canPause -> com.harmony.core.ui.room.RoomButton(androidx.compose.ui.res.stringResource(R.string.activity_pause),
                        { viewModel.pause(batch.albumId) }, kind = com.harmony.core.ui.room.RoomButtonKind.Text)
                    batch.canResume -> com.harmony.core.ui.room.RoomButton(androidx.compose.ui.res.stringResource(R.string.activity_resume),
                        { viewModel.resume(batch.albumId) }, kind = com.harmony.core.ui.room.RoomButtonKind.Tonal)
                }
            }
        }
        if (ordered.size > shown.size || showAll) {
            com.harmony.core.ui.room.RoomButton(
                if (showAll) androidx.compose.ui.res.stringResource(R.string.albums_show_fewer)
                else androidx.compose.ui.res.stringResource(R.string.albums_show_all, ordered.size),
                { showAll = !showAll }, kind = com.harmony.core.ui.room.RoomButtonKind.Text,
            )
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
