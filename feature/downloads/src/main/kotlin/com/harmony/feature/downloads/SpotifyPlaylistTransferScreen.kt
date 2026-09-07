package com.harmony.feature.downloads

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
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialChoiceChips
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.EditorialTextAction
import com.harmony.core.ui.component.greenPalette

@Composable
fun SpotifyPlaylistTransferScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    verificationReturnSignal: Int,
    viewModel: SpotifyPlaylistTransferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = greenPalette()
    val context = LocalContext.current

    LaunchedEffect(verificationReturnSignal) {
        if (verificationReturnSignal > 0 && state.pausedForVerification) {
            viewModel.resumeAfterProviderVerification()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(palette.field),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TransferHeader(onBack = onBack, palette = palette)
        }

        state.error?.let { error ->
            item {
                NoticeCard(
                    text = error,
                    icon = Icons.Rounded.ErrorOutline,
                    palette = palette,
                )
            }
        }
        state.message?.let { message ->
            item {
                NoticeCard(text = message, icon = Icons.Rounded.Sync, palette = palette)
            }
        }

        if (!state.connected) {
            item {
                SpotifyConnectionCard(
                    state = state,
                    palette = palette,
                    onClientIdChange = viewModel::setClientId,
                    onConnect = {
                        viewModel.createSpotifyAuthorizationUri()?.let { uri ->
                            if (!openExternalUri(context, uri.toString())) viewModel.spotifyBrowserLaunchFailed()
                        }
                    },
                    onOpenDashboard = { openExternalUri(context, SPOTIFY_DASHBOARD_URL) },
                    onCopyRedirect = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Spotify Redirect URI", SpotifyPlaylistClient.REDIRECT_URI))
                        Toast.makeText(context, "Redirect URI copied", Toast.LENGTH_SHORT).show()
                    },
                    onConnectionHelp = viewModel::showSpotifyConnectionHelp,
                )
            }
        } else if (state.selectedPlaylist == null) {
            item {
                ConnectedControls(
                    state = state,
                    palette = palette,
                    onRefresh = viewModel::refreshPlaylists,
                    onDisconnect = viewModel::disconnectSpotify,
                )
            }
            item {
                PlaylistLinkCard(
                    value = state.playlistLink,
                    loading = state.isImportingPlaylist,
                    onValueChange = viewModel::setPlaylistLink,
                    onImport = viewModel::importPlaylistLink,
                    palette = palette,
                )
            }
            if (state.isLoadingPlaylists) {
                item { LoadingRow("Loading Spotify playlists…", palette) }
            } else {
                item {
                    EditorialSectionLabel(
                        text = "Your Spotify playlists",
                        palette = palette,
                        modifier = Modifier.padding(start = 22.dp, top = 8.dp),
                    )
                }
                items(state.playlists, key = { it.id }) { playlist ->
                    SpotifyPlaylistRow(
                        playlist = playlist,
                        palette = palette,
                        enabled = !state.isImportingPlaylist,
                        onClick = { viewModel.importPlaylist(playlist) },
                    )
                }
            }
        } else {
            item {
                ImportedPlaylistCard(
                    state = state,
                    palette = palette,
                    onChooseAnother = viewModel::clearSelectedPlaylist,
                    onOpenSpotify = {
                        state.selectedPlaylist?.spotifyUrl?.let { openExternalUri(context, it) }
                    },
                )
            }
            item {
                DownloadMethodCard(
                    state = state,
                    palette = palette,
                    onSelectSource = viewModel::selectSource,
                    onSelectFormat = viewModel::setSpotiFlacOutputFormat,
                    onOpenDownloads = onOpenDownloads,
                )
            }
            if (state.pausedForVerification) {
                item {
                    VerificationCard(
                        state = state,
                        palette = palette,
                        onVerify = {
                            state.verificationChallenge?.verificationUrl
                                ?.let { openExternalUri(context, it) }
                        },
                        onCheck = viewModel::resumeAfterProviderVerification,
                    )
                }
            }
            if (state.isTransferring || state.isCheckingVerification) {
                item { TransferProgressCard(state = state, palette = palette, onCancel = viewModel::cancelTransfer) }
            }
            state.resultPlaylistId?.let { playlistId ->
                item {
                    EditorialCard(
                        palette = palette,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = palette.ink)
                            Text(
                                "Harmony playlist is ready",
                                color = palette.ink,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            EditorialTextAction(
                                text = "Open",
                                onClick = { onOpenPlaylist(playlistId) },
                                palette = palette,
                            )
                        }
                    }
                }
            }
            item {
                SelectionAndTransferCard(
                    state = state,
                    palette = palette,
                    onSelectAll = { viewModel.selectAllMissing(true) },
                    onClear = { viewModel.selectAllMissing(false) },
                    onRetry = viewModel::retryFailedTracks,
                    onTransfer = viewModel::requestTransfer,
                )
            }
            item {
                EditorialSectionLabel(
                    text = "Playlist order",
                    palette = palette,
                    modifier = Modifier.padding(start = 22.dp, top = 8.dp),
                )
            }
            items(state.tracks, key = { it.remote.stableKey }) { row ->
                TransferTrackRow(
                    row = row,
                    palette = palette,
                    enabled = !state.isTransferring && !state.isCheckingVerification,
                    onToggle = { viewModel.toggleTrack(row.remote.stableKey) },
                )
            }
        }
    }

    if (state.showTransferConfirmation) {
        val count = state.selectedMissingCount
        AlertDialog(
            onDismissRequest = viewModel::dismissTransferConfirmation,
            containerColor = palette.field,
            titleContentColor = palette.ink,
            textContentColor = palette.muted,
            title = { Text(if (count == 0) "Create Harmony playlist?" else "Transfer $count track${if (count == 1) "" else "s"}?") },
            text = {
                Text(
                    if (count == 0) {
                        "Harmony will create the playlist from the ${state.localMatchCount} tracks already in your library."
                    } else {
                        "Harmony will use ${state.source.displayName} for every selected missing track. It will not switch engines automatically."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmTransfer) {
                    Text(if (count == 0) "Create" else "Start", color = palette.ink)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissTransferConfirmation) {
                    Text("Cancel", color = palette.muted)
                }
            },
        )
    }
}

@Composable
private fun TransferHeader(onBack: () -> Unit, palette: EditorialPalette) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EditorialCircleButton(
            onClick = onBack,
            contentDescription = "Back to playlists",
            palette = palette,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Icon(Icons.Rounded.ArrowBack, null, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                "Playlist Transfer",
                fontSize = 36.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
                color = palette.ink,
            )
            Text(
                "Spotify order. Harmony files. Your chosen source.",
                fontSize = 13.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun SpotifyConnectionCard(
    state: SpotifyPlaylistTransferState,
    palette: EditorialPalette,
    onClientIdChange: (String) -> Unit,
    onConnect: () -> Unit,
    onOpenDashboard: () -> Unit,
    onCopyRedirect: () -> Unit,
    onConnectionHelp: () -> Unit,
) {
    EditorialCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = palette.ink)
                Text(
                    "Connect Spotify",
                    color = palette.ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                "One-time setup: register a Spotify app to give Harmony access to your playlists. " +
                    "Sign in to Spotify only in your browser.",
                color = palette.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "1. Open Developer Dashboard and create or select an app with Web API access.",
                color = palette.ink,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
            )
            EditorialTextAction("Open Developer Dashboard", onOpenDashboard, palette)
            Text(
                "Spotify Development Mode requires the app owner to have Premium. " +
                    "Add the account you sign in with under Users Management.",
                color = palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "2. In that app's Settings, add and save this exact Redirect URI:",
                color = palette.ink,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                SpotifyPlaylistClient.REDIRECT_URI,
                color = palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )
            EditorialTextAction("Copy Redirect URI", onCopyRedirect, palette)
            Text(
                "3. Copy Client ID from Settings / Basic Information and paste it here.",
                color = palette.ink,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
            val clientIdError = state.clientId.takeIf(String::isNotBlank)
                ?.let(SpotifyAuthorization::clientIdError)
            OutlinedTextField(
                value = state.clientId,
                onValueChange = onClientIdChange,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                singleLine = true,
                label = { Text("Spotify Client ID") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Ascii,
                ),
                isError = clientIdError != null,
                supportingText = { Text(clientIdError ?: "32 characters copied from your Spotify app.") },
            )
            Text(
                "Client Secret is not needed. A valid-looking ID can still be rejected by Spotify " +
                    "if it belongs to no active app or is the wrong value.",
                color = palette.muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorialPill(
                    text = "Connect",
                    icon = Icons.Rounded.Login,
                    onClick = onConnect,
                    palette = palette,
                )
                EditorialTextAction(
                    text = "Connection help",
                    onClick = onConnectionHelp,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun ConnectedControls(
    state: SpotifyPlaylistTransferState,
    palette: EditorialPalette,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    EditorialCard(palette, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CheckCircle, null, tint = palette.ink)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("Spotify connected", color = palette.ink, fontWeight = FontWeight.Bold)
                Text(
                    "${state.playlists.size} playlist${if (state.playlists.size == 1) "" else "s"} available",
                    color = palette.muted,
                    fontSize = 12.sp,
                )
            }
            EditorialCircleButton(onRefresh, "Refresh playlists", palette, size = 38.dp) {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            EditorialCircleButton(onDisconnect, "Disconnect Spotify", palette, size = 38.dp) {
                Icon(Icons.Rounded.Logout, null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PlaylistLinkCard(
    value: String,
    loading: Boolean,
    onValueChange: (String) -> Unit,
    onImport: () -> Unit,
    palette: EditorialPalette,
) {
    EditorialCard(palette, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Or paste a playlist link", color = palette.ink, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                enabled = !loading,
                label = { Text("open.spotify.com/playlist/…") },
                trailingIcon = { Icon(Icons.Rounded.Link, null) },
            )
            EditorialPill(
                text = if (loading) "Importing…" else "Import link",
                icon = Icons.Rounded.CloudDownload,
                onClick = onImport,
                palette = palette,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun SpotifyPlaylistRow(
    playlist: SpotifyPlaylistSummary,
    palette: EditorialPalette,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    EditorialCard(
        palette = palette,
        onClick = onClick.takeIf { enabled && playlist.canImportItems },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArtwork(playlist.imageUrl, playlist.name, palette)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    playlist.name,
                    color = palette.ink,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${playlist.totalTracks} tracks · ${playlist.ownerName}",
                    color = palette.muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!playlist.canImportItems) {
                    Text(
                        "Items unavailable: not owned or collaborative",
                        color = palette.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Icon(Icons.Rounded.CloudDownload, null, tint = if (playlist.canImportItems) palette.ink else palette.muted)
        }
    }
}

@Composable
private fun PlaylistArtwork(url: String?, name: String, palette: EditorialPalette) {
    Surface(
        modifier = Modifier.size(58.dp),
        shape = CircleShape,
        color = palette.accent,
        contentColor = palette.onAccent,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = "$name artwork", modifier = Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null)
            }
        }
    }
}

@Composable
private fun ImportedPlaylistCard(
    state: SpotifyPlaylistTransferState,
    palette: EditorialPalette,
    onChooseAnother: () -> Unit,
    onOpenSpotify: () -> Unit,
) {
    val playlist = state.selectedPlaylist ?: return
    EditorialCard(palette, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaylistArtwork(playlist.imageUrl, playlist.name, palette)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(playlist.name, color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${state.tracks.size} importable · ${state.localMatchCount} already local · ${state.missingCount} missing",
                        color = palette.muted,
                        fontSize = 12.sp,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditorialTextAction("Choose another", onChooseAnother, palette)
                if (playlist.spotifyUrl != null) {
                    EditorialTextAction("Open in Spotify", onOpenSpotify, palette)
                }
            }
        }
    }
}

@Composable
private fun DownloadMethodCard(
    state: SpotifyPlaylistTransferState,
    palette: EditorialPalette,
    onSelectSource: (DownloadSource) -> Unit,
    onSelectFormat: (SpotiFlacOutputFormat) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    EditorialCard(palette, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            EditorialSectionLabel("Download method", palette)
            Text(
                "The selected engine is used for this batch. Harmony never silently switches between engines.",
                color = palette.muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
            EditorialChoiceChips(
                options = DownloadSource.entries.map { it.displayName },
                selectedIndex = DownloadSource.entries.indexOf(state.source),
                onSelect = { onSelectSource(DownloadSource.entries[it]) },
                palette = palette,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (state.source == DownloadSource.SPOTIFLAC) {
                Text("Output", color = palette.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 15.dp))
                EditorialChoiceChips(
                    options = SpotiFlacOutputFormat.entries.map { it.label },
                    selectedIndex = SpotiFlacOutputFormat.entries.indexOf(state.spotiFlacOutputFormat),
                    onSelect = { onSelectFormat(SpotiFlacOutputFormat.entries[it]) },
                    palette = palette,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                val connected = state.soulseekConnection.status == SoulseekConnectionStatus.CONNECTED
                Text(
                    if (connected) {
                        "Connected as ${state.soulseekConnection.username} · ${state.soulseekFormatPreference.label}"
                    } else {
                        "Soulseek is not connected. Sign in once from Downloads."
                    },
                    color = if (connected) palette.ink else palette.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (!connected) {
                    EditorialTextAction(
                        text = "Open Downloads",
                        onClick = onOpenDownloads,
                        palette = palette,
                        modifier = Modifier.padding(top = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionAndTransferCard(
    state: SpotifyPlaylistTransferState,
    palette: EditorialPalette,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onTransfer: () -> Unit,
) {
    EditorialCard(palette, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${state.selectedMissingCount} missing selected", color = palette.ink, fontWeight = FontWeight.Bold)
                    Text(
                        "Local matches are included automatically in the original order.",
                        color = palette.muted,
                        fontSize = 12.sp,
                    )
                }
                EditorialTextAction("All", onSelectAll, palette)
                Spacer(Modifier.width(7.dp))
                EditorialTextAction("None", onClear, palette)
            }
            if (state.failedCount > 0 && !state.isTransferring) {
                EditorialTextAction(
                    text = "Retry ${state.failedCount} failed",
                    onClick = onRetry,
                    palette = palette,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            EditorialPill(
                text = when {
                    state.isTransferring -> "Transferring…"
                    state.selectedMissingCount == 0 -> "Create playlist"
                    else -> "Transfer selected"
                },
                icon = Icons.Rounded.Sync,
                onClick = onTransfer,
                palette = palette,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun TransferTrackRow(
    row: PlaylistTransferTrack,
    palette: EditorialPalette,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    EditorialCard(
        palette = palette,
        onClick = onToggle.takeIf { enabled && row.localSongId == null },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (row.remote.position + 1).toString(),
                color = palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.width(26.dp),
            )
            AsyncImage(
                model = row.remote.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    row.remote.title,
                    color = palette.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.remote.artists,
                    color = palette.muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.detail ?: statusText(row.status),
                    color = palette.muted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (row.status) {
                PlaylistTransferTrackStatus.IN_LIBRARY,
                PlaylistTransferTrackStatus.DOWNLOADED -> Icon(Icons.Rounded.CheckCircle, null, tint = palette.ink)
                PlaylistTransferTrackStatus.SEARCHING,
                PlaylistTransferTrackStatus.DOWNLOADING -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = palette.ink,
                )
                PlaylistTransferTrackStatus.WAITING_FOR_VERIFICATION ->
                    Icon(Icons.Rounded.HourglassTop, null, tint = palette.ink)
                PlaylistTransferTrackStatus.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = palette.ink)
                PlaylistTransferTrackStatus.READY -> Checkbox(
                    checked = row.selected,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.accent,
                        checkmarkColor = palette.onAccent,
                        uncheckedColor = palette.ink,
                    ),
                )
            }
        }
    }
}

@Composable
private fun VerificationCard(
    state: SpotifyPlaylistTransferState,
    palette: EditorialPalette,
    onVerify: () -> Unit,
    onCheck: () -> Unit,
) {
    val challenge = state.verificationChallenge
    EditorialCard(palette, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.HourglassTop, null, tint = palette.ink)
                Text(
                    "Provider verification required",
                    color = palette.ink,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 9.dp),
                )
            }
            Text(
                challenge?.instructions ?: "Complete the provider page once. Harmony will return here and continue the same track.",
                color = palette.muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (challenge?.verificationUrl != null) {
                    EditorialPill("Verify ${providerName(challenge.providerId)}", Icons.Rounded.OpenInNew, onVerify, palette)
                }
                EditorialTextAction(
                    text = if (state.isCheckingVerification) "Checking…" else "Check & continue",
                    onClick = onCheck,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun TransferProgressCard(
    state: SpotifyPlaylistTransferState,
    palette: EditorialPalette,
    onCancel: () -> Unit,
) {
    EditorialCard(palette, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDownload, null, tint = palette.ink)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        if (state.isCheckingVerification) "Checking provider" else "Playlist transfer",
                        color = palette.ink,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        state.activeDetail ?: "Preparing…",
                        color = palette.muted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.isTransferring) {
                    EditorialCircleButton(onCancel, "Pause playlist transfer", palette, size = 38.dp) {
                        Icon(Icons.Rounded.PauseCircle, null, modifier = Modifier.size(19.dp))
                    }
                }
            }
            if (state.activeProgress != null) {
                LinearProgressIndicator(
                    progress = { state.activeProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = palette.ink,
                    trackColor = palette.muted.copy(alpha = 0.25f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = palette.ink,
                    trackColor = palette.muted.copy(alpha = 0.25f),
                )
            }
            if (state.attemptedDownloads > 0) {
                Text(
                    "${state.completedDownloads.coerceAtMost(state.attemptedDownloads)} of ${state.attemptedDownloads} processed",
                    color = palette.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, icon: ImageVector, palette: EditorialPalette) {
    EditorialCard(palette, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = palette.ink, modifier = Modifier.size(19.dp))
            Text(
                text,
                color = palette.ink,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun LoadingRow(text: String, palette: EditorialPalette) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = palette.ink)
        Text(text, color = palette.muted, modifier = Modifier.padding(start = 10.dp))
    }
}

private fun statusText(status: PlaylistTransferTrackStatus): String = when (status) {
    PlaylistTransferTrackStatus.IN_LIBRARY -> "Already in Harmony"
    PlaylistTransferTrackStatus.READY -> "Ready to locate"
    PlaylistTransferTrackStatus.SEARCHING -> "Searching…"
    PlaylistTransferTrackStatus.DOWNLOADING -> "Downloading…"
    PlaylistTransferTrackStatus.DOWNLOADED -> "Downloaded and added"
    PlaylistTransferTrackStatus.WAITING_FOR_VERIFICATION -> "Waiting for verification"
    PlaylistTransferTrackStatus.FAILED -> "Needs attention"
}

private fun providerName(id: String): String = id.substringBefore('-')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun openExternalUri(context: Context, value: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

private const val SPOTIFY_DASHBOARD_URL = "https://developer.spotify.com/dashboard"
