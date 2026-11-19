package com.harmony.feature.downloads

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import com.harmony.core.ui.room.RoomButton
import com.harmony.core.ui.room.RoomButtonKind
import com.harmony.core.ui.room.RoomCard
import com.harmony.core.ui.room.RoomChip
import com.harmony.core.ui.room.RoomSectionTitle
import com.harmony.core.ui.room.RoomTopBar
import com.harmony.core.ui.room.StatusLine
import com.harmony.core.ui.room.StatusTone
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.network.InternetNotice
import com.harmony.core.ui.component.EditorialChoiceChips
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.theme.CyberGlassColors
import com.harmony.core.ui.theme.CyberGlassPalette

/**
 * One album (docs/redesign/05 §2.9): pick the edition, then the album's
 * cover and one main action (Get N songs, Pause, Resume, Retry), the source
 * and format folded under "Options", and the tracklist with each track's
 * state in words. Reached from Get music (Albums), the Activity sheet and
 * Discover. Same AlbumDownloadViewModel calls as before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumDownloadScreen(
    albumId: String, title: String, artist: String, onBack: () -> Unit, onOpenDownloads: () -> Unit,
    onReview: (String) -> Unit, artistAliases: Set<String> = emptySet(), viewModel: AlbumDownloadViewModel = hiltViewModel(),
) {
    val journeys by viewModel.journeys.collectAsStateWithLifecycle()
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val internet by viewModel.internet.collectAsStateWithLifecycle()
    val editions by viewModel.editions.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val verificationUrl by viewModel.verificationUrl.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    val favoriteUris = remember(favorites) { favorites.map { it.uri }.toSet() }
    val soulseekConnection by viewModel.soulseekConnection.collectAsStateWithLifecycle()
    val album = journeys.firstOrNull { it.id == albumId }
    val batch = batches.firstOrNull { it.albumId == albumId }
    val otherActive = batches.any { it.albumId != albumId && it.active }
    var sourceName by rememberSaveable(albumId) { mutableStateOf(album?.source ?: "SPOTIFLAC") }
    var formatName by rememberSaveable(albumId) { mutableStateOf(album?.format ?: "FLAC_LOSSLESS") }
    var wifiOnly by rememberSaveable(albumId) { mutableStateOf(false) }
    var showOptions by rememberSaveable(albumId) { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    // Soulseek is the only engine here that can be chosen but unusable: the
    // provider engine needs no session to queue an album.
    val soulseekBlocked = sourceName == "SOULSEEK" && soulseekConnection.status != SoulseekConnectionStatus.CONNECTED
    val r = Room
    val context = LocalContext.current
    LaunchedEffect(albumId, internet.ready, busy) {
        if (album == null) viewModel.search(albumId, title, artist, artistAliases)
    }
    val engine = if (sourceName == "SOULSEEK") "Soulseek" else viewModel.engineName
    val format = SpotiFlacOutputFormat.fromName(formatName)

    LazyColumn(
        Modifier.fillMaxSize().background(r.ground),
        contentPadding = PaddingValues(bottom = LocalFloatingChromeHeight.current + 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { RoomTopBar(stringResource(R.string.album_title), onBack = onBack) }
        if (message != null || browserError != null) item {
            StatusLine(
                if (browserError != null) StatusTone.Error else StatusTone.Info,
                title = browserError ?: message.orEmpty(),
                action = stringResource(R.string.activity_dismiss),
                onAction = { browserError = null; viewModel.dismissMessage() },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (!internet.ready) item { Box(Modifier.padding(horizontal = 16.dp)) { InternetNotice(internet) } }

        if (album == null) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = RoomType.nameL, color = r.ink)
                    Text(artist, style = RoomType.glance, color = r.muted)
                    Text(stringResource(R.string.album_choose_edition), style = RoomType.bodySmall, color = r.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (internet.ready) {
                items(editions, key = { it.id }) { edition ->
                    EditionRow(edition, enabled = !busy) { viewModel.choose(albumId, edition) }
                }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (busy) CircularProgressIndicator(color = r.amberText)
                        else RoomButton(stringResource(R.string.album_search_again), { viewModel.retrySearch(albumId, title, artist, artistAliases) }, kind = RoomButtonKind.Text)
                    }
                }
            }
        } else if (batch != null) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArtwork(batch.coverUrl.ifBlank { null }, 112.dp, corner = 12.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(batch.title, style = RoomType.nameL, color = r.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(batch.artist, style = RoomType.glance, color = r.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(batch.year, pluralStringResource(R.plurals.album_tracks, batch.tracks.size, batch.tracks.size))
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = RoomType.caption, color = r.muted,
                        )
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(batch.statusLabel, style = RoomType.title, color = r.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (batch.active) {
                        LinearProgressIndicator(
                            progress = { batch.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = r.amber, trackColor = r.raised, gapSize = 0.dp, drawStopIndicator = {},
                        )
                    }
                    val missing = album.selectedMissing.size
                    val masterLabel = when {
                        batch.canPause -> stringResource(R.string.album_pause)
                        batch.canResume -> stringResource(R.string.album_resume)
                        batch.canRetryFailed -> stringResource(R.string.album_retry_failed, batch.failed)
                        batch.canDownload -> pluralStringResource(R.plurals.album_get_songs, missing, missing)
                        else -> null
                    }
                    if (masterLabel != null) {
                        RoomButton(
                            masterLabel,
                            onClick = {
                                when {
                                    batch.canPause -> viewModel.pause(albumId)
                                    batch.canResume || batch.canRetryFailed -> viewModel.resume(albumId)
                                    batch.canDownload -> viewModel.start(albumId, DownloadSource.valueOf(sourceName), format, wifiOnly)
                                }
                            },
                            kind = if (batch.canPause) RoomButtonKind.Tonal else RoomButtonKind.Filled,
                            enabled = !busy && internet.ready && !(batch.canDownload && !batch.active && (soulseekBlocked || batch.total == 0)),
                            prominent = true, fillWidth = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (album.availableCount > 0) {
                            RoomButton(pluralStringResource(R.plurals.album_play, album.availableCount, album.availableCount),
                                { if (!busy) viewModel.play(albumId) }, kind = RoomButtonKind.Tonal, icon = Icons.Rounded.PlayArrow)
                        }
                        if (batch.canCancel) {
                            RoomButton(stringResource(R.string.album_stop), { viewModel.cancel(albumId) }, kind = RoomButtonKind.Text, enabled = !busy)
                        }
                    }
                    if (otherActive && !batch.active) StatusLine(StatusTone.Waiting, title = stringResource(R.string.album_waits_turn))
                    if (album.availableCount > 0 && !batch.active) {
                        RoomButton(stringResource(R.string.album_manage_keep), { onReview(albumId) }, kind = RoomButtonKind.Text)
                    }
                }
            }
            if (!batch.active) item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { showOptions = !showOptions }
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.get_from), style = RoomType.bodySmall, color = r.muted)
                        Spacer(Modifier.width(4.dp))
                        Text("$engine · ${stringResource(format.plainLabel())}", style = RoomType.labelSmall, color = r.amberText, modifier = Modifier.weight(1f))
                        Icon(if (showOptions) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.album_options), tint = r.amberText)
                    }
                    if (soulseekBlocked) {
                        StatusLine(
                            StatusTone.Waiting,
                            title = stringResource(if (soulseekConnection.status == SoulseekConnectionStatus.CONNECTING) R.string.album_slsk_connecting else R.string.album_slsk_needed),
                            body = stringResource(R.string.album_slsk_body),
                            action = stringResource(R.string.album_slsk_open),
                            onAction = onOpenDownloads,
                        )
                    }
                    if (showOptions) {
                        RoomCard(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.album_source), style = RoomType.labelSmall, color = r.muted)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // An album resolves each track from metadata, which the converter can't do.
                                    TransferCapableSources.forEach { source ->
                                        RoomChip(source.displayName, selected = sourceName == source.name, enabled = internet.ready && !busy, onClick = {
                                            sourceName = source.name
                                            if (source == DownloadSource.SOULSEEK && formatName == SpotiFlacOutputFormat.FLAC_HI_RES_96.name) {
                                                formatName = SpotiFlacOutputFormat.FLAC_LOSSLESS.name
                                            }
                                        })
                                    }
                                }
                                Text(stringResource(R.string.get_save_as), style = RoomType.labelSmall, color = r.muted)
                                val formats = remember(sourceName) {
                                    SpotiFlacOutputFormat.entries.filter { sourceName == "SPOTIFLAC" || it != SpotiFlacOutputFormat.FLAC_HI_RES_96 }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    formats.forEach { f ->
                                        RoomChip(stringResource(f.plainLabel()), selected = formatName == f.name, enabled = !busy, onClick = { formatName = f.name })
                                    }
                                }
                                Text(stringResource(R.string.album_tags_note), style = RoomType.bodySmall, color = r.muted)
                                RoomChip(stringResource(R.string.album_wifi_only), selected = wifiOnly, enabled = internet.ready && !busy, onClick = { wifiOnly = !wifiOnly })
                                if (sourceName == "SPOTIFLAC") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        RoomButton(stringResource(R.string.album_check_sources), { viewModel.verify(albumId) }, kind = RoomButtonKind.Text, enabled = internet.ready && !busy)
                                        verificationUrl?.let { url ->
                                            RoomButton(stringResource(R.string.get_open_browser), {
                                                val uri = Uri.parse(url)
                                                if (uri.scheme == "https") runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                                    .onFailure { browserError = context.getString(R.string.album_no_browser) }
                                                else browserError = context.getString(R.string.album_bad_link)
                                            }, kind = RoomButtonKind.Tonal, enabled = internet.ready)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoomSectionTitle(stringResource(R.string.album_tracks_heading), Modifier.weight(1f))
                    if (!batch.active) {
                        RoomButton(stringResource(R.string.album_all), { viewModel.select(albumId, selected = true) }, kind = RoomButtonKind.Text, enabled = !busy)
                        RoomButton(stringResource(R.string.album_none), { viewModel.select(albumId, selected = false) }, kind = RoomButtonKind.Text, enabled = !busy)
                    }
                }
            }
            items(batch.tracks, key = { it.track.id }) { row ->
                TrackLine(
                    row = row,
                    selectable = !batch.active,
                    favorite = row.track.uri?.let { it in favoriteUris },
                    enabled = !busy && !(row.canRetry && batch.active),
                    onToggle = { viewModel.select(albumId, row.track.id, it) },
                    onRetry = { if (row.phase == AlbumTrackPhase.VERIFY) viewModel.verify(albumId) else viewModel.retryTrack(albumId, row.track.id) },
                    onSkip = { viewModel.skipTrack(albumId, row.track.id) },
                    onFavorite = { row.track.uri?.let(viewModel::toggleFavorite) },
                )
            }
        }
    }
}

/** A track's state in words, with an icon, so it never rests on colour. */
@Composable
private fun phaseWords(phase: AlbumTrackPhase): Pair<String, androidx.compose.ui.graphics.Color> {
    val r = Room
    return when (phase) {
        AlbumTrackPhase.WAITING -> stringResource(R.string.album_phase_waiting) to r.muted
        AlbumTrackPhase.DOWNLOADING -> stringResource(R.string.album_phase_getting) to r.amberText
        AlbumTrackPhase.INDEXING -> stringResource(R.string.album_phase_analysing) to r.muted
        AlbumTrackPhase.INDEXED, AlbumTrackPhase.SAVED -> stringResource(R.string.album_phase_added) to r.moss
        AlbumTrackPhase.IN_LIBRARY -> stringResource(R.string.album_phase_in_library) to r.moss
        AlbumTrackPhase.FAILED -> stringResource(R.string.album_phase_failed) to r.wine
        AlbumTrackPhase.VERIFY -> stringResource(R.string.album_phase_verify) to r.slate
        AlbumTrackPhase.SKIPPED -> stringResource(R.string.album_phase_skipped) to r.muted
        AlbumTrackPhase.NOT_SELECTED -> stringResource(R.string.album_phase_not_selected) to r.faint
    }
}

@Composable
private fun TrackLine(
    row: AlbumTrackRow,
    selectable: Boolean,
    favorite: Boolean?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onFavorite: () -> Unit,
) {
    val r = Room
    val (words, color) = phaseWords(row.phase)
    val checked = row.phase != AlbumTrackPhase.NOT_SELECTED
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selectable && row.track.uri == null) Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable && row.track.uri == null) {
            Checkbox(checked, onCheckedChange = null, enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = r.amberText, checkmarkColor = r.surface, uncheckedColor = r.outline))
        } else {
            Text(row.number, style = RoomType.caption, color = r.muted, modifier = Modifier.width(40.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(row.track.title, style = RoomType.title, color = r.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(words, row.fraction?.takeIf { row.phase == AlbumTrackPhase.DOWNLOADING }?.let { "${(it * 100).toInt()}%" }, row.detail)
                    .joinToString(" · "),
                style = RoomType.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (row.phase == AlbumTrackPhase.DOWNLOADING && row.fraction != null) {
                LinearProgressIndicator(
                    progress = { row.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = r.amber, trackColor = r.raised, gapSize = 0.dp, drawStopIndicator = {},
                )
            }
        }
        if (row.canRetry) RoomButton(stringResource(R.string.get_try_again), onRetry, kind = RoomButtonKind.Text, enabled = enabled)
        if (row.canSkip && !selectable) RoomButton(stringResource(R.string.album_skip), onSkip, kind = RoomButtonKind.Text, enabled = enabled)
        if (favorite != null) {
            IconButton(onClick = onFavorite, enabled = enabled) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (favorite) R.string.album_unfavourite else R.string.album_favourite, row.track.title),
                    tint = if (favorite) r.amberText else r.muted,
                )
            }
        }
    }
}

/** An edition to choose: cover, title, artist, track count. */
@Composable
private fun EditionRow(edition: AlbumEdition, enabled: Boolean, onChoose: () -> Unit) {
    val r = Room
    RoomCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = if (enabled) onChoose else null, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArtwork(edition.cover, 56.dp, corner = 8.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(edition.title, style = RoomType.title, color = r.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(edition.artist, if (edition.trackCount > 0) pluralStringResource(R.plurals.album_tracks, edition.trackCount, edition.trackCount) else null)
                        .joinToString(" · "),
                    style = RoomType.bodySmall, color = r.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = r.amberText)
        }
    }
}
