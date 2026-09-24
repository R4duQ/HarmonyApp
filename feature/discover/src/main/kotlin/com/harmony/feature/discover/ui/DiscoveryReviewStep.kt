package com.harmony.feature.discover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.glassFill
import com.harmony.core.ui.component.glassRim
import com.harmony.domain.library.discovery.PlaylistPlacement
import com.harmony.domain.library.discovery.SongAvailability
import com.harmony.domain.library.repository.DiscoveryBatch

@Composable
internal fun ReviewStep(
    state: DiscoveryUiState,
    palette: EditorialPalette,
    actions: DiscoveryActions,
    bottomPadding: Dp,
    downloadPanel: @Composable (DiscoveryBatch) -> Unit,
) {
    val batch = state.review ?: return
    val progress = state.progress
    val large = LocalDensity.current.fontScale > 1.3f
    val available = progress?.availableCount ?: 0
    val total = batch.songs.size
    val missing = total - available
    val linkedToDraft = state.draft?.batchId == batch.id
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(DiscoveryTags.REVIEW_STEP),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = bottomPadding + 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepHeader("Your playlist", null, if (linkedToDraft && !batch.locked) "Back to songs" else "Close", actions.backFromReview,
                    palette, backModifier = Modifier.testTag(DiscoveryTags.BACK))
                StepIndicator(2, palette)
            }
        }
        if (state.connection != Connection.ONLINE && state.connection != Connection.CHECKING) item(key = "offline") {
            ConnectionNotice(state, palette, Modifier.testTag(DiscoveryTags.OFFLINE))
        }
        item(key = "summary") { SummaryCard(batch, palette, actions, large) }
        item(key = "availability") {
            DiscoverCard(palette, Modifier.testTag(DiscoveryTags.AVAILABILITY)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$available of $total in your library", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    if (progress != null && missing > 0) {
                        val parts = listOfNotNull(
                            "${missing - progress.failed.size - (if (progress.downloading != null) 1 else 0)} waiting",
                            progress.downloading?.let { "1 downloading" },
                            progress.failed.size.takeIf { it > 0 }?.let { "$it failed" },
                        )
                        Text(parts.joinToString(" · "), color = palette.muted, fontSize = 14.sp)
                        Text("Found in a catalog doesn't mean there's a file. A song joins the playlist once it's downloaded and scanned.",
                            color = palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    CreateAction(batch, available, total, state.creating, palette, actions)
                }
            }
        }
        if (missing > 0 && !batch.playlistDeleted) item(key = "download") {
            DiscoverCard(palette, Modifier.testTag(DiscoveryTags.DOWNLOADS)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download the missing $missing", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    downloadPanel(batch)
                }
            }
        }
        item(key = "songs-title") { SectionTitle("Songs", palette) }
        itemsIndexed(batch.songs, key = { _, s -> "review:${s.key}" }) { index, song ->
            val status = progress?.status(song.key) ?: SongAvailability.NEEDS_DOWNLOAD
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(DiscoveryTags.reviewRow(song.key)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}", color = palette.muted, fontSize = 13.sp, modifier = Modifier.widthIn(min = 22.dp))
                Cover(song.artwork, palette, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = when (status) {
                        SongAvailability.IN_LIBRARY -> "In your library"
                        SongAvailability.DOWNLOADING -> "Downloading…"
                        SongAvailability.FAILED -> batch.errors[song.key] ?: "Download failed"
                        SongAvailability.NEEDS_DOWNLOAD -> "Needs download"
                    }
                    Text(detail, color = palette.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    batch.reasons[song.key]?.let { Text(it, color = palette.muted.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Icon(
                    when (status) {
                        SongAvailability.IN_LIBRARY -> Icons.Rounded.CheckCircle
                        SongAvailability.DOWNLOADING -> Icons.Rounded.Downloading
                        SongAvailability.FAILED -> Icons.Rounded.ErrorOutline
                        SongAvailability.NEEDS_DOWNLOAD -> Icons.Rounded.CloudDownload
                    }, null, tint = if (status == SongAvailability.IN_LIBRARY) palette.accent else palette.muted, modifier = Modifier.size(20.dp))
            }
        }
        item(key = "new") {
            GlassButton("Start a new selection", palette, actions.startNew, Modifier.fillMaxWidth().testTag(DiscoveryTags.NEW_SELECTION))
        }
    }
}

@Composable
private fun SummaryCard(batch: DiscoveryBatch, palette: EditorialPalette, actions: DiscoveryActions, large: Boolean) {
    var name by rememberSaveable(batch.id) { mutableStateOf(batch.name) }
    val focus = LocalFocusManager.current
    val (knownMs, known) = PlaylistPlacement.duration(batch.songs)
    DiscoverCard(palette, Modifier.testTag(DiscoveryTags.SUMMARY)) {
        val art = batch.songs.map { it.artwork }.filter { it.isNotBlank() }.distinct().take(4)
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val meta: @Composable (Modifier) -> Unit = { m -> Text(
                    when {
                        batch.songs.isEmpty() -> "No songs"
                        known == batch.songs.size -> "${batch.songs.size} songs · ${formatDuration(knownMs)}"
                        known > 0 -> "${batch.songs.size} songs · ${formatDuration(knownMs)} for the $known with a known length"
                        else -> "${batch.songs.size} songs · length unknown"
                    },
                    color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = m.testTag(DiscoveryTags.DURATION),
                ) }
            if (large) { MosaicCover(art, palette, Modifier.size(84.dp)); meta(Modifier) }
            else Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                MosaicCover(art, palette, Modifier.size(104.dp)); meta(Modifier.weight(1f))
            }
            // Full width, so a long name stays readable while it's edited.
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Playlist name") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.rename(name); focus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = glassFill(palette), unfocusedContainerColor = glassFill(palette),
                    focusedBorderColor = palette.ink.copy(alpha = 0.4f), unfocusedBorderColor = palette.line,
                    focusedTextColor = palette.ink, unfocusedTextColor = palette.ink, focusedLabelColor = palette.muted, unfocusedLabelColor = palette.muted,
                ),
                modifier = Modifier.fillMaxWidth().testTag(DiscoveryTags.NAME)
                    .onFocusChanged { if (!it.isFocused && name.isNotBlank() && name != batch.name) actions.rename(name) },
            )
        }
    }
}

/** Create now with what's playable; the rest keeps going in the background. Disabled only with a reason. */
@Composable
private fun CreateAction(batch: DiscoveryBatch, available: Int, total: Int, creating: Boolean, palette: EditorialPalette, actions: DiscoveryActions) {
    val missing = total - available
    when {
        batch.playlistId != null && !batch.playlistDeleted -> {
            PrimaryButton("Open playlist", palette, actions.createPlaylist, Modifier.fillMaxWidth().testTag(DiscoveryTags.OPEN_PLAYLIST),
                icon = Icons.AutoMirrored.Rounded.QueueMusic)
            Text(if (missing > 0) "Saved. The other $missing join the same playlist when they're downloaded, even after a restart."
                else batch.status, color = palette.muted, fontSize = 12.sp)
        }
        batch.playlistDeleted -> Text("You deleted this playlist. Downloads keep their files but no longer add to it.",
            color = palette.muted, fontSize = 13.sp)
        available == 0 -> {
            PrimaryButton("Create playlist", palette, {}, Modifier.fillMaxWidth().testTag(DiscoveryTags.CREATE), enabled = false,
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd)
            Text("None of these songs is in your library yet. Download them below first.", color = palette.muted, fontSize = 12.sp,
                modifier = Modifier.testTag(DiscoveryTags.CREATE_REASON))
        }
        missing == 0 -> PrimaryButton("Create playlist", palette, actions.createPlaylist, Modifier.fillMaxWidth().testTag(DiscoveryTags.CREATE),
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd, busy = creating)
        else -> {
            PrimaryButton("Create playlist with the $available available songs", palette, actions.createPlaylist,
                Modifier.fillMaxWidth().testTag(DiscoveryTags.CREATE), icon = Icons.AutoMirrored.Rounded.PlaylistAdd, busy = creating)
            Text("The other $missing stay here and join the same playlist when they're downloaded.", color = palette.muted, fontSize = 12.sp)
        }
    }
}
