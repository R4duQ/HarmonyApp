package com.harmony.feature.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
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
 * The Albums tab in Downloads: search an album, then either Download Album
 * (the whole album joins the queue in one tap) or Tracks (open it to choose
 * songs). The query and results live in [AlbumSearchViewModel], kept with
 * the Downloads screen, so switching to Tracks and back keeps them.
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
    val glass = CyberGlassColors
    val outputFormat = AlbumDownloadPolicy.formatFor(source, format)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CyberGlassPanel {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Download an album", color = glass.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Find the album, then download all of it in one tap, or open it to pick songs. " +
                    "Audio: ${if (source == DownloadSource.SOULSEEK) "Soulseek" else queue.engineName}.",
                    color = glass.TextSecondary, fontSize = 13.sp)
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Album title and artist", color = glass.TextQuiet) },
                    singleLine = true,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = glass.TextPlaceholder) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = glass.TextPrimary, unfocusedTextColor = glass.TextPrimary, disabledTextColor = glass.TextSecondary,
                        focusedContainerColor = glass.Surface, unfocusedContainerColor = glass.Surface, disabledContainerColor = glass.Surface,
                        focusedBorderColor = glass.Cyan, unfocusedBorderColor = glass.Indigo, disabledBorderColor = glass.ChipBorder,
                        cursorColor = glass.Cyan,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                NeonPillButton(
                    text = if (state.searching) "Searching albums…" else "Search albums",
                    icon = Icons.Rounded.Search,
                    onClick = viewModel::search,
                    enabled = !state.busy && state.query.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = glass.Cyan, trackColor = glass.Cyan.copy(alpha = 0.12f))
                Text("Album search uses public metadata; it doesn't download anything until you tap Download Album.",
                    color = glass.TextQuiet, fontSize = 11.sp)
            }
        }
        state.notice?.let { CyberNotice(it, onDismiss = viewModel::dismissNotice) }
        state.error?.let { CyberNotice(it, error = true) }
        if (state.searched && state.results.isEmpty() && !state.busy && state.error == null) {
            CyberNotice("No albums found. Try the artist and a shorter album title.")
        }
        if (state.results.isNotEmpty()) {
            Text("${state.results.size} EDITIONS", color = glass.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
        state.results.forEach { edition ->
            val batch = batches.firstOrNull { b -> viewModel.albumIdFor(edition) == b.albumId }
            AlbumSearchCard(
                edition = edition,
                quality = qualityBadgeFor(outputFormat),
                busy = state.busy,
                loading = state.openingId == edition.id,
                onOpen = { viewModel.open(edition, source, format, onOpenAlbum) },
                onDownloadAll = { viewModel.downloadAll(edition, source, format) },
                queuedLabel = batch?.takeIf { it.active || it.state == AlbumBatchState.COMPLETED }?.statusLabel,
            )
        }
    }
}
