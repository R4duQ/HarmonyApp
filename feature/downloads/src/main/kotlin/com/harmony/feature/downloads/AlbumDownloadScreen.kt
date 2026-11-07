package com.harmony.feature.downloads

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
 * One album, in Cyber-Glass: choose the edition, then its header with the
 * master progress and one main action (Download All / Pause All / Resume /
 * Retry failed), the source and format, and the numbered tracklist with
 * each track's live state. Reached from Downloads (Albums), from the album
 * card in the queue, and from Discover.
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
    var browserError by remember { mutableStateOf<String?>(null) }
    // Soulseek is the only engine here that can be *selected* but unusable:
    // the provider engine needs no session to queue an album.
    val soulseekBlocked = sourceName == "SOULSEEK" &&
        soulseekConnection.status != SoulseekConnectionStatus.CONNECTED
    val glass = CyberGlassColors
    val palette = CyberGlassPalette
    val context = LocalContext.current
    LaunchedEffect(albumId, internet.ready, busy) {
        if (album == null) viewModel.search(albumId, title, artist, artistAliases)
    }
    val engine = if (sourceName == "SOULSEEK") "Soulseek" else viewModel.engineName
    val format = SpotiFlacOutputFormat.fromName(formatName)

    LazyColumn(
        Modifier.fillMaxSize().background(glass.Page),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = LocalFloatingChromeHeight.current + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = glass.TextPrimary) }
                Text("ALBUM DOWNLOAD", color = glass.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
            }
        }
        if (message != null || browserError != null) item {
            CyberNotice(browserError ?: message.orEmpty(), error = browserError != null || message?.contains("not", true) == true,
                onDismiss = { browserError = null; viewModel.dismissMessage() })
        }
        if (!internet.ready) item { InternetNotice(internet) }

        if (album == null) {
            item {
                CyberGlassPanel {
                    Column(Modifier.padding(16.dp)) {
                        Text(title, color = glass.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(artist, color = glass.TextSecondary, fontSize = 13.sp)
                        Text("Choose an edition. You'll see its complete tracklist before downloading.",
                            color = glass.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (internet.ready) {
                items(editions, key = { it.id }) { edition ->
                    EditionRow(edition, enabled = !busy) { viewModel.choose(albumId, edition) }
                }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (busy) CircularProgressIndicator(color = glass.Cyan)
                        else TextButton(onClick = { viewModel.retrySearch(albumId, title, artist, artistAliases) }) { Text("Search again", color = glass.Cyan) }
                    }
                }
            }
        } else if (batch != null) {
            item {
                AlbumBatchHeader(
                    batch = batch,
                    quality = qualityBadgeFor(format),
                    engine = engine,
                    masterEnabled = !busy && internet.ready && !(batch.canDownload && !batch.active && (soulseekBlocked || batch.total == 0)),
                    onMaster = {
                        when {
                            batch.canPause -> viewModel.pause(albumId)
                            batch.canResume || batch.canRetryFailed -> viewModel.resume(albumId)
                            batch.canDownload -> viewModel.start(albumId, DownloadSource.valueOf(sourceName), format, wifiOnly)
                        }
                    },
                )
            }
            if (batch.canCancel) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    NeonPillButton("Cancel album", Icons.Rounded.Close, { viewModel.cancel(albumId) }, enabled = !busy, prominent = false, destructive = true)
                }
            }
            if (otherActive && !batch.active) item {
                CyberNotice("Another album is downloading. This one will wait its turn in the queue.")
            }
            if (!batch.active) item {
                CyberGlassPanel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("SOURCE & QUALITY", color = glass.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                        // TransferCapableSources: an album resolves each track from metadata,
                        // which the converter can't do (it needs a pasted URL per track).
                        DownloadSourceSelector(DownloadSource.valueOf(sourceName), {
                            sourceName = it.name
                            if (it == DownloadSource.SOULSEEK && formatName == SpotiFlacOutputFormat.FLAC_HI_RES_96.name) {
                                formatName = SpotiFlacOutputFormat.FLAC_LOSSLESS.name
                            }
                        }, palette, enabled = internet.ready && !busy, sources = TransferCapableSources)
                        val formats = remember(sourceName) {
                            SpotiFlacOutputFormat.entries.filter { sourceName == "SPOTIFLAC" || it != SpotiFlacOutputFormat.FLAC_HI_RES_96 }
                        }
                        EditorialChoiceChips(
                            options = formats.map { if (sourceName == "SOULSEEK") it.historyLabel.removeSuffix(" 320 kbps") else it.label },
                            selectedIndex = formats.indexOfFirst { it.name == formatName }.coerceAtLeast(0),
                            onSelect = { formatName = formats[it].name },
                            palette = palette,
                        )
                        Text(if (sourceName == "SPOTIFLAC" && formatName == "MP3_320")
                            "Smaller files on your phone. $engine downloads lossless audio first, then converts it to MP3."
                            else if (sourceName == "SOULSEEK") "Fast available peers first. Connect your Soulseek account in Downloads."
                            else "${format.summary} Downloaded with $engine; album, artist, track and disc numbers, date and cover are written into every file.",
                            color = glass.TextSecondary, fontSize = 12.sp)
                        if (soulseekBlocked) {
                            CyberNotice(
                                (if (soulseekConnection.status == SoulseekConnectionStatus.CONNECTING) "Connecting to Soulseek… " else "Soulseek is not connected. ") +
                                    "Sign in to the peer network in Downloads, then come back; the tracks you picked are kept.",
                                error = true,
                            )
                            TextButton(onClick = onOpenDownloads, enabled = internet.ready) { Text("Open Soulseek settings", color = glass.Cyan) }
                        }
                        Row(
                            Modifier.clip(CircleShape).clickable(enabled = internet.ready && !busy, role = Role.Checkbox) { wifiOnly = !wifiOnly },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(wifiOnly, null, enabled = internet.ready && !busy,
                                colors = CheckboxDefaults.colors(checkedColor = glass.Cyan, checkmarkColor = glass.OnAccent, uncheckedColor = glass.ChipBorder))
                            Text("Unmetered network only", color = glass.TextPrimary, fontSize = 13.sp)
                        }
                        if (sourceName == "SPOTIFLAC") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { viewModel.verify(albumId) }, enabled = internet.ready && !busy) {
                                    Text("Provider verification", color = glass.Cyan)
                                }
                                verificationUrl?.let { url -> TextButton(onClick = {
                                    val uri = Uri.parse(url)
                                    if (uri.scheme == "https") runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                        .onFailure { browserError = "Could not open a browser. Enable one and try again." }
                                    else browserError = "The provider returned an unsupported verification link."
                                }, enabled = internet.ready) { Text("Open verification in browser", color = glass.Cyan) } }
                            }
                        }
                    }
                }
            }
            if (album.availableCount > 0) item {
                CyberGlassPanel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonPillButton("Play ${album.availableCount} tracks in order", Icons.Rounded.PlayArrow,
                            { if (!busy) viewModel.play(albumId) }, Modifier.fillMaxWidth(), prominent = false)
                        Text("After you've heard at least 90% of every track on the full album, Harmony will ask what you want to keep. " +
                            "For a partial download, you can manage the songs below at any time.", color = glass.TextSecondary, fontSize = 12.sp)
                        TextButton(onClick = { onReview(albumId) }, enabled = !batch.active) { Text("Manage songs to keep", color = glass.Cyan) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("TRACKS", color = glass.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp,
                        modifier = Modifier.weight(1f))
                    if (!batch.active) {
                        Text("${album.selectedMissing.size} missing selected", color = glass.TextSecondary, fontSize = 12.sp)
                        TextButton(onClick = { viewModel.select(albumId, selected = true) }, enabled = !busy) { Text("All", color = glass.Cyan) }
                        TextButton(onClick = { viewModel.select(albumId, selected = false) }, enabled = !busy) { Text("None", color = glass.TextSecondary) }
                    }
                }
            }
            item {
                CyberGlassPanel(corner = 18.dp) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        batch.tracks.forEach { row ->
                            AlbumTrackLine(
                                row = row,
                                selectable = !batch.active,
                                onToggle = { viewModel.select(albumId, row.track.id, it) },
                                onRetry = { if (row.phase == AlbumTrackPhase.VERIFY) viewModel.verify(albumId) else viewModel.retryTrack(albumId, row.track.id) },
                                onSkip = { viewModel.skipTrack(albumId, row.track.id) },
                                controlsEnabled = !busy && !(row.canRetry && batch.active),
                                trailing = row.track.uri?.let { uri -> @Composable {
                                    IconButton(onClick = { viewModel.toggleFavorite(uri) }, enabled = !busy, modifier = Modifier.size(36.dp)) {
                                        Icon(if (uri in favoriteUris) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            "Favorite ${row.track.title}", tint = if (uri in favoriteUris) glass.Purple else glass.TextSecondary,
                                            modifier = Modifier.size(20.dp))
                                    }
                                } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** An edition to choose: cover, title, artist, track count. */
@Composable
private fun EditionRow(edition: AlbumEdition, enabled: Boolean, onChoose: () -> Unit) {
    val glass = CyberGlassColors
    CyberGlassPanel(corner = 18.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onChoose).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArtwork(edition.cover, 56.dp, corner = 12.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(edition.title, color = glass.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(edition.artist, if (edition.trackCount > 0) "${edition.trackCount} tracks" else null).filterNotNull().joinToString(" · "),
                    color = glass.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, "Choose ${edition.title}", tint = glass.Cyan)
        }
    }
}
