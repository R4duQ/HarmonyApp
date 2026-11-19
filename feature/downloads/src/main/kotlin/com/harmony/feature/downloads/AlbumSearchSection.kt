package com.harmony.feature.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.theme.CyberGlassColors

/**
 * Albums on the Get music page: one search, then each edition with Get album
 * (the whole album joins the queue in one tap) or Choose songs (open it to
 * pick). The query and results live in [AlbumSearchViewModel], so switching
 * to Songs and back keeps them.
 */
@Composable
internal fun AlbumSearchSection(
    source: DownloadSource,
    format: SpotiFlacOutputFormat,
    onOpenAlbum: (String) -> Unit,
    viewModel: AlbumSearchViewModel = hiltViewModel(),
    queue: AlbumQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val batches by queue.batches.collectAsStateWithLifecycle()
    val r = com.harmony.core.ui.theme.Room
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GetSearchField(state.query, viewModel::setQuery, viewModel::search,
            androidx.compose.ui.res.stringResource(R.string.albums_search_label), enabled = !state.busy)
        com.harmony.core.ui.room.RoomButton(
            androidx.compose.ui.res.stringResource(R.string.get_search), viewModel::search, icon = Icons.Rounded.Search,
            enabled = !state.busy && state.query.trim().length >= 2, loading = state.searching,
        )
        Text(androidx.compose.ui.res.stringResource(R.string.albums_search_note), style = com.harmony.core.ui.theme.RoomType.bodySmall, color = r.muted)
        state.notice?.let { com.harmony.core.ui.room.StatusLine(com.harmony.core.ui.room.StatusTone.Info, it,
            action = androidx.compose.ui.res.stringResource(R.string.activity_dismiss), onAction = viewModel::dismissNotice) }
        state.error?.let { com.harmony.core.ui.room.StatusLine(com.harmony.core.ui.room.StatusTone.Error, it) }
        if (state.searched && state.results.isEmpty() && !state.busy && state.error == null) {
            com.harmony.core.ui.room.StatusLine(com.harmony.core.ui.room.StatusTone.Info,
                androidx.compose.ui.res.stringResource(R.string.albums_none_found), body = androidx.compose.ui.res.stringResource(R.string.albums_none_found_body))
        }
        if (state.results.isNotEmpty()) com.harmony.core.ui.room.RoomSectionTitle(androidx.compose.ui.res.stringResource(R.string.get_results))
        state.results.forEach { edition ->
            val batch = batches.firstOrNull { b -> viewModel.albumIdFor(edition) == b.albumId }
            val queued = batch?.takeIf { it.active || it.state == AlbumBatchState.COMPLETED }?.statusLabel
            com.harmony.core.ui.room.RoomCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        AlbumArtwork(edition.cover, 64.dp, corner = 8.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(edition.title, style = com.harmony.core.ui.theme.RoomType.title, color = r.ink, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(listOfNotNull(edition.artist, if (edition.trackCount > 0)
                                androidx.compose.ui.res.pluralStringResource(R.plurals.album_tracks, edition.trackCount, edition.trackCount) else null)
                                .joinToString(" · "), style = com.harmony.core.ui.theme.RoomType.bodySmall, color = r.muted, maxLines = 1)
                            if (queued != null) Text(queued, style = com.harmony.core.ui.theme.RoomType.bodySmall, color = r.amberText)
                        }
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.harmony.core.ui.room.RoomButton(
                            androidx.compose.ui.res.stringResource(R.string.albums_get_album), { viewModel.downloadAll(edition, source, format) },
                            kind = com.harmony.core.ui.room.RoomButtonKind.Tonal, icon = Icons.Rounded.Download,
                            enabled = !state.busy && queued == null,
                        )
                        com.harmony.core.ui.room.RoomButton(
                            androidx.compose.ui.res.stringResource(R.string.albums_choose_songs), { viewModel.open(edition, source, format, onOpenAlbum) },
                            kind = com.harmony.core.ui.room.RoomButtonKind.Text, enabled = !state.busy, loading = state.openingId == edition.id,
                        )
                    }
                }
            }
        }
    }
}
