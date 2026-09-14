package com.harmony.feature.discover.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.coralPalette
import com.harmony.core.ui.network.InternetNotice
import com.harmony.feature.discover.model.*
import com.harmony.feature.discover.provider.*
import com.harmony.core.ui.component.FloatingChromeClearance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private val Sleeve = Color(0xFF171A19)
private val Paper = Color(0xFFF7F3EB)
private val QuietPaper = Color(0xFFBFC4BD)

/** The parent scrolls vertically; the native pager owns only horizontal swipes. */
@Composable
fun DiscoverScreen(
    onSearchLibrary: (String) -> Unit = {},
    onDownloadAlbum: (String) -> Unit = {},
    provider: DiscoveryProvider = ShflDiscoveryProvider,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val internet by viewModel.internet.collectAsStateWithLifecycle()
    var swipeMode by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberLazyListState()
    val deckVersion = Triple(swipeMode, state.round.number, state.round.completed)
    var previousDeck by remember { mutableStateOf(deckVersion) }
    LaunchedEffect(deckVersion, internet.ready) {
        val changed = previousDeck != deckVersion
        previousDeck = deckVersion
        if (swipeMode && internet.ready && (state.round.completed || changed)) {
            // Next/Undo replaces a tall reveal; its old scroll offset can hide the new song.
            scroll.scrollToItem(if (state.round.completed) 2 else 3)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.stopPreview() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.stopPreview() }
    }
    LaunchedEffect(swipeMode) { viewModel.stopPreview() }
    val palette = coralPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var lastLaunchAt by remember { mutableLongStateOf(-1_000L) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val open: (String) -> Unit = { url ->
        val now = SystemClock.elapsedRealtime()
        if (internet.ready && now - lastLaunchAt >= 800L) {
            lastLaunchAt = now
            val result = if (provider.permits(url)) ShflLauncher.open(context, url, palette.field.toArgb())
                else ShflLaunchResult.BLOCKED
            if (result != ShflLaunchResult.OPENED) scope.launch {
                snackbar.showSnackbar(DiscoverStrings.LAUNCH_FAILED)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message.text,
                actionLabel = message.undoListenedId?.let { "Undo" }, withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) message.undoListenedId?.let {
                viewModel.setListened(it, false)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(palette.field)) {
        LazyColumn(state = scroll, contentPadding = PaddingValues(bottom = FloatingChromeClearance),
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 600.dp).fillMaxSize()) {
            item(key = "heading") {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("THE ALBUM CLUB", color = palette.muted, fontSize = 10.sp,
                                letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                            Text("Discover.", color = palette.ink, fontSize = 42.sp,
                                lineHeight = 46.sp, letterSpacing = (-1.5).sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { showInfo = true }) {
                            Icon(Icons.Rounded.Info, "How album recommendations work", tint = palette.ink)
                        }
                    }
                    Text("Know the song. Discover the album.", color = palette.ink,
                        fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp))
                    Text("Swipe on songs. Find your next full listen.", color = palette.muted,
                        fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            if (!internet.ready) item(key = "internet") {
                InternetNotice(internet, Modifier.padding(22.dp))
                Text("Your downloaded music is still available in Library.", color = palette.muted,
                    modifier = Modifier.padding(horizontal = 22.dp))
            } else {
            item(key = "mode") {
                Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(swipeMode, onClick = { swipeMode = true; viewModel.selectShelf(AlbumShelf.FOR_YOU) },
                        label = { Text("Swipe songs") }, leadingIcon = { Icon(Icons.Rounded.Favorite, null, Modifier.size(17.dp)) })
                    FilterChip(!swipeMode, onClick = { swipeMode = false }, label = { Text("Albums") },
                        leadingIcon = { Icon(Icons.Rounded.Album, null, Modifier.size(17.dp)) })
                }
            }
            if (!swipeMode) item(key = "shelves") {
                Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth().selectableGroup()
                    .clip(RoundedCornerShape(16.dp)).background(palette.ink.copy(alpha = 0.07f))) {
                    AlbumShelf.entries.forEach { shelf ->
                        val selected = state.shelf == shelf
                        Box(Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (selected) palette.ink else Color.Transparent)
                            .selectable(selected, onClick = { viewModel.selectShelf(shelf) }, role = Role.Tab)
                            .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text(if (shelf == AlbumShelf.SAVED) "Saved · ${state.savedCount}" else shelf.label,
                                color = if (selected) palette.field else palette.ink,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (!swipeMode || !state.round.completed) item(key = "genres") {
                LazyRow(contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf<AlbumGenre?>(null) + AlbumGenre.entries, key = { it?.name ?: "all" }) { genre ->
                        FilterChip(selected = state.genre == genre, onClick = { viewModel.selectGenre(genre) },
                            label = { Text(genre?.label ?: "All sounds") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent, labelColor = palette.muted,
                                selectedContainerColor = palette.ink.copy(alpha = 0.12f), selectedLabelColor = palette.ink))
                    }
                }
            }
            item(key = "deck") {
                when {
                    state.loading -> Row(Modifier.fillMaxWidth().padding(32.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = palette.ink, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Finding your next full listen…", color = palette.ink)
                    }
                    swipeMode -> SongSwipeDeck(state, palette, viewModel,
                        onSeeAlbums = {
                            swipeMode = false
                            viewModel.selectGenre(null)
                            viewModel.selectShelf(if (state.round.completed && state.roundRecommendation == null) AlbumShelf.ALL else AlbumShelf.FOR_YOU)
                        }, onDownload = onDownloadAlbum, onOpen = open)
                    state.albums.isEmpty() -> EmptyAlbumShelf(state, palette) {
                        viewModel.selectGenre(null)
                        viewModel.selectShelf(AlbumShelf.ALL)
                    }
                    else -> key(state.batchRevision, state.albums.map { it.album.id }) {
                        // Capture this deck's revision; reading delegated state in the callback
                        // would give an outgoing pager the replacement deck's revision.
                        val displayedRevision = state.batchRevision
                        AlbumDeck(state, palette, viewModel.restoredAlbumId(), viewModel.restoredAlbumIndex(),
                            { viewModel.rememberAlbum(it, displayedRevision) },
                            { viewModel.refresh(displayedRevision) }, { viewModel.selectGenre(null) },
                            viewModel::toggleSaved, viewModel::toggleFamiliar,
                            viewModel::setListened, viewModel::playEntry, viewModel::playLocalAlbum,
                            onSearchLibrary, open, onDownloadAlbum)
                    }
                }
            }
            item(key = "source") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    HorizontalDivider(color = palette.line.copy(alpha = 0.3f))
                    Text("A selection of ${ShflAlbumCatalog.albums.size} albums on The Shfl",
                        fontSize = 12.sp, color = palette.ink, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp))
                    Text(when {
                        state.libraryUnavailable -> "Your library is unavailable right now. You can still explore every album."
                        state.familiarCount > 0 -> "${state.familiarCount} albums connect to tracks in your library. Listening notes by Harmony."
                        else -> "Find a song you recognize, then stay for the record. Listening notes by Harmony."
                    }, color = palette.muted, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    TextButton(onClick = { open(provider.shuffle.url) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Explore more on The Shfl", color = palette.ink)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = palette.ink, modifier = Modifier.size(14.dp))
                    }
                }
            }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false },
        title = { Text("From a song to a whole album") },
        text = { Text("Browse a selection of familiar albums with verified pages on The Shfl. " +
            "Swipe right to like a song, left to dislike it, or Skip without changing your taste. " +
            "Every ten choices reveal one album with a personal explanation and an original Harmony listening note. " +
            "Your round and its result are saved until you choose Next ten songs. Undo also works on the final vote. " +
            "Your choices influence album, artist and genre recommendations and stay on this device. " +
            "Refresh shows up to 24 different albums and restarts at the first card. " +
            "After you explore the selection, it reshuffles for another cycle. " +
            "If every album in a genre already fits on screen, choose All sounds or another genre for more albums. " +
            "Harmony also uses your local tracks and writes its own short listening notes. " +
            "‘I know it’ lets you tell Harmony when a song is familiar. Saved albums stay on this device. " +
            "Previews use your local recording when available, otherwise Deezer's preview service. Some songs have no preview. " +
            "This is a curated selection, not The Shfl’s live catalogue. Harmony is not affiliated with The Shfl.",
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } })
}

@Composable
private fun AlbumDeck(
    state: DiscoverUiState, palette: EditorialPalette, restoredId: String?, restoredIndex: Int, onSettled: (String) -> Unit,
    onRefresh: () -> Unit, onAllSounds: () -> Unit, onSave: (String) -> Unit, onFamiliar: (String) -> Unit,
    onListened: (String, Boolean) -> Unit, onPlayEntry: (AlbumSuggestion) -> Unit,
    onPlayLocal: (AlbumSuggestion) -> Unit, onSearch: (String) -> Unit, onOpen: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    val pager = rememberPagerState(initialPage = state.albums.indexOfFirst { it.album.id == restoredId }.takeIf { it >= 0 }
        ?: restoredIndex.coerceIn(0, state.albums.lastIndex.coerceAtLeast(0)),
        pageCount = { state.albums.size })
    val currentAlbums by rememberUpdatedState(state.albums)
    val settledCallback by rememberUpdatedState(onSettled)
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        snapshotFlow { currentAlbums.getOrNull(pager.settledPage)?.album?.id }
            .filterNotNull().distinctUntilChanged().collect { settledCallback(it) }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.shelf == AlbumShelf.SAVED) "YOUR NEXT LISTENS" else "BEYOND THE SINGLE",
                color = palette.muted, fontSize = 10.sp, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.canRefresh) TextButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, null, tint = palette.ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val nextCount = minOf(AlbumBatches.SIZE, state.remainingCount.takeIf { it > 0 } ?: (state.ranked.size - state.albums.size))
                Text("Refresh · next $nextCount", color = palette.ink, fontSize = 12.sp)
            } else if (state.genre != null) TextButton(onClick = onAllSounds) {
                Text("All sounds", color = palette.ink, fontSize = 12.sp)
            }
        }
        Text(if (state.remainingCount > 0) "Batch ${state.batchNumber} · ${state.remainingCount} more to explore" else
            if (state.canRefresh) "Batch ${state.batchNumber} · selection explored; refresh reshuffles it" else
                "All ${state.albums.size} albums in this selection" +
                    if (state.genre != null) " · choose All sounds for more" else "",
            color = palette.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        HorizontalPager(state = pager, key = { state.albums[it].album.id },
            contentPadding = PaddingValues(horizontal = 24.dp), pageSpacing = 12.dp,
            verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) { index ->
            val item = state.albums.getOrNull(index) ?: return@HorizontalPager
            AlbumSleeve(item, Modifier.fillMaxWidth().widthIn(max = 480.dp).graphicsLayer {
                val distance = ((pager.currentPage - index) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                scaleX = 1f - distance * 0.035f
                scaleY = 1f - distance * 0.035f
                alpha = 1f - distance * 0.18f
            }, onSave = { onSave(item.album.id) }, onFamiliar = { onFamiliar(item.album.id) },
                onListened = { onListened(item.album.id, !item.listened) }, onPlayEntry = { onPlayEntry(item) },
                onPlayLocal = { onPlayLocal(item) }, onSearch = { onSearch(item.album.title) },
                onOpen = { onOpen(item.album.shflUrl) }, onDownload = { onDownload(item.album.id) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(enabled = pager.currentPage > 0 && !pager.isScrollInProgress,
                onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous album",
                    tint = palette.ink.copy(alpha = if (pager.currentPage > 0) 1f else 0.3f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics { contentDescription = "Album ${pager.currentPage + 1} of ${state.albums.size}" }) {
                Text("${(pager.currentPage + 1).coerceAtMost(state.albums.size)} / ${state.albums.size}",
                    color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Swipe left or right", color = palette.muted, fontSize = 11.sp)
            }
            IconButton(enabled = pager.currentPage < state.albums.lastIndex && !pager.isScrollInProgress,
                onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(currentAlbums.lastIndex)) } }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next album",
                    tint = palette.ink.copy(alpha = if (pager.currentPage < state.albums.lastIndex) 1f else 0.3f))
            }
        }
    }
}

@Composable
private fun AlbumSleeve(
    item: AlbumSuggestion, modifier: Modifier, onSave: () -> Unit, onFamiliar: () -> Unit,
    onListened: () -> Unit, onPlayEntry: () -> Unit, onPlayLocal: () -> Unit,
    onSearch: () -> Unit, onOpen: () -> Unit, onDownload: () -> Unit,
) {
    val album = item.album
    val accent = Color(album.color)
    Surface(modifier, shape = RoundedCornerShape(26.dp), color = Sleeve, contentColor = Paper,
        shadowElevation = 5.dp) {
        Column {
            Box(Modifier.padding(12.dp).fillMaxWidth()) {
                AlbumCover(album, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(17.dp)))
                Surface(Modifier.align(Alignment.BottomStart).padding(10.dp), color = Sleeve.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp)) {
                    Text("${album.year}  /  ${album.genres.first().label}", color = Paper, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = CircleShape,
                    color = Sleeve.copy(alpha = 0.9f)) {
                    IconToggleButton(checked = item.saved, onCheckedChange = { onSave() }, modifier = Modifier.size(48.dp)) {
                        Icon(if (item.saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (item.saved) "Remove ${album.title} from saved albums" else "Save ${album.title} for later",
                            tint = if (item.saved) accent else Paper)
                    }
                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                Text(album.artist, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(album.title, color = Paper, fontSize = 28.sp, lineHeight = 32.sp,
                    letterSpacing = (-0.5).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(18.dp))
                Surface(color = Paper.copy(alpha = 0.065f), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(when {
                            item.startingSong != null -> "FROM YOUR LIBRARY"
                            item.familiar -> "A SONG YOU KNOW"
                            else -> "RECOGNIZE THIS SONG?"
                        }, color = accent, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.startingTitle, color = Paper, fontSize = 15.sp,
                                lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (item.startingSong != null) IconButton(onClick = onPlayEntry) {
                                Icon(Icons.Rounded.PlayArrow, "Play local track ${item.startingTitle}", tint = accent)
                            }
                        }
                        if (item.startingSong == null) TextButton(onClick = onFamiliar, contentPadding = PaddingValues(0.dp)) {
                            Icon(if (item.familiar) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                null, tint = accent, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (item.familiar) "Known · tap to undo" else "I know it", color = accent, fontSize = 12.sp)
                        }
                    }
                }
                Text("WHY THE WHOLE ALBUM", color = QuietPaper, fontSize = 9.sp, letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                Text(album.listeningNote, color = Paper.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 22.sp)
                Button(onClick = onDownload, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Sleeve),
                    modifier = Modifier.padding(top = 20.dp).fillMaxWidth().heightIn(min = 50.dp)) {
                    Text("Download / open album", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                }
                TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text("Read on The Shfl", color = accent)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = accent, modifier = Modifier.size(16.dp))
                }
                if (item.match.albumSongs.isNotEmpty()) OutlinedButton(onClick = onPlayLocal,
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, QuietPaper.copy(alpha = 0.4f)),
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Paper, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    val count = item.match.albumSongs.size
                    Text("Play $count local ${if (count == 1) "track" else "tracks"}", color = Paper)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    TextButton(onClick = onSearch, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                        Icon(Icons.Rounded.Search, null, tint = QuietPaper, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Find locally", color = QuietPaper, fontSize = 12.sp)
                    }
                    TextButton(onClick = onListened, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                        Icon(if (item.listened) Icons.Rounded.CheckCircle else Icons.Rounded.CheckCircleOutline,
                            null, tint = if (item.listened) accent else QuietPaper, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (item.listened) "Listened · undo" else "Heard the album", color = if (item.listened) accent else QuietPaper,
                            fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AlbumCover(album: DiscoverAlbum, modifier: Modifier) {
    val context = LocalContext.current
    var failed by remember(album.id) { mutableStateOf(false) }
    var retry by remember(album.id) { mutableIntStateOf(0) }
    val accent = Color(album.color)
    Box(modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.45f), Sleeve))),
        contentAlignment = Alignment.Center) {
        // Visible during loading and after a failed image request; no blank card offline.
        Canvas(Modifier.fillMaxSize().padding(35.dp)) {
            val radius = size.minDimension / 2f
            drawCircle(Sleeve.copy(alpha = 0.65f), radius)
            repeat(7) { drawCircle(accent.copy(alpha = 0.18f), radius * (0.4f + it * 0.08f), style = Stroke(1.dp.toPx())) }
            drawCircle(accent, radius * 0.24f)
            drawCircle(Sleeve, radius * 0.035f)
        }
        key(album.id, retry) {
            AsyncImage(model = ImageRequest.Builder(context).data(album.coverUrl).crossfade(true).build(),
                contentDescription = "${album.title} album cover", contentScale = ContentScale.Fit,
                onSuccess = { failed = false }, onError = { failed = true }, modifier = Modifier.fillMaxSize())
        }
        if (failed) TextButton(onClick = { failed = false; retry++ },
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            colors = ButtonDefaults.textButtonColors(containerColor = Sleeve.copy(alpha = 0.9f), contentColor = Paper)) {
            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Retry cover", fontSize = 11.sp)
        }
    }
}

@Composable
private fun EmptyAlbumShelf(state: DiscoverUiState, palette: EditorialPalette, onBrowse: () -> Unit) {
    Surface(Modifier.padding(24.dp).fillMaxWidth(), color = Sleeve, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Album, null, tint = Paper, modifier = Modifier.size(62.dp))
            Text(when {
                state.shelf == AlbumShelf.SAVED && state.savedCount == 0 -> "Make room for a full listen."
                state.genre != null -> "No albums in this selection."
                else -> "You've explored this selection."
            }, color = Paper, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 20.dp))
            Text(if (state.shelf == AlbumShelf.SAVED && state.savedCount == 0)
                "Tap the bookmark on an album to keep it here for later."
            else "Browse all albums, including records you've marked as listened.",
                color = QuietPaper, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
            Button(onClick = onBrowse, colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                modifier = Modifier.padding(top = 20.dp)) { Text("Browse albums") }
        }
    }
}
