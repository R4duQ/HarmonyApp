package com.harmony.app.navigation

import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.res.stringResource
import com.harmony.app.R
import com.harmony.core.ui.room.ActivityPill
import com.harmony.core.ui.room.RoomIconButton
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomMotion
import com.harmony.feature.downloads.ActivitySheet
import com.harmony.feature.downloads.AddActions
import com.harmony.feature.downloads.AddScreen
import com.harmony.feature.downloads.rememberActivityCounts
import com.harmony.feature.player.PlayerViewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.harmony.feature.discover.navigation.DiscoverRoute
import com.harmony.feature.discover.ui.DiscoverScreen
import com.harmony.feature.equalizer.EqualizerScreen
import com.harmony.feature.home.HomeScreen
import com.harmony.feature.downloads.DownloadsScreen
import com.harmony.feature.downloads.AlbumDownloadScreen
import com.harmony.feature.library.LibraryActionsHost
import com.harmony.feature.library.LibraryActionsViewModel
import com.harmony.feature.discover.provider.ShflAlbumCatalog
import androidx.hilt.navigation.compose.hiltViewModel
import com.harmony.feature.downloads.SpotifyPlaylistTransferScreen
import com.harmony.feature.library.AlbumDetailScreen
import com.harmony.feature.library.LibraryScreen
import com.harmony.feature.player.MiniPlayer
import com.harmony.core.ui.component.catalogPalette
import com.harmony.feature.player.NowPlayingScreen
import com.harmony.feature.playlists.PlaylistDetailScreen
import com.harmony.feature.playlists.PlaylistsScreen
import com.harmony.feature.settings.FlacCheckScreen
import com.harmony.feature.settings.SettingsScreen
import com.harmony.feature.settings.TagEditorScreen
import com.harmony.core.ui.component.FloatingChromeClearance
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.component.ReadableWidth
import com.harmony.core.ui.component.isWideWindow
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder

object Routes {
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val EQUALIZER = "equalizer"
    const val HOME = "home"
    /** Owned by :feature:discover so the route string lives with the screen. */
    const val DISCOVER = DiscoverRoute.ROUTE
    /** The download sources page; Add hands it a search as `downloads?q=…`. */
    const val DOWNLOADS = "downloads"
    const val DOWNLOADS_PATTERN = "downloads?q={q}"
    /** Every way music comes in: one search and four doors. */
    const val ADD = "add"
    const val ALBUM_DOWNLOAD = "album_download/{discoverId}"
    const val SPOTIFY_TRANSFER = "spotify_playlist_transfer"
    const val SETTINGS = "settings"
    const val NOW_PLAYING = "now_playing"
    /** Now Playing with the Play on sheet open (Add › From your computer). */
    const val NOW_PLAYING_PLAY_ON = "now_playing?playOn=true"
    const val NOW_PLAYING_PATTERN = "now_playing?playOn={playOn}"
    const val ALBUM = "album/{albumId}"
    const val PLAYLIST = "playlist/{playlistId}"
    const val SMART_PLAYLIST = "smart_playlist/{smartType}"
    const val FLAC_CHECK = "flac_check"
    const val TAG_EDITOR = "tag_editor"
    const val RECOGNIZE = "recognize"
}

/**
 * Pops [entry] only if it is still the top of the back stack.
 *
 * The playlist page can ask to leave from its Back button and from its
 * swipe-down gesture, and the system back gesture can race either of them.
 * A bare popBackStack() from a page that is already on its way out pops the
 * page UNDER it too — the user lands two screens back. Checking the entry
 * makes every request after the first a no-op, and popping (rather than
 * navigating somewhere) is what returns to the real previous page with its
 * tab, scroll position and state as they were left.
 */
private fun androidx.navigation.NavController.popIfCurrent(
    entry: androidx.navigation.NavBackStackEntry,
): Boolean = currentBackStackEntry?.id == entry.id && popBackStack()

/**
 * A destination laid out for the window: on a tablet the page sits in a
 * centred column of readable width (see [ReadableWidth]); on a phone it is
 * exactly the plain destination. Now Playing is the one page that doesn't
 * use this, because it lays itself out for the whole screen.
 */
private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments = arguments) { entry ->
    if (isWideWindow()) ReadableWidth { content(entry) } else content(entry)
}

/** On a tablet the mini player and download banner stop growing at this width. */
private val WideChromeMaxWidth = 720.dp

private data class TopLevel(val route: String, val item: NavItem)

/**
 * The shell: three destinations (Listen · Library · Add), the pages they open,
 * and the dock (the mini player above the navigation bar) floating over the
 * page. Now Playing covers everything and hides the dock.
 *
 * Every destination's top bar carries the same utilities, from the shell:
 * the Activity pill (what Harmony is doing in the background), Sound and
 * Settings. On a tablet the bar becomes a rail with Sound and Settings at its
 * foot. One look on every page: the Listening Room, no section colours.
 */
@Composable
fun HarmonyApp(
    verificationReturnSignal: Int = 0,
    playlistVerificationReturnSignal: Int = 0,
    spotifyAuthorizationReturnSignal: Int = 0,
    albumVerificationReturnSignal: Int = 0,
    discoveryVerificationReturnSignal: Int = 0,
) {
    val libraryActions: LibraryActionsViewModel = hiltViewModel()
    val player: PlayerViewModel = hiltViewModel()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onNowPlaying = currentRoute == Routes.NOW_PLAYING_PATTERN

    val topLevel = listOf(
        TopLevel(Routes.HOME, NavItem(stringResource(R.string.nav_listen), Icons.Outlined.Headphones, Icons.Rounded.Headphones)),
        TopLevel(Routes.LIBRARY, NavItem(stringResource(R.string.nav_library), Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic)),
        TopLevel(Routes.ADD, NavItem(stringResource(R.string.nav_add), Icons.Outlined.AddCircleOutline, Icons.Rounded.AddCircle)),
    )
    // Screens not yet rebuilt still take an EditorialPalette; it resolves from the room.
    val palette = catalogPalette()

    val wide = isWideWindow()
    val showRail = wide && !onNowPlaying
    val railSpace by animateDpAsState(
        targetValue = if (showRail) NavRailClearance else 0.dp,
        label = "rail-space",
    )

    // Discover's "Search my library" and Identify's "In your library" hand a
    // search to Library as counters, so Library's route and saved state stay
    // as they were; see LibraryScreen.
    var librarySearchSignal by rememberSaveable { mutableIntStateOf(0) }
    var librarySearchText by rememberSaveable { mutableStateOf<String?>(null) }
    var librarySearchResetSignal by rememberSaveable { mutableIntStateOf(0) }
    var showActivity by rememberSaveable { mutableStateOf(false) }

    fun goTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun open(route: String) = navController.navigate(route) { launchSingleTop = true }

    val topLevelIndex = topLevel.indexOfFirst { it.route == currentRoute }
    val onSelectTopLevel: (Int) -> Unit = { index ->
        val route = topLevel[index].route
        if (route == Routes.LIBRARY) librarySearchResetSignal++
        goTo(route)
    }

    val counts = rememberActivityCounts()
    val topActions: @Composable RowScope.() -> Unit = {
        ActivityPill(counts.running, counts.needsYou, counts.failed, onClick = { showActivity = true })
        if (!wide) {
            RoomIconButton(Icons.Rounded.Tune, stringResource(R.string.nav_sound), { open(Routes.EQUALIZER) })
            RoomIconButton(Icons.Rounded.Settings, stringResource(R.string.nav_settings), { open(Routes.SETTINGS) })
        }
    }

    LaunchedEffect(verificationReturnSignal) {
        if (verificationReturnSignal > 0 && currentRoute != Routes.DOWNLOADS_PATTERN) open(Routes.DOWNLOADS)
    }
    LaunchedEffect(playlistVerificationReturnSignal) {
        if (playlistVerificationReturnSignal > 0 && currentRoute != Routes.SPOTIFY_TRANSFER) open(Routes.SPOTIFY_TRANSFER)
    }
    LaunchedEffect(spotifyAuthorizationReturnSignal) {
        if (spotifyAuthorizationReturnSignal > 0 && currentRoute != Routes.SPOTIFY_TRANSFER) open(Routes.SPOTIFY_TRANSFER)
    }
    LaunchedEffect(albumVerificationReturnSignal) {
        if (albumVerificationReturnSignal > 0 && currentRoute != Routes.ALBUM_DOWNLOAD) open(Routes.DOWNLOADS)
    }
    LaunchedEffect(discoveryVerificationReturnSignal) {
        if (discoveryVerificationReturnSignal > 0 && currentRoute != Routes.DISCOVER) open(Routes.DISCOVER)
    }

    Scaffold(
        containerColor = Room.ground,
        // Only the top inset: the dock below reads the bottom inset itself.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
    ) { innerPadding ->
        // Measured, not assumed: the dock's height changes with the mini
        // player, whether the bar is on this route, and the gesture inset.
        var chromeHeight by remember { mutableStateOf(FloatingChromeClearance) }
        val density = LocalDensity.current

        CompositionLocalProvider(LocalFloatingChromeHeight provides chromeHeight) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding(), start = railSpace),
                // Fade-through between pages (docs/redesign/04 §6).
                enterTransition = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300, easing = RoomMotion.EmphasizedDecelerate))
                },
                exitTransition = {
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200, easing = RoomMotion.EmphasizedAccelerate))
                },
                popEnterTransition = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300, easing = RoomMotion.EmphasizedDecelerate))
                },
                popExitTransition = {
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200, easing = RoomMotion.EmphasizedAccelerate))
                },
            ) {
                page(Routes.HOME) {
                    HomeScreen(
                        onOpenNowPlaying = { open(Routes.NOW_PLAYING) },
                        onOpenAlbum = { navController.navigate("album/$it") },
                        onOpenPlaylist = { navController.navigate("playlist/$it") },
                        onOpenSmart = { navController.navigate("smart_playlist/${it.name}") },
                        onOpenDiscover = { open(Routes.DISCOVER) },
                        onOpenAdd = { goTo(Routes.ADD) },
                        onOpenLibrary = { librarySearchResetSignal++; goTo(Routes.LIBRARY) },
                        // Applied before Listen starts the mix, so the first song fits the mood.
                        onApplyMood = player::onMoodFilterSelected,
                        contentPadding = PaddingValues(bottom = chromeHeight),
                        topActions = topActions,
                    )
                }
                page(Routes.LIBRARY) {
                    LibraryScreen(
                        onAlbumClick = { navController.navigate("album/$it") },
                        onArtistClick = { navController.navigate("artist/${android.net.Uri.encode(it)}") },
                        openSearchSignal = librarySearchSignal,
                        requestedSearchQuery = librarySearchText,
                        resetSearchSignal = librarySearchResetSignal,
                        playlists = {
                            PlaylistsScreen(
                                onPlaylistClick = { navController.navigate("playlist/$it") },
                                onSmartPlaylistClick = { navController.navigate("smart_playlist/${it.name}") },
                                onSpotifyTransfer = { open(Routes.SPOTIFY_TRANSFER) },
                                embedded = true,
                            )
                        },
                        topActions = topActions,
                    )
                }
                page(Routes.ADD) {
                    AddScreen(
                        actions = AddActions(
                            onSearch = { q -> open("downloads?q=${android.net.Uri.encode(q)}") },
                            onIdentify = { open(Routes.RECOGNIZE) },
                            onSpotify = { open(Routes.SPOTIFY_TRANSFER) },
                            onDiscover = { open(Routes.DISCOVER) },
                            onComputer = { open(Routes.NOW_PLAYING_PLAY_ON) },
                        ),
                        contentPadding = PaddingValues(bottom = chromeHeight),
                        topActions = topActions,
                    )
                }
                page(Routes.DISCOVER) {
                    DiscoverScreen(
                        onOpenPlaylists = { librarySearchResetSignal++; goTo(Routes.LIBRARY) },
                        // The playlist Discover just saved opens over Discover; Back returns to the flow.
                        onOpenPlaylist = { navController.navigate("playlist/$it") },
                        downloadPanel = { batch -> com.harmony.feature.downloads.DiscoveryDownloadPanel(batch,
                            onOpenDownloads = { open(Routes.DOWNLOADS) }) },
                    )
                }
                page(
                    Routes.ALBUM,
                    arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
                ) { entry ->
                    AlbumDetailScreen(
                        onBack = { navController.popIfCurrent(entry) },
                        onOpenArtist = { navController.navigate("artist/${android.net.Uri.encode(it)}") },
                        onOpenAlbum = { navController.navigate("album/$it") },
                    )
                }
                page(
                    "artist/{artistName}",
                    arguments = listOf(navArgument("artistName") { type = NavType.StringType }),
                ) {
                    com.harmony.feature.library.ArtistDetailScreen(
                        onAlbumClick = { navController.navigate("album/$it") },
                        onBack = { navController.popBackStack() },
                    )
                }
                page(Routes.PLAYLISTS) {
                    PlaylistsScreen(
                        onPlaylistClick = { navController.navigate("playlist/$it") },
                        onSmartPlaylistClick = { navController.navigate("smart_playlist/${it.name}") },
                        onSpotifyTransfer = { open(Routes.SPOTIFY_TRANSFER) },
                    )
                }
                page(Routes.SPOTIFY_TRANSFER) {
                    SpotifyPlaylistTransferScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDownloads = { open(Routes.DOWNLOADS) },
                        onOpenPlaylist = { playlistId -> navController.navigate("playlist/$playlistId") },
                        verificationReturnSignal = playlistVerificationReturnSignal,
                    )
                }
                page(
                    Routes.PLAYLIST,
                    arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                ) { entry ->
                    PlaylistDetailScreen(onBack = { navController.popIfCurrent(entry) })
                }
                page(
                    Routes.SMART_PLAYLIST,
                    arguments = listOf(navArgument("smartType") { type = NavType.StringType }),
                ) { entry ->
                    PlaylistDetailScreen(onBack = { navController.popIfCurrent(entry) })
                }
                page(Routes.EQUALIZER) {
                    EqualizerScreen(onBack = { navController.popBackStack() })
                }
                page(
                    Routes.DOWNLOADS_PATTERN,
                    arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    DownloadsScreen(
                        onOpenAlbum = { open("album_download/${android.net.Uri.encode(it)}") },
                        onOpenRecognize = { open(Routes.RECOGNIZE) },
                        initialQuery = entry.arguments?.getString("q"),
                        onBack = { navController.popIfCurrent(entry) },
                    )
                }
                page(Routes.ALBUM_DOWNLOAD, arguments = listOf(navArgument("discoverId") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("discoverId").orEmpty()
                    val album = ShflAlbumCatalog.albums.firstOrNull { it.id == id }
                    AlbumDownloadScreen(id, album?.title ?: "Album", album?.artist.orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenDownloads = { open(Routes.DOWNLOADS) },
                        onReview = libraryActions::openReview, artistAliases = album?.artistAliases.orEmpty())
                }
                page(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenFlacCheck = { open(Routes.FLAC_CHECK) },
                        onOpenTagEditor = { open(Routes.TAG_EDITOR) },
                        onOpenEqualizer = { open(Routes.EQUALIZER) },
                        onBack = { navController.popBackStack() },
                    )
                }
                page(Routes.RECOGNIZE) {
                    com.harmony.feature.downloads.RecognizeScreen(
                        onBack = { navController.popBackStack() },
                        // A recognised song opens the sources page already searching for it.
                        onOpenDownloads = { open(Routes.DOWNLOADS) },
                        onSearchLibrary = { query ->
                            librarySearchText = query
                            librarySearchSignal++
                            goTo(Routes.LIBRARY)
                        },
                    )
                }
                page(Routes.FLAC_CHECK) { FlacCheckScreen() }
                page(Routes.TAG_EDITOR) { TagEditorScreen() }
                composable(
                    Routes.NOW_PLAYING_PATTERN,
                    arguments = listOf(navArgument("playOn") { type = NavType.BoolType; defaultValue = false }),
                    // The player grows up out of the mini player and sinks back into it.
                    enterTransition = {
                        androidx.compose.animation.slideInVertically(
                            androidx.compose.animation.core.tween(RoomMotion.Large, easing = RoomMotion.EmphasizedDecelerate)
                        ) { it / 2 } +
                            androidx.compose.animation.scaleIn(
                                androidx.compose.animation.core.tween(RoomMotion.Large, easing = RoomMotion.EmphasizedDecelerate),
                                initialScale = 0.92f,
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f),
                            ) +
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220))
                    },
                    exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) },
                    popExitTransition = {
                        androidx.compose.animation.slideOutVertically(
                            androidx.compose.animation.core.tween(RoomMotion.Medium, easing = RoomMotion.EmphasizedAccelerate)
                        ) { it / 2 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(260, delayMillis = 40))
                    },
                ) { entry ->
                    NowPlayingScreen(
                        onBack = { navController.popBackStack() },
                        onOpenEqualizer = { open(Routes.EQUALIZER) },
                        openPlayOn = entry.arguments?.getBoolean("playOn") == true,
                    )
                }
            }

            // The dock, over the page: the mini player above the navigation
            // bar, on the ground with a hairline at its top. Pages reserve its
            // measured height, so nothing ever reads through it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = railSpace),
            ) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (wide) Modifier.widthIn(max = WideChromeMaxWidth) else Modifier)
                        .onSizeChanged { with(density) { chromeHeight = it.height.toDp() + 8.dp } }
                        .fillMaxWidth()
                        .then(
                            if (!wide && topLevelIndex >= 0) {
                                Modifier
                                    .background(Room.surface)
                                    .drawBehind { drawLine(palette.line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
                            } else Modifier,
                        )
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    Spacer(Modifier.height(8.dp))
                    // Always composed, so it can lift into Now Playing and drop back.
                    MiniPlayer(
                        onExpand = {
                            if (navController.currentDestination?.route != Routes.NOW_PLAYING_PATTERN) open(Routes.NOW_PLAYING)
                        },
                        palette = palette,
                        shown = !onNowPlaying,
                    )
                    if (!wide && topLevelIndex >= 0) {
                        Spacer(Modifier.height(4.dp))
                        RoomNavBar(
                            items = topLevel.map { it.item },
                            selectedIndex = topLevelIndex,
                            onSelect = onSelectTopLevel,
                        )
                    }
                }
            }

            if (showRail) {
                RoomNavRail(
                    items = topLevel.map { it.item },
                    selectedIndex = topLevelIndex,
                    onSelect = onSelectTopLevel,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                    footer = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            RoomIconButton(Icons.Rounded.Tune, stringResource(R.string.nav_sound), { open(Routes.EQUALIZER) })
                            RoomIconButton(Icons.Rounded.Settings, stringResource(R.string.nav_settings), { open(Routes.SETTINGS) })
                        }
                    },
                )
            }
        }
        }
    }
    if (showActivity) {
        ActivitySheet(onDismiss = { showActivity = false }, onOpenAll = { open(Routes.DOWNLOADS) })
    }
    LibraryActionsHost(libraryActions)
}
