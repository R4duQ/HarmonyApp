package com.harmony.feature.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.harmony.core.ui.network.InternetNotice
import com.harmony.core.ui.component.GlassInsetPanel
import com.harmony.core.ui.component.EditorialChoiceChips
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.GlassCard
import com.harmony.core.ui.component.amberPalette
import com.harmony.core.ui.component.LocalFloatingChromeHeight

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumDownloadScreen(
    albumId: String, title: String, artist: String, onBack: () -> Unit, onOpenDownloads: () -> Unit,
    onReview: (String) -> Unit, artistAliases: Set<String> = emptySet(), viewModel: AlbumDownloadViewModel = hiltViewModel(),
) {
    val journeys by viewModel.journeys.collectAsStateWithLifecycle()
    val internet by viewModel.internet.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val progress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val editions by viewModel.editions.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val verificationUrl by viewModel.verificationUrl.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    val favoriteUris = remember(favorites) { favorites.map { it.uri }.toSet() }
    val soulseekConnection by viewModel.soulseekConnection.collectAsStateWithLifecycle()
    val album = journeys.firstOrNull { it.id == albumId }
    val active = jobs.firstOrNull { !it.state.isFinished }
    val thisActive = active?.tags?.contains(albumId) == true
    var sourceName by rememberSaveable(albumId) { mutableStateOf(album?.source ?: "SPOTIFLAC") }
    var formatName by rememberSaveable(albumId) { mutableStateOf(album?.format ?: "FLAC_LOSSLESS") }
    var wifiOnly by rememberSaveable(albumId) { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    // Soulseek is the only engine here that can be *selected* but unusable:
    // SpotiFLAC needs no session to queue an album. So this is the one
    // combination the screen has to call out rather than let you discover
    // by pressing Download.
    val soulseekBlocked = sourceName == "SOULSEEK" &&
        soulseekConnection.status != SoulseekConnectionStatus.CONNECTED
    // amber, not coral: this screen is reached from Downloads and from
    // Discover, and Downloads was moved onto Library's amber palette. A
    // coral screen in the middle of that flow read as a different section.
    val palette = amberPalette()
    val context = LocalContext.current
    LaunchedEffect(albumId, internet.ready, busy) {
        if (album == null) viewModel.search(albumId, title, artist, artistAliases)
    }

    LazyColumn(Modifier.fillMaxSize().background(palette.field), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = LocalFloatingChromeHeight.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.ink) }
                EditorialSectionLabel("Album download", palette)
            }
            GlassCard(palette = palette, modifier = Modifier.padding(top = 10.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (album != null) AsyncImage(album.coverUrl, "Album cover", Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)))
                    Column(Modifier.weight(1f).padding(start = if (album == null) 0.dp else 16.dp)) {
                        Text(album?.title ?: title, color = palette.ink, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                        Text(album?.artist ?: artist, color = palette.muted)
                    }
                }
            }
        }
        if (message != null || browserError != null) item {
            GlassInsetPanel(palette = palette, shape = RoundedCornerShape(14.dp)) {
                Text(browserError ?: message.orEmpty(), Modifier.fillMaxWidth().padding(14.dp), color = palette.ink)
            }
        }
        if (!internet.ready) item { InternetNotice(internet) }
        if (album == null && internet.ready) {
            item { Text("Choose an edition. You'll see its complete tracklist before downloading.", color = palette.muted) }
            items(editions, key = { it.id }) { edition ->
                // Was a bare OutlinedButton wrapping a Column, which gave
                // each edition a full-width outlined slab and no artwork —
                // the covers are the fastest way to tell two editions of
                // the same album apart, and AlbumEdition carries one.
                NotificationPill(
                    title = edition.title,
                    detail = edition.artist,
                    palette = palette,
                    coverUrl = edition.cover,
                    enabled = !busy,
                    onClick = { viewModel.choose(albumId, edition) },
                    action = {
                        PillAction(
                            icon = Icons.Rounded.ChevronRight,
                            contentDescription = "Choose ${edition.title}",
                            palette = palette,
                            enabled = !busy,
                            onClick = { viewModel.choose(albumId, edition) },
                        )
                    },
                )
            }
            item {
                if (busy) CircularProgressIndicator(color = palette.ink)
                else TextButton(onClick = { viewModel.retrySearch(albumId, title, artist, artistAliases) }) { Text("Search again", color = palette.ink) }
            }
        } else if (album != null) {
            item {
                GlassCard(palette = palette) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${album.availableCount}/${album.tracks.size} on your phone · ${album.heardCount}/${album.tracks.size} heard",
                            color = palette.ink, fontWeight = FontWeight.SemiBold)
                        Text("Choose the whole album or just the tracks you want. Existing files are reused.",
                            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        LinearProgressIndicator(progress = { album.availableCount.toFloat() / album.tracks.size.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(50)),
                            color = palette.accent, trackColor = palette.ink.copy(alpha = 0.12f))
                    }
                }
            }
            item {
              GlassCard(palette = palette) {
               Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // TransferCapableSources: an album download resolves each
                // track from metadata, which the converter can't do — it
                // needs a pasted URL per track.
                DownloadSourceSelector(DownloadSource.valueOf(sourceName), {
                    sourceName = it.name
                    if (it == DownloadSource.SOULSEEK && formatName == SpotiFlacOutputFormat.FLAC_HI_RES_96.name) {
                        formatName = SpotiFlacOutputFormat.FLAC_LOSSLESS.name
                    }
                }, palette, enabled = internet.ready && active == null && !busy, sources = TransferCapableSources)
                // EditorialChoiceChips instead of Material FilterChip: the
                // chips elsewhere in Downloads are the editorial ones, and
                // FilterChip brought its own Material container and tick
                // that ignored the palette entirely.
                val formats = remember(sourceName) {
                    SpotiFlacOutputFormat.entries.filter {
                        sourceName == "SPOTIFLAC" || it != SpotiFlacOutputFormat.FLAC_HI_RES_96
                    }
                }
                EditorialChoiceChips(
                    options = formats.map {
                        if (sourceName == "SOULSEEK") it.historyLabel.removeSuffix(" 320 kbps") else it.label
                    },
                    selectedIndex = formats.indexOfFirst { it.name == formatName }.coerceAtLeast(0),
                    onSelect = { formatName = formats[it].name },
                    palette = palette,
                )
                Text(if (sourceName == "SPOTIFLAC" && formatName == "MP3_320")
                    "Smaller files on your phone. SpotiFLAC downloads lossless audio first, then converts it to MP3."
                    else if (sourceName == "SOULSEEK") "Fast available peers first. Connect your Soulseek account in Downloads."
                    else SpotiFlacOutputFormat.fromName(formatName).summary, color = palette.muted, fontSize = 13.sp)
                if (soulseekBlocked) {
                    GlassInsetPanel(palette = palette) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                when (soulseekConnection.status) {
                                    SoulseekConnectionStatus.CONNECTING -> "Connecting to Soulseek…"
                                    else -> "Soulseek is not connected"
                                },
                                color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            )
                            Text(
                                "Sign in to the peer network in Downloads, then come back — the tracks you picked here are kept.",
                                color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                            )
                            TextButton(onClick = onOpenDownloads, enabled = internet.ready) {
                                Text("Open Soulseek settings", color = palette.ink)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wifiOnly, { wifiOnly = it }, enabled = internet.ready && active == null && !busy)
                    Text("Unmetered network only", color = palette.ink)
                }
                if (thisActive) {
                    Text(if (active?.state == androidx.work.WorkInfo.State.RUNNING) "Downloading · you can leave this screen"
                        else "Queued · waiting for network or retry", color = palette.ink)
                    progress?.takeIf { it.albumId == albumId }?.let { download ->
                        Text("${download.title} · ${download.detail.orEmpty()}", color = palette.muted, fontSize = 12.sp)
                        val fraction = download.fraction
                        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = palette.ink)
                        else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = palette.ink)
                    }
                    EditorialPill("Pause album", Icons.Rounded.Pause, viewModel::pause, palette)
                } else if (album.availableCount < album.tracks.size) {
                    // soulseekBlocked included here so the button reflects
                    // reality instead of throwing from the ViewModel's check
                    // after you press it.
                    val canStart = internet.ready && active == null && !busy &&
                        album.selectedMissing.isNotEmpty() && !soulseekBlocked
                    // The one primary action on the screen, so it keeps a
                    // solid accent fill rather than becoming another pill —
                    // a screen where every control looks identical gives the
                    // eye nowhere to land.
                    Surface(
                        onClick = {
                            if (canStart) {
                                viewModel.start(
                                    albumId,
                                    DownloadSource.valueOf(sourceName),
                                    SpotiFlacOutputFormat.fromName(formatName),
                                    wifiOnly,
                                )
                            }
                        },
                        enabled = canStart,
                        shape = RoundedCornerShape(50),
                        color = if (canStart) palette.accent else palette.ink.copy(alpha = 0.10f),
                        contentColor = if (canStart) palette.onAccent else palette.muted,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(19.dp)); Spacer(Modifier.width(10.dp))
                            Text(AlbumDownloadPolicy.downloadLabel(album), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (active != null) {
                        Text("Another album is queued. Open it in Downloads to pause it first.", color = palette.muted)
                        TextButton(onClick = onOpenDownloads) { Text("Open album downloads", color = palette.ink) }
                    }
                }
                if (sourceName == "SOULSEEK") TextButton(onClick = onOpenDownloads, enabled = internet.ready) { Text("Soulseek connection settings", color = palette.ink) }
                if (sourceName == "SPOTIFLAC") {
                    TextButton(onClick = { viewModel.verify(albumId) }, enabled = internet.ready && active == null && !busy) { Text("Provider verification", color = palette.ink) }
                    verificationUrl?.let { url -> TextButton(onClick = {
                        val uri = Uri.parse(url)
                        if (uri.scheme == "https") runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            .onFailure { browserError = "Could not open a browser. Enable one and try again." }
                        else browserError = "The provider returned an unsupported verification link."
                    }, enabled = internet.ready) { Text("Open verification in browser", color = palette.ink) } }
                }
               }
              }
            }
            if (album.availableCount > 0) item {
                GlassCard(palette = palette) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        EditorialPill(
                            "Play ${album.availableCount} tracks in order",
                            Icons.Rounded.PlayArrow,
                            { if (!busy) viewModel.play(albumId) },
                            palette,
                            Modifier.fillMaxWidth(),
                        )
                        Text("After you've heard at least 90% of every track on the full album, Harmony will ask what you want to keep. For a partial download, you can manage the songs below at any time.",
                            color = palette.muted, fontSize = 13.sp)
                        TextButton(onClick = { onReview(albumId) }, enabled = active == null) { Text("Manage songs to keep", color = palette.ink) }
                    }
                }
            }
            item {
                EditorialSectionLabel("Tracks", palette, Modifier.padding(top = 6.dp))
                GlassInsetPanel(palette = palette, modifier = Modifier.padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${album.selectedMissing.size} missing selected", color = palette.ink,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.select(albumId, selected = true) }, enabled = !thisActive && !busy) { Text("All", color = palette.ink) }
                        TextButton(onClick = { viewModel.select(albumId, selected = false) }, enabled = !thisActive && !busy) { Text("None", color = palette.muted) }
                    }
                }
            }
            items(album.tracks, key = { it.id }) { track ->
                GlassInsetPanel(palette = palette, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().then(if (track.uri == null) Modifier.toggleable(
                        value = track.selectedForDownload, enabled = !thisActive && !busy, role = Role.Checkbox,
                        onValueChange = { viewModel.select(albumId, track.id, it) },
                    ) else Modifier).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (track.uri == null) Checkbox(track.selectedForDownload,
                            onCheckedChange = null, enabled = !thisActive && !busy,
                            modifier = Modifier.semantics { contentDescription = "Download ${track.title}" })
                        else Icon(Icons.Rounded.CheckCircle, "Already on your phone", tint = palette.ink,
                            modifier = Modifier.padding(end = 10.dp).size(20.dp))
                        Text(if (album.tracks.any { it.disc > 1 }) "${track.disc}.${track.number.toString().padStart(2, '0')}"
                            else "${track.number}".padStart(2, '0'), color = palette.muted, fontSize = 12.sp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, color = palette.ink, fontWeight = FontWeight.SemiBold)
                            Text("${track.artist} · ${track.durationMs / 60_000}:${((track.durationMs / 1_000) % 60).toString().padStart(2, '0')}", color = palette.muted, fontSize = 12.sp)
                            Text(track.error ?: if (track.heard) "Heard · ${track.status}" else track.status, color = palette.muted, fontSize = 12.sp)
                        }
                        if (track.uri != null) IconButton(onClick = { track.uri?.let(viewModel::toggleFavorite) }, enabled = !busy) {
                            Icon(if (track.uri in favoriteUris) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                "Favorite ${track.title}", tint = palette.ink)
                        }
                    }
                }
            }
        }
    }
}
