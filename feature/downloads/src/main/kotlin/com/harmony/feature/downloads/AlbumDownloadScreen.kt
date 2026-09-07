package com.harmony.feature.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.harmony.core.ui.component.coralPalette
import com.harmony.core.ui.network.InternetNotice

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
    val album = journeys.firstOrNull { it.id == albumId }
    val active = jobs.firstOrNull { !it.state.isFinished }
    val thisActive = active?.tags?.contains(albumId) == true
    var sourceName by rememberSaveable(albumId) { mutableStateOf(album?.source ?: "SPOTIFLAC") }
    var formatName by rememberSaveable(albumId) { mutableStateOf(album?.format ?: "FLAC_LOSSLESS") }
    var wifiOnly by rememberSaveable { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    val palette = coralPalette()
    val context = LocalContext.current
    LaunchedEffect(albumId, internet.ready, busy) {
        if (album == null) viewModel.search(albumId, title, artist, artistAliases)
    }

    LazyColumn(Modifier.fillMaxSize().background(palette.field), contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.ink) }
                Text("MAKE IT YOURS", color = palette.ink, letterSpacing = 1.5.sp, fontSize = 11.sp)
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (album != null) AsyncImage(album.coverUrl, "Album cover", Modifier.size(90.dp).clip(RoundedCornerShape(16.dp)))
                Column(Modifier.weight(1f).padding(start = if (album == null) 0.dp else 16.dp)) {
                    Text(album?.title ?: title, color = palette.ink, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                    Text(album?.artist ?: artist, color = palette.muted)
                }
            }
        }
        if (message != null || browserError != null) item {
            Surface(color = palette.ink.copy(alpha = 0.07f), shape = RoundedCornerShape(14.dp)) {
                Text(browserError ?: message.orEmpty(), Modifier.fillMaxWidth().padding(14.dp), color = palette.ink)
            }
        }
        if (!internet.ready) item { InternetNotice(internet) }
        if (album == null && internet.ready) {
            item { Text("Choose an edition. You'll see its complete tracklist before downloading.", color = palette.muted) }
            items(editions, key = { it.id }) { edition ->
                OutlinedButton(onClick = { viewModel.choose(albumId, edition) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(edition.title, fontWeight = FontWeight.Bold)
                        Text(edition.artist)
                    }
                }
            }
            item {
                if (busy) CircularProgressIndicator(color = palette.ink)
                else TextButton(onClick = { viewModel.retrySearch(albumId, title, artist, artistAliases) }) { Text("Search again", color = palette.ink) }
            }
        } else if (album != null) {
            item {
                Text("${album.availableCount}/${album.tracks.size} on your phone · ${album.heardCount}/${album.tracks.size} heard", color = palette.ink)
                Text("Choose the whole album or just the tracks you want. Existing files are reused.", color = palette.muted, fontSize = 12.sp)
                LinearProgressIndicator(progress = { album.availableCount.toFloat() / album.tracks.size.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = palette.ink)
            }
            item {
                DownloadSourceSelector(DownloadSource.valueOf(sourceName), { sourceName = it.name }, palette, enabled = internet.ready && active == null && !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpotiFlacOutputFormat.entries.forEach { format ->
                        FilterChip(selected = formatName == format.name, onClick = { formatName = format.name },
                            enabled = internet.ready && active == null && !busy,
                            label = { Text(if (format == SpotiFlacOutputFormat.MP3_320 && sourceName == "SOULSEEK") "MP3" else format.label) })
                    }
                }
                Text(if (sourceName == "SPOTIFLAC" && formatName == "MP3_320")
                    "Smaller files on your phone. SpotiFLAC downloads lossless audio first, then converts it to MP3."
                    else if (sourceName == "SOULSEEK") "Fast available peers first. Connect your Soulseek account in Downloads."
                    else "Original lossless audio. One track at a time keeps temporary storage and battery use bounded.", color = palette.muted, fontSize = 13.sp)
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
                    OutlinedButton(onClick = viewModel::pause) { Text("Pause album") }
                } else if (album.availableCount < album.tracks.size) {
                    Button(onClick = { viewModel.start(albumId, DownloadSource.valueOf(sourceName), SpotiFlacOutputFormat.fromName(formatName), wifiOnly) },
                        enabled = internet.ready && active == null && !busy && album.selectedMissing.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp))
                        Text("Download ${album.selectedMissing.size} selected tracks")
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
            if (album.availableCount > 0) item {
                Button(onClick = { viewModel.play(albumId) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, null); Text("  Play ${album.availableCount} tracks in order")
                }
                Text("After you've heard at least 90% of every track on the full album, Harmony will ask what you want to keep. For a partial download, you can manage the songs below at any time.",
                    color = palette.muted, fontSize = 13.sp)
                TextButton(onClick = { onReview(albumId) }, enabled = active == null) { Text("Manage songs to keep", color = palette.ink) }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${album.selectedMissing.size} missing tracks selected", color = palette.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.select(albumId, selected = true) }, enabled = !thisActive && !busy && internet.ready) { Text("All") }
                    TextButton(onClick = { viewModel.select(albumId, selected = false) }, enabled = !thisActive && !busy && internet.ready) { Text("None") }
                }
            }
            items(album.tracks, key = { it.id }) { track ->
                Surface(color = palette.ink.copy(alpha = 0.055f), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (track.uri == null) Checkbox(track.selectedForDownload,
                            onCheckedChange = { viewModel.select(albumId, track.id, it) }, enabled = !thisActive && !busy && internet.ready,
                            modifier = Modifier.semantics { contentDescription = "Download ${track.title}" })
                        else Icon(Icons.Rounded.CheckCircle, "Already on your phone", tint = palette.ink,
                            modifier = Modifier.padding(end = 10.dp).size(20.dp))
                        Text("${track.number}".padStart(2, '0'), color = palette.muted, fontSize = 12.sp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, color = palette.ink, fontWeight = FontWeight.SemiBold)
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
