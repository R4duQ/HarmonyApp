package com.harmony.feature.downloads

import com.harmony.core.ui.theme.RoomType
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.room.StatusTone
import com.harmony.core.ui.room.StatusLine
import com.harmony.core.ui.room.RoomTopBar
import com.harmony.core.ui.room.RoomSectionTitle
import com.harmony.core.ui.room.RoomChip
import com.harmony.core.ui.room.RoomButtonKind
import com.harmony.core.ui.room.RoomButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.clip
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import com.harmony.core.ui.theme.Catalog
import androidx.compose.material.icons.rounded.Download
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.harmony.core.ui.component.GlassCard
import com.harmony.core.ui.component.EditorialChoiceChips
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.EditorialTextAction
import com.harmony.core.ui.component.greenPalette
import com.harmony.core.ui.component.LocalFloatingChromeHeight

/**
 * Bring a Spotify playlist into Harmony (docs/redesign/05 §2.11): connect once
 * with a guided 3-step setup, pick a playlist or paste its link, see in three
 * numbers what you have and what is missing, then one button gets the
 * missing songs and makes the playlist. Same SpotifyPlaylistTransferViewModel
 * calls as before; Spotify's API needs the account even for a pasted link.
 */
@Composable
fun SpotifyPlaylistTransferScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    verificationReturnSignal: Int,
    viewModel: SpotifyPlaylistTransferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(verificationReturnSignal) {
        if (verificationReturnSignal > 0 && state.pausedForVerification) viewModel.resumeAfterProviderVerification()
    }
    val copiedText = stringResource(R.string.sp_copied)
    SpotifyTransferContent(
        state = state,
        actions = SpotifyTransferActions(
            onBack = onBack,
            onClientId = viewModel::setClientId,
            onConnect = {
                viewModel.createSpotifyAuthorizationUri()?.let { uri ->
                    if (!openExternalUri(context, uri.toString())) viewModel.spotifyBrowserLaunchFailed()
                }
            },
            onOpenDashboard = { openExternalUri(context, SPOTIFY_DASHBOARD_URL) },
            onCopyRedirect = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Spotify redirect address", SpotifyPlaylistClient.REDIRECT_URI))
                Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
            },
            onHelp = viewModel::showSpotifyConnectionHelp,
            onRefresh = viewModel::refreshPlaylists,
            onDisconnect = viewModel::disconnectSpotify,
            onLink = viewModel::setPlaylistLink,
            onImportLink = viewModel::importPlaylistLink,
            onImport = viewModel::importPlaylist,
            onChooseAnother = viewModel::clearSelectedPlaylist,
            onSource = viewModel::selectSource,
            onFormat = viewModel::setSpotiFlacOutputFormat,
            onOpenDownloads = onOpenDownloads,
            onVerify = { state.verificationChallenge?.verificationUrl?.let { openExternalUri(context, it) } },
            onCheckVerification = viewModel::resumeAfterProviderVerification,
            onCancel = viewModel::cancelTransfer,
            onOpenPlaylist = onOpenPlaylist,
            onAll = { viewModel.selectAllMissing(true) },
            onNone = { viewModel.selectAllMissing(false) },
            onRetry = viewModel::retryFailedTracks,
            onTransfer = viewModel::requestTransfer,
            onToggle = viewModel::toggleTrack,
        ),
    )
}

internal class SpotifyTransferActions(
    val onBack: () -> Unit = {},
    val onClientId: (String) -> Unit = {},
    val onConnect: () -> Unit = {},
    val onOpenDashboard: () -> Unit = {},
    val onCopyRedirect: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onLink: (String) -> Unit = {},
    val onImportLink: () -> Unit = {},
    val onImport: (SpotifyPlaylistSummary) -> Unit = {},
    val onChooseAnother: () -> Unit = {},
    val onSource: (DownloadSource) -> Unit = {},
    val onFormat: (SpotiFlacOutputFormat) -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    val onVerify: () -> Unit = {},
    val onCheckVerification: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenPlaylist: (Long) -> Unit = {},
    val onAll: () -> Unit = {},
    val onNone: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onTransfer: () -> Unit = {},
    val onToggle: (String) -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpotifyTransferContent(state: SpotifyPlaylistTransferState, actions: SpotifyTransferActions) {
    val r = Room
    LazyColumn(
        Modifier.fillMaxSize().background(r.ground),
        contentPadding = PaddingValues(bottom = LocalFloatingChromeHeight.current + 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { RoomTopBar(stringResource(R.string.sp_title), onBack = actions.onBack) }
        state.error?.let { error -> item { StatusLine(StatusTone.Error, error, modifier = Modifier.padding(horizontal = 16.dp)) } }
        state.message?.let { message -> item { StatusLine(StatusTone.Info, message, modifier = Modifier.padding(horizontal = 16.dp)) } }

        when {
            !state.connected -> item { ConnectGuide(state, actions) }
            state.selectedPlaylist == null -> {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = r.moss, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sp_connected), style = RoomType.bodySmall, color = r.ink, modifier = Modifier.weight(1f))
                        RoomButton(stringResource(R.string.sp_refresh), actions.onRefresh, kind = RoomButtonKind.Text, enabled = !state.isLoadingPlaylists)
                        RoomButton(stringResource(R.string.sp_disconnect), actions.onDisconnect, kind = RoomButtonKind.Text)
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GetSearchField(state.playlistLink, actions.onLink, actions.onImportLink, stringResource(R.string.sp_link_label),
                            leading = Icons.Rounded.Link, enabled = !state.isImportingPlaylist)
                        RoomButton(stringResource(R.string.sp_open_link), actions.onImportLink, kind = RoomButtonKind.Tonal,
                            enabled = state.playlistLink.isNotBlank() && !state.isImportingPlaylist, loading = state.isImportingPlaylist)
                    }
                }
                item { RoomSectionTitle(stringResource(R.string.sp_your_playlists), Modifier.padding(horizontal = 16.dp)) }
                if (state.isLoadingPlaylists) {
                    item {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = r.amberText, strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.sp_loading), style = RoomType.body, color = r.muted)
                        }
                    }
                } else {
                    items(state.playlists, key = { it.id }) { playlist ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.isImportingPlaylist, role = Role.Button) { actions.onImport(playlist) }
                                .heightIn(min = 72.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AlbumArtwork(playlist.imageUrl, 56.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, style = RoomType.title, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(pluralStringResource(R.plurals.album_tracks, playlist.totalTracks, playlist.totalTracks), playlist.ownerName)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = RoomType.bodySmall, color = r.muted, maxLines = 1,
                                )
                                if (!playlist.canImportItems) Text(stringResource(R.string.sp_not_yours), style = RoomType.bodySmall, color = r.slate)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = r.amberText)
                        }
                    }
                }
            }
            else -> {
                val playlist = state.selectedPlaylist
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArtwork(playlist.imageUrl, 88.dp, corner = 12.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = RoomType.nameL, color = r.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(playlist.ownerName, style = RoomType.bodySmall, color = r.muted)
                            RoomButton(stringResource(R.string.sp_choose_another), actions.onChooseAnother, kind = RoomButtonKind.Text,
                                enabled = !state.isTransferring)
                        }
                    }
                }
                item {
                    // The summary first, in three numbers (docs/redesign/05 §2.11).
                    val notFound = state.failedCount
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Count(state.localMatchCount, stringResource(R.string.sp_count_have), Modifier.weight(1f))
                        Count(state.selectedMissingCount, stringResource(R.string.sp_count_get), Modifier.weight(1f))
                        Count(notFound, stringResource(R.string.sp_count_failed), Modifier.weight(1f))
                    }
                }
                if (state.pausedForVerification) item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusLine(StatusTone.Waiting,
                            stringResource(R.string.get_check_needed, providerName(state.verificationChallenge?.providerId)),
                            body = state.verificationChallenge?.instructions ?: stringResource(R.string.get_paused_until_check))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.verificationChallenge?.verificationUrl != null) {
                                RoomButton(stringResource(R.string.get_open_browser), actions.onVerify, kind = RoomButtonKind.Tonal)
                            }
                            RoomButton(stringResource(R.string.get_check_again), actions.onCheckVerification, kind = RoomButtonKind.Text,
                                loading = state.isCheckingVerification)
                        }
                    }
                }
                if (state.isTransferring || state.isCheckingVerification) item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TransferRow(
                            title = state.tracks.firstOrNull { it.remote.stableKey == state.currentTrackKey }?.remote?.title
                                ?: stringResource(R.string.sp_working),
                            detail = (state.activeDetail ?: "") + " · " +
                                stringResource(R.string.sp_done_of, state.completedDownloads, state.attemptedDownloads),
                            fraction = state.activeProgress,
                            onStop = actions.onCancel,
                        )
                    }
                }
                state.resultPlaylistId?.let { id ->
                    item {
                        StatusLine(StatusTone.Success, stringResource(R.string.sp_ready, playlist.name),
                            action = stringResource(R.string.sp_open_playlist), onAction = { actions.onOpenPlaylist(id) },
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                if (!state.isTransferring) item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sp_get_with), style = RoomType.labelSmall, color = r.muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // YT Converter needs a link per song, which a Spotify playlist doesn't have.
                            TransferCapableSources.forEach { source ->
                                RoomChip(source.displayName, selected = state.source == source, onClick = { actions.onSource(source) })
                            }
                        }
                        if (state.source == DownloadSource.SPOTIFLAC) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                SpotiFlacOutputFormat.entries.forEach { f ->
                                    RoomChip(stringResource(f.plainLabel()), selected = state.spotiFlacOutputFormat == f, onClick = { actions.onFormat(f) })
                                }
                            }
                        } else if (state.soulseekConnection.status != SoulseekConnectionStatus.CONNECTED) {
                            StatusLine(StatusTone.Waiting, stringResource(R.string.album_slsk_needed), body = stringResource(R.string.album_slsk_body),
                                action = stringResource(R.string.album_slsk_open), onAction = actions.onOpenDownloads)
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val missing = state.selectedMissingCount
                        RoomButton(
                            when {
                                state.isTransferring -> stringResource(R.string.sp_transferring)
                                missing == 0 -> stringResource(R.string.sp_create_only)
                                else -> pluralStringResource(R.plurals.sp_get_and_create, missing, missing)
                            },
                            actions.onTransfer,
                            enabled = !state.isTransferring && !state.isCheckingVerification,
                            prominent = true, fillWidth = true,
                        )
                        if (state.failedCount > 0 && !state.isTransferring) {
                            RoomButton(pluralStringResource(R.plurals.sp_retry_failed, state.failedCount, state.failedCount),
                                actions.onRetry, kind = RoomButtonKind.Tonal)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoomSectionTitle(stringResource(R.string.sp_songs), Modifier.weight(1f))
                        RoomButton(stringResource(R.string.album_all), actions.onAll, kind = RoomButtonKind.Text, enabled = !state.isTransferring)
                        RoomButton(stringResource(R.string.album_none), actions.onNone, kind = RoomButtonKind.Text, enabled = !state.isTransferring)
                    }
                }
                items(state.tracks, key = { it.remote.stableKey }) { row ->
                    TrackRow(row, enabled = !state.isTransferring && !state.isCheckingVerification, onToggle = { actions.onToggle(row.remote.stableKey) })
                }
            }
        }
    }
}

/** The 3-step setup, one step at a time in plain words, with the parts to copy one tap away. */
@Composable
private fun ConnectGuide(state: SpotifyPlaylistTransferState, actions: SpotifyTransferActions) {
    val r = Room
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.sp_guide_title), style = RoomType.nameL, color = r.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.sp_guide_body), style = RoomType.body, color = r.muted)
        Step(1, stringResource(R.string.sp_step1), stringResource(R.string.sp_step1_body)) {
            RoomButton(stringResource(R.string.sp_open_dashboard), actions.onOpenDashboard, kind = RoomButtonKind.Tonal, icon = Icons.Rounded.OpenInBrowser)
        }
        Step(2, stringResource(R.string.sp_step2), stringResource(R.string.sp_step2_body)) {
            Text(
                SpotifyPlaylistClient.REDIRECT_URI,
                style = RoomType.caption,
                color = r.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(r.raised)
                    .padding(12.dp),
            )
            RoomButton(stringResource(R.string.sp_copy), actions.onCopyRedirect, kind = RoomButtonKind.Tonal, icon = Icons.Rounded.ContentCopy)
        }
        Step(3, stringResource(R.string.sp_step3), stringResource(R.string.sp_step3_body)) {
            OutlinedTextField(
                state.clientId, actions.onClientId, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sp_client_id)) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                textStyle = RoomType.body,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = r.surface, unfocusedContainerColor = r.surface,
                    focusedBorderColor = r.ink, unfocusedBorderColor = r.outline,
                    focusedLabelColor = r.ink, unfocusedLabelColor = r.muted, cursorColor = r.ink,
                ),
            )
        }
        RoomButton(stringResource(R.string.sp_connect), actions.onConnect, prominent = true, fillWidth = true, enabled = state.clientId.isNotBlank())
        RoomButton(stringResource(R.string.sp_help), actions.onHelp, kind = RoomButtonKind.Text)
    }
}

@Composable
private fun Step(number: Int, title: String, body: String, content: @Composable () -> Unit) {
    val r = Room
    Row {
        Box(Modifier.size(32.dp).clip(CircleShape).background(r.amberSoft), contentAlignment = Alignment.Center) {
            Text("$number", style = RoomType.labelSmall, color = r.onAmberSoft)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = RoomType.title, color = r.ink)
            Text(body, style = RoomType.bodySmall, color = r.muted)
            content()
        }
    }
}

@Composable
private fun Count(value: Int, label: String, modifier: Modifier) {
    val r = Room
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(r.surface)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text("$value", style = RoomType.nameXL, color = r.ink)
        Text(label, style = RoomType.bodySmall, color = r.muted)
    }
}

@Composable
private fun TrackRow(row: PlaylistTransferTrack, enabled: Boolean, onToggle: () -> Unit) {
    val r = Room
    val (words, color) = when (row.status) {
        PlaylistTransferTrackStatus.IN_LIBRARY -> stringResource(R.string.album_phase_in_library) to r.moss
        PlaylistTransferTrackStatus.READY -> stringResource(if (row.selected) R.string.sp_to_get else R.string.album_phase_not_selected) to r.muted
        PlaylistTransferTrackStatus.SEARCHING -> stringResource(R.string.sp_searching) to r.amberText
        PlaylistTransferTrackStatus.DOWNLOADING -> stringResource(R.string.album_phase_getting) to r.amberText
        PlaylistTransferTrackStatus.DOWNLOADED -> stringResource(R.string.album_phase_added) to r.moss
        PlaylistTransferTrackStatus.WAITING_FOR_VERIFICATION -> stringResource(R.string.album_phase_verify) to r.slate
        PlaylistTransferTrackStatus.FAILED -> stringResource(R.string.album_phase_failed) to r.wine
    }
    val toggleable = row.localSongId == null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (toggleable) Modifier.toggleable(row.selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (toggleable) {
            Checkbox(row.selected, onCheckedChange = null, enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = r.amberText, checkmarkColor = r.surface, uncheckedColor = r.outline))
        } else {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = r.moss, modifier = Modifier.padding(12.dp).size(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(row.remote.title, style = RoomType.title, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(row.remote.artists, words, row.detail).joinToString(" · "), style = RoomType.bodySmall, color = color,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun openExternalUri(context: Context, value: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

private const val SPOTIFY_DASHBOARD_URL = "https://developer.spotify.com/dashboard"
