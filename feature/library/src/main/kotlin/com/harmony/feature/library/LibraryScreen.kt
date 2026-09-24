package com.harmony.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.harmony.core.model.Song
import com.harmony.core.ui.component.ArcSongList
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialTab
import com.harmony.core.ui.component.EmptyState
import com.harmony.core.ui.component.GlassCircleButton
import com.harmony.core.ui.component.GlassSearchBar
import com.harmony.core.ui.component.GlassSegmentedTabs
import com.harmony.core.ui.component.GlassSongCard
import com.harmony.core.ui.component.VinylAlbumCover
import com.harmony.core.ui.component.amberPalette
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Library home: permission gate, search, then Songs / Albums / Artists tabs.
 *
 * VISUAL: the amber "editorial" treatment — a flat saturated field, near-
 * black ink, every list entry an outlined rounded card with a circled play
 * affordance on the right. Shared construction lives in core:ui
 * (EditorialLook.kt); the Playlists screen uses the same kit in green.
 *
 * BEHAVIOR: all pre-existing logic is untouched — the permission strategy
 * (gate lives here so settings/EQ stay reachable without the permission; on
 * grant the MediaStore observer triggers the scan, no manual refresh path),
 * the FTS search that replaces tab content in place, the paged songs list,
 * and the windowed-queue tap behavior in the ViewModel.
 *
 * One deliberate trade in the redesign: song entries are cards now, and the
 * old swipe-right-to-queue gesture from SongRow doesn't carry over (a swipe
 * sliding an outlined card sideways breaks the printed-poster illusion the
 * outline creates). "Play next" is still one gesture away — it moved into
 * each card's ⋮ menu alongside "Add to playlist".
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    /**
     * Incremented by the shell when another screen (currently Discover) asks
     * Library to open its search. A counter rather than a boolean because the
     * request has to be distinguishable from the last one; a route argument
     * was avoided because Library is the start destination and changing its
     * route pattern would touch save/restore behaviour for no gain.
     */
    openSearchSignal: Int = 0,
    requestedSearchQuery: String? = null,
    /**
     * Incremented by the shell on an ORDINARY entry into Library — tapping
     * the Library tab, or Discover's "browse library" — as opposed to a
     * search request or popping back from Now Playing. Closes search if it
     * was left open, so it doesn't stay stuck open forever the way it used
     * to when searchActive was itself saveable; see the note on
     * [handledResetSignal].
     */
    resetSearchSignal: Int = 0,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val palette = amberPalette()
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val needsNotificationPermission = Build.VERSION.SDK_INT >= 33

    fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    var audioGranted by remember { mutableStateOf(hasPermission(audioPermission)) }
    var notificationsGranted by remember {
        mutableStateOf(
            !needsNotificationPermission || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        audioGranted = results[audioPermission] ?: audioGranted
        if (needsNotificationPermission) {
            notificationsGranted =
                results[Manifest.permission.POST_NOTIFICATIONS] ?: notificationsGranted
        }
    }

    if (!audioGranted) {
        PermissionPane(
            palette = palette,
            title = "Your music stays on your device",
            body = "Harmony needs access to your audio files to build your library, and " +
                "permission to show a notification so you can see and control what's " +
                "playing — including in the background. Nothing is ever uploaded — " +
                "all scanning and analysis happens locally.",
            buttonText = "Allow access",
            onButtonClick = {
                val perms = if (needsNotificationPermission) {
                    arrayOf(audioPermission, Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    arrayOf(audioPermission)
                }
                launcher.launch(perms)
            },
        )
        return
    }

    if (!notificationsGranted) {
        PermissionPane(
            palette = palette,
            title = "See what's playing",
            body = "Without notification access, playback keeps working in the background, " +
                "but you won't see a notification or lock-screen controls for it.",
            buttonText = "Allow notifications",
            onButtonClick = { launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
            secondaryText = "Not now",
            onSecondaryClick = { notificationsGranted = true },
        )
        return
    }

    // rememberSaveable, not remember: opening an album or artist tears this
    // screen's composition down, so plain remember would drop you back on
    // Songs (and out of search) every time you came back. Saveable state is
    // held by the nav back stack entry and restored on pop.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // rememberSaveable so that going Library -> Now Playing -> back keeps
    // whatever search state you left behind — that round trip doesn't pop
    // this screen off the back stack, so its saved state naturally
    // survives.
    //
    // The trade this reintroduces: on its own, rememberSaveable can't tell
    // "returning from Now Playing" apart from "switching tabs away and
    // back" — both restore Library's saved state through the exact same
    // mechanism, so a search left open would come back in EITHER case,
    // which is what caused the original "keyboard reappears every time you
    // enter Library" bug. [resetSearchSignal] below is what breaks that
    // tie: it only fires on an ordinary tab entry, never on a Now Playing
    // return, so search only survives the case you actually want it to.
    var searchActive by rememberSaveable { mutableStateOf(false) }
    // Deliberately NOT saveable, and that is the whole mechanism: it is true
    // only when search was opened by an action in THIS composition. Going to
    // Now Playing and back restores searchActive from the back stack but
    // resets this, so the bar comes back open with the keyboard still down,
    // exactly as it was left.
    var raiseKeyboard by remember { mutableStateOf(false) }
    // Saveable, so navigating away and back doesn't replay an old request:
    // the signal only counts if it is newer than the one already handled.
    var handledSearchSignal by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openSearchSignal) {
        if (openSearchSignal > handledSearchSignal) {
            handledSearchSignal = openSearchSignal
            requestedSearchQuery?.let(viewModel::onSearchQueryChange)
            searchActive = true
            raiseKeyboard = true
        }
    }
    // Same replay-guard pattern as handledSearchSignal above, for the
    // reset signal.
    var handledResetSignal by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(resetSearchSignal) {
        if (resetSearchSignal > handledResetSignal) {
            handledResetSignal = resetSearchSignal
            searchActive = false
            viewModel.onSearchQueryChange("")
        }
    }
    // Songs can be browsed as a plain list or as the arc wheel. Both exist
    // because they're good at different things: the wheel is for drifting
    // through the library, the list is the only one of the two you can
    // actually find a specific song in among 1200+.
    var arcMode by rememberSaveable { mutableStateOf(false) }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    // Collected once at screen level: SongsTab renders from it, and the tab
    // bar's count badge reads itemCount from the same collection.
    val pagedSongs = viewModel.songsPaged.collectAsLazyPagingItems()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field),
    ) {
        // ---- Header: glass search pill, with the wheel toggle trailing it
        // on the Songs tab ----
        GlassSearchBar(
            active = searchActive,
            autoFocus = raiseKeyboard,
            value = searchQuery,
            onValueChange = viewModel::onSearchQueryChange,
            onFieldClick = { searchActive = true; raiseKeyboard = true },
            onClear = {
                if (searchQuery.isEmpty()) {
                    searchActive = false
                } else {
                    viewModel.onSearchQueryChange("")
                }
            },
            palette = palette,
            // The pill sat 10dp under the status bar with the tab row right
            // beneath it and the list starting immediately after — three
            // bands stacked with no air between them, which is what made the
            // top read as cut off rather than as a header.
            modifier = Modifier.padding(top = 10.dp),
            trailing = if (!searchActive && selectedTab == 0) {
                {
                    GlassCircleButton(
                        onClick = { arcMode = !arcMode },
                        contentDescription = if (arcMode) "Switch to list view" else "Switch to wheel view",
                        palette = palette,
                        filled = arcMode,
                    ) {
                        Icon(
                            if (arcMode) Icons.Rounded.ViewList else Icons.Rounded.Album,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            } else null,
        )

        // ---- Search results OR the normal tabbed browser ----
        AnimatedContent(
            targetState = searchActive && searchQuery.isNotBlank(),
            transitionSpec = { fadeIn().togetherWith(fadeOut()) },
            label = "library-search-toggle",
        ) { showingSearch ->
            if (showingSearch) {
                SearchResultsContent(viewModel, palette, onAlbumClick, onArtistClick)
            } else {
                Column(Modifier.fillMaxSize()) {
                    GlassSegmentedTabs(
                        tabs = listOf(
                            EditorialTab(
                                "Songs",
                                pagedSongs.itemCount.takeIf { it > 0 },
                                Icons.Rounded.MusicNote,
                            ),
                            EditorialTab(
                                "Albums",
                                albums.size.takeIf { it > 0 },
                                Icons.Rounded.Album,
                            ),
                            EditorialTab(
                                "Artists",
                                artists.size.takeIf { it > 0 },
                                Icons.Rounded.Person,
                            ),
                        ),
                        selected = selectedTab,
                        onSelect = { selectedTab = it },
                        palette = palette,
                        modifier = Modifier.padding(
                            start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp,
                        ),
                    )
                    // A hairline that fades out toward the edges instead of
                    // ruling straight across. A full-width line reads as a
                    // hard division — the header and the list are the same
                    // surface, and this only needs to suggest where one ends.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        palette.line.copy(alpha = 0.35f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                    // Tab content slides in the direction of travel, so
                    // switching tabs reads as horizontal movement between
                    // panes rather than a repaint.
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val enter = androidx.compose.animation.slideInHorizontally(
                                androidx.compose.animation.core.tween(280)
                            ) { full -> if (forward) full / 3 else -full / 3 } +
                                fadeIn(androidx.compose.animation.core.tween(280))
                            val exit = androidx.compose.animation.slideOutHorizontally(
                                androidx.compose.animation.core.tween(200)
                            ) { full -> if (forward) -full / 3 else full / 3 } +
                                fadeOut(androidx.compose.animation.core.tween(200))
                            enter.togetherWith(exit)
                        },
                        label = "library-tabs",
                    ) { tab ->
                        when (tab) {
                            0 -> SongsTab(viewModel, pagedSongs, palette, arcMode)
                            1 -> AlbumsTab(albums, palette, onAlbumClick)
                            2 -> ArtistsTab(artists, palette, onArtistClick)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun PermissionPane(
    palette: EditorialPalette,
    title: String,
    body: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    secondaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.field)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(
            onClick = onButtonClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = palette.onAccent,
            ),
        ) { Text(buttonText) }
        if (secondaryText != null && onSecondaryClick != null) {
            TextButton(onClick = onSecondaryClick) {
                Text(secondaryText, color = palette.ink)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SongsTab(
    viewModel: LibraryViewModel,
    songs: LazyPagingItems<Song>,
    palette: EditorialPalette,
    arcMode: Boolean,
) {
    var addToPlaylistSongId by remember { mutableStateOf<Long?>(null) }
    if (songs.itemCount == 0) {
        EmptyState(
            title = "No music yet",
            subtitle = "Songs appear here automatically as your device is scanned.",
        )
        return
    }
    if (arcMode) {
        ArcSongList(
            itemCount = songs.itemCount,
            // get(), not peek(): get() tells Paging the index is being
            // displayed, which is what triggers the next page to load. peek()
            // only reads what's already there, so everything past page one
            // stayed null forever and rendered as a blank gap.
            songAt = { index -> songs[index] },
            palette = palette,
            onPlay = { index -> songs.peek(index)?.let(viewModel::onPagedSongClick) },
            onDelete = viewModel::deleteSong,
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = LocalFloatingChromeHeight.current),
    ) {
        items(
            count = songs.itemCount,
            key = { index -> songs.peek(index)?.id ?: "placeholder-$index" },
        ) { index ->
            val song = songs[index]
            if (song != null) {
                GlassSongCard(
                    song = song,
                    palette = palette,
                    onClick = { viewModel.onPagedSongClick(song) },
                    onPlayNext = { viewModel.addToQueue(song) },
                    onAddToPlaylist = { addToPlaylistSongId = song.id },
                    onRemove = { viewModel.deleteSong(song) },
                    removeLabel = "Delete from phone",
                    onSwipeToQueue = { viewModel.addToQueue(song) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    addToPlaylistSongId?.let { id ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { addToPlaylistSongId = null }) {
            AddToPlaylistSheet(songId = id, onDismiss = { addToPlaylistSongId = null })
        }
    }
}

@Composable
private fun AlbumsTab(
    albums: List<com.harmony.core.model.Album>,
    palette: EditorialPalette,
    onAlbumClick: (Long) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyState("No albums yet", "Albums appear as your library is scanned.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp, end = 14.dp, top = 14.dp, bottom = LocalFloatingChromeHeight.current,
        ),
    ) {
        items(albums, key = { it.id }) { album ->
            EditorialCard(
                palette = palette,
                onClick = { onAlbumClick(album.id) },
                modifier = Modifier
                    .padding(6.dp)
                    .fillMaxWidth()
                    .animateItem(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    // Aspect follows the sleeve fraction: the block is one
                    // sleeve tall and a bit wider, so the disc has somewhere
                    // to peek out without the cell growing.
                    VinylAlbumCover(
                        artworkUri = album.artworkUri,
                        albumName = album.name,
                        palette = palette,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f / 0.76f),
                    )
                    Text(
                        text = album.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = album.albumArtist ?: "${album.songCount} songs",
                        fontSize = 12.sp,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<com.harmony.core.model.Artist>,
    palette: EditorialPalette,
    onArtistClick: (String) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyState("No artists yet", "Artists appear as your library is scanned.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = LocalFloatingChromeHeight.current),
    ) {
        // Keyed on the name, not the id. GROUP BY names.name in
        // CollectionDao.observeArtists guarantees one row per name, so the
        // name is the only value here that cannot collide by construction.
        items(artists, key = { it.name }) { artist ->
            EditorialCard(
                palette = palette,
                onClick = { onArtistClick(artist.name) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .animateItem(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            artist.name,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            color = palette.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            artistCounts(artist.albumCount, artist.songCount),
                            fontSize = 12.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // An arrow, not a play glyph: tapping opens the artist
                    // page, and a triangle here would promise playback.
                    EditorialCircleButton(
                        onClick = { onArtistClick(artist.name) },
                        contentDescription = "Open ${artist.name}",
                        palette = palette,
                        size = 40.dp,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Search results
// ---------------------------------------------------------------------------

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultsContent(
    viewModel: LibraryViewModel,
    palette: EditorialPalette,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    var addToPlaylistSongId by remember { mutableStateOf<Long?>(null) }
    val empty = results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty()

    @Composable
    fun sectionHeader(title: String) {
        Text(
            title.uppercase(),
            fontSize = 11.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.muted,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (isSearching) {
            // A hairline, not a spinner over the list: the hits from the
            // previous keystroke stay readable and tappable while the next
            // pass runs, so the list narrows instead of blanking.
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = palette.ink,
                trackColor = palette.line,
            )
        }
        // "No matches" is a claim about a query that has actually been run.
        // On the first keystroke there is nothing to keep on screen and the
        // search is still in flight, so the bar above is the only honest
        // feedback — saying the word failed here would be wrong twice over,
        // because the hits usually land a moment later.
        if (empty) {
            if (!isSearching) {
                EmptyState("No matches", "Try a different spelling or a shorter search.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalFloatingChromeHeight.current)) {
                if (results.songs.isNotEmpty()) {
                    item { sectionHeader("Songs") }
                    itemsIndexed(results.songs) { index, song ->
                        GlassSongCard(
                            song = song,
                            palette = palette,
                            onClick = { viewModel.onSongClick(results.songs, index) },
                            onPlayNext = { viewModel.addToQueue(song) },
                            onAddToPlaylist = { addToPlaylistSongId = song.id },
                            onRemove = { viewModel.deleteSong(song) },
                            removeLabel = "Delete from phone",
                            onSwipeToQueue = { viewModel.addToQueue(song) },
                        )
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item { sectionHeader("Albums") }
                    items(results.albums, key = { "album-${it.id}" }) { album ->
                        EditorialCard(
                            palette = palette,
                            onClick = { onAlbumClick(album.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(album.artworkUri, album.name, Modifier.size(48.dp), cornerRadius = 10.dp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(
                                        album.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        album.albumArtist ?: "${album.songCount} songs",
                                        fontSize = 12.sp,
                                        color = palette.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.artists.isNotEmpty()) {
                    item { sectionHeader("Artists") }
                    items(results.artists, key = { "artist-${it.name}" }) { artist ->
                        EditorialCard(
                            palette = palette,
                            onClick = { onArtistClick(artist.name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    artist.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.ink,
                                )
                                Text(
                                    artistCounts(artist.albumCount, artist.songCount),
                                    fontSize = 12.sp,
                                    color = palette.muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    addToPlaylistSongId?.let { id ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { addToPlaylistSongId = null }) {
            AddToPlaylistSheet(songId = id, onDismiss = { addToPlaylistSongId = null })
        }
    }
}

/** "1 album  •  12 songs": singular when there is one. */
internal fun artistCounts(albums: Int, songs: Int): String =
    "${if (albums == 1) "1 album" else "$albums albums"}  •  ${if (songs == 1) "1 song" else "$songs songs"}"
