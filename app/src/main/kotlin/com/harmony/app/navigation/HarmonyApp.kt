package com.harmony.app.navigation

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlaylistPlay
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
import com.harmony.feature.downloads.DownloadStatusBanner
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
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.amberPalette
import com.harmony.core.ui.component.animatedEditorialPalette
import com.harmony.core.ui.component.bluePalette
import com.harmony.core.ui.component.coralPalette
import com.harmony.core.ui.component.greenPalette
import com.harmony.core.ui.component.lavenderPalette
import com.harmony.core.ui.component.stonePalette
import com.harmony.feature.player.NowPlayingScreen
import com.harmony.feature.playlists.PlaylistDetailScreen
import com.harmony.feature.playlists.PlaylistsScreen
import com.harmony.feature.settings.FlacCheckScreen
import com.harmony.feature.settings.SettingsScreen
import com.harmony.feature.settings.TagEditorScreen
import com.harmony.core.ui.component.FloatingChromeClearance

object Routes {
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val EQUALIZER = "equalizer"
    const val HOME = "home"
    /** Owned by :feature:discover so the route string lives with the screen. */
    const val DISCOVER = DiscoverRoute.ROUTE
    const val DOWNLOADS = "downloads"
    const val ALBUM_DOWNLOAD = "album_download/{discoverId}"
    const val SPOTIFY_TRANSFER = "spotify_playlist_transfer"
    const val SETTINGS = "settings"
    const val NOW_PLAYING = "now_playing"
    const val ALBUM = "album/{albumId}"
    const val PLAYLIST = "playlist/{playlistId}"
    const val SMART_PLAYLIST = "smart_playlist/{smartType}"
    const val FLAC_CHECK = "flac_check"
    const val TAG_EDITOR = "tag_editor"
}

private data class TopLevel(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/**
 * Shell layout: content / mini player / bottom navigation, stacked. The mini
 * player is part of the scaffold (not each screen) so it persists across
 * every top-level destination, and hides itself on the full player screen
 * where it would be redundant.
 */
@Composable
fun HarmonyApp(
    verificationReturnSignal: Int = 0,
    playlistVerificationReturnSignal: Int = 0,
    spotifyAuthorizationReturnSignal: Int = 0,
    albumVerificationReturnSignal: Int = 0,
) {
    val libraryActions: LibraryActionsViewModel = hiltViewModel()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Five destinations. Two features gave up a bottom slot and neither lost
    // anything: Equalizer moved to Settings and the Now Playing overflow menu,
    // Settings moved to the Home header's gear. Both keep their routes, their
    // ViewModels and their state, and both gained a back affordance because
    // they no longer have the navigation bar underneath them.
    val topLevel = listOf(
        TopLevel(Routes.HOME, "Home", Icons.Rounded.Home),
        TopLevel(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
        TopLevel(Routes.PLAYLISTS, "Playlists", Icons.Rounded.PlaylistPlay),
        TopLevel(Routes.DISCOVER, "Discover", Icons.Rounded.Explore),
        TopLevel(Routes.DOWNLOADS, "Downloads", Icons.Rounded.Download),
    )

    // Every destination maps to one editorial palette. The shell tints to
    // it — the Scaffold container (what shows behind the status bar), the
    // mini player, and the navigation bar all take the same colors, so the
    // chrome belongs to the page rather than framing it in a fixed dark.
    // Cross-fading the whole palette over ~the navigation transition's
    // duration makes the change read as part of the screen opening.
    val targetPalette = when {
        currentRoute == Routes.NOW_PLAYING -> lavenderPalette()
        currentRoute == Routes.LIBRARY ||
            currentRoute?.startsWith("artist/") == true ||
            currentRoute?.startsWith("album/") == true -> amberPalette()
        currentRoute == Routes.PLAYLISTS ||
            currentRoute == Routes.SPOTIFY_TRANSFER ||
            currentRoute?.startsWith("playlist/") == true ||
            currentRoute?.startsWith("smart_playlist/") == true -> greenPalette()
        currentRoute == Routes.EQUALIZER -> bluePalette()
        currentRoute == Routes.DISCOVER || currentRoute == Routes.ALBUM_DOWNLOAD -> coralPalette()
        currentRoute == Routes.HOME -> amberPalette()
        currentRoute == Routes.DOWNLOADS -> amberPalette()
        currentRoute == Routes.SETTINGS -> stonePalette()
        // Any destination added later without a palette still gets sane
        // chrome instead of an arbitrary section's colors.
        else -> EditorialPalette(
            field = MaterialTheme.colorScheme.background,
            ink = MaterialTheme.colorScheme.onBackground,
            muted = MaterialTheme.colorScheme.onSurfaceVariant,
            line = MaterialTheme.colorScheme.outlineVariant,
            accent = MaterialTheme.colorScheme.primary,
            onAccent = MaterialTheme.colorScheme.onPrimary,
        )
    }
    val palette = animatedEditorialPalette(targetPalette)

    // Discover's "Search my library" hands off to the Library screen's own
    // search. It travels as an incrementing counter rather than a route
    // argument so Library's route, saved state and back-stack behaviour stay
    // exactly as they were; LibraryScreen records the last value it acted on,
    // so simply returning to Library later doesn't re-open search.
    var librarySearchSignal by rememberSaveable { mutableIntStateOf(0) }
    var librarySearchText by rememberSaveable { mutableStateOf<String?>(null) }
    // A separate counter, not reused from librarySearchSignal: this one
    // means "you arrived at Library through an ordinary entry, not a
    // search request — close search if it was left open." It's bumped only
    // by plain entries into Library (the nav bar tab, onOpenLibrary), never
    // by the search-handoff paths above, and never by popping back from Now
    // Playing — which is exactly what lets search stay open across a trip
    // to Now Playing and back, while still closing on an ordinary return to
    // the tab.
    var librarySearchResetSignal by rememberSaveable { mutableIntStateOf(0) }

    fun goTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(verificationReturnSignal) {
        if (verificationReturnSignal > 0 && currentRoute != Routes.DOWNLOADS) {
            goTo(Routes.DOWNLOADS)
        }
    }

    LaunchedEffect(playlistVerificationReturnSignal) {
        if (playlistVerificationReturnSignal > 0 && currentRoute != Routes.SPOTIFY_TRANSFER) {
            navController.navigate(Routes.SPOTIFY_TRANSFER) { launchSingleTop = true }
        }
    }

    LaunchedEffect(spotifyAuthorizationReturnSignal) {
        if (spotifyAuthorizationReturnSignal > 0 && currentRoute != Routes.SPOTIFY_TRANSFER) {
            navController.navigate(Routes.SPOTIFY_TRANSFER) { launchSingleTop = true }
        }
    }

    LaunchedEffect(albumVerificationReturnSignal) {
        if (albumVerificationReturnSignal > 0 && currentRoute != Routes.ALBUM_DOWNLOAD) goTo(Routes.DOWNLOADS)
    }

    Scaffold(
        containerColor = palette.field,
        // Only the TOP side, deliberately: Scaffold's default is
        // WindowInsets.safeDrawing, which — with no bottomBar to claim it —
        // reserves the bottom system-bar inset for itself and marks it
        // consumed for everything inside the content slot. That's exactly
        // why the floating chrome below ended up sitting flush against the
        // physical bottom edge under the phone's own nav bar: its own
        // .windowInsetsPadding(WindowInsets.navigationBars) was reading an
        // inset Scaffold had already zeroed out. Restricting Scaffold to
        // only the top here leaves the bottom inset untouched, so that
        // Column's own windowInsetsPadding gets the real value.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                // Only the TOP inset from Scaffold is applied here. The
                // bottom inset is deliberately dropped: this is what makes
                // the nav bar's glass show real content through it instead
                // of an opaque rectangle. Previously the chrome below lived
                // in Scaffold's bottomBar slot, which made Scaffold reserve
                // that slot's full height as bottom padding — NavHost's
                // content never existed in that region at all, so there
                // was nothing for the glass to show no matter how
                // transparent its fill was.
                //
                // Each screen's own scrollable content is responsible for
                // leaving enough room at the bottom (LazyColumn/
                // LazyVerticalGrid contentPadding) that its last items can
                // still scroll clear of the floating chrome — see
                // LibraryScreen's SongsTab/AlbumsTab/ArtistsTab.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                // Motion defaults for every destination: a quick fade with a
                // small upward drift, matched by a subtle fade-out. Visual
            // identity is untouched — screens just stop teleporting.
            enterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)) +
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core.tween(250)
                    ) { it / 24 }
            },
            exitTransition = {
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
            },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250))
            },
            popExitTransition = {
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core.tween(220)
                    ) { it / 24 }
            },
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onAlbumClick = { navController.navigate("album/$it") },
                    onArtistClick = { navController.navigate("artist/${android.net.Uri.encode(it)}") },
                    openSearchSignal = librarySearchSignal,
                    requestedSearchQuery = librarySearchText,
                    resetSearchSignal = librarySearchResetSignal,
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    // The shell no longer reserves this space via Scaffold's
                    // bottomBar, so the floating chrome's height has to be
                    // handed down explicitly — HomeScreen already takes it.
                    contentPadding = PaddingValues(bottom = FloatingChromeClearance),
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSearch = {
                        librarySearchText = null
                        librarySearchSignal++
                        goTo(Routes.LIBRARY)
                    },
                    onOpenDownloads = { goTo(Routes.DOWNLOADS) },
                    onOpenDiscover = { goTo(Routes.DISCOVER) },
                    onOpenFlacCheck = { navController.navigate(Routes.FLAC_CHECK) },
                    onOpenLibrary = { librarySearchResetSignal++; goTo(Routes.LIBRARY) },
                )
            }
            composable(Routes.DISCOVER) {
                DiscoverScreen(
                    onDownloadAlbum = { navController.navigate("album_download/${android.net.Uri.encode(it)}") { launchSingleTop = true } },
                    onSearchLibrary = { albumTitle ->
                        librarySearchText = albumTitle
                        librarySearchSignal++
                        goTo(Routes.LIBRARY)
                    },
                )
            }
            composable(
                Routes.ALBUM,
                arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
            ) { AlbumDetailScreen() }
            composable(
                "artist/{artistName}",
                arguments = listOf(navArgument("artistName") { type = NavType.StringType }),
            ) {
                com.harmony.feature.library.ArtistDetailScreen(
                    onAlbumClick = { navController.navigate("album/$it") },
                )
            }
            composable(Routes.PLAYLISTS) {
                PlaylistsScreen(
                    onPlaylistClick = { navController.navigate("playlist/$it") },
                    onSmartPlaylistClick = { navController.navigate("smart_playlist/${it.name}") },
                    onSpotifyTransfer = {
                        navController.navigate(Routes.SPOTIFY_TRANSFER) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.SPOTIFY_TRANSFER) {
                SpotifyPlaylistTransferScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDownloads = {
                        navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true }
                    },
                    onOpenPlaylist = { playlistId ->
                        navController.navigate("playlist/$playlistId")
                    },
                    verificationReturnSignal = playlistVerificationReturnSignal,
                )
            }
            composable(
                Routes.PLAYLIST,
                arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
            ) { PlaylistDetailScreen() }
            composable(
                Routes.SMART_PLAYLIST,
                arguments = listOf(navArgument("smartType") { type = NavType.StringType }),
            ) { PlaylistDetailScreen() }
            composable(Routes.EQUALIZER) {
                EqualizerScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen(onOpenAlbum = { navController.navigate("album_download/${android.net.Uri.encode(it)}") { launchSingleTop = true } })
            }
            composable(Routes.ALBUM_DOWNLOAD, arguments = listOf(navArgument("discoverId") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("discoverId").orEmpty()
                val album = ShflAlbumCatalog.albums.firstOrNull { it.id == id }
                AlbumDownloadScreen(id, album?.title ?: "Album", album?.artist.orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                    onReview = libraryActions::openReview, artistAliases = album?.artistAliases.orEmpty())
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenFlacCheck = { navController.navigate(Routes.FLAC_CHECK) },
                    onOpenTagEditor = { navController.navigate(Routes.TAG_EDITOR) },
                    onOpenEqualizer = { navController.navigate(Routes.EQUALIZER) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.FLAC_CHECK) { FlacCheckScreen() }
            composable(Routes.TAG_EDITOR) { TagEditorScreen() }
            composable(
                Routes.NOW_PLAYING,
                // The player is spatially "below" everything (it expands up
                // from the mini player), so it slides up over the app and
                // slides back down on exit — the one screen with a stronger,
                // directional transition.
                enterTransition = {
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core.tween(320)
                    ) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(320))
                },
                exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) },
                popExitTransition = {
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core.tween(280)
                    ) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(280))
                },
            ) {
                NowPlayingScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEqualizer = { navController.navigate(Routes.EQUALIZER) },
                )
            }
        }

        // The floating bottom chrome, drawn ON TOP of NavHost's content
        // rather than reserved as Scaffold layout space. This is what lets
        // the nav bar's glass show real content moving underneath it: this
        // Column used to carry its own opaque `.background(palette.field)`
        // and sit in Scaffold's bottomBar slot, which made Scaffold push
        // NavHost's content up out of this entire region — there was
        // nothing behind the glass to see, no matter how transparent its
        // fill was.
        //
        // The nav bar and the mini player still block taps to whatever is
        // now visible underneath them: LiquidGlassNavBar's whole capsule
        // already carries a pointerInput for its swipe gesture, and
        // MiniPlayer's whole row is clickable — both consume touches over
        // their full footprint regardless of the fill's transparency. Only
        // the gap between them (the Spacer) has no touch handler of its
        // own, so a tap that lands exactly there reaches whatever is
        // visible underneath — which is correct, since that gap isn't part
        // of either control.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // Above the mini player so a running download stays visible on
            // every screen. Suppressed on download workflow pages, which
            // already render richer progress, and on Now Playing, which is
            // a full-screen surface with its own chrome.
            DownloadStatusBanner(
                palette = palette,
                onOpenDownloads = { goTo(Routes.DOWNLOADS) },
                suppressed = currentRoute == Routes.DOWNLOADS ||
                    currentRoute == Routes.ALBUM_DOWNLOAD ||
                    currentRoute == Routes.SPOTIFY_TRANSFER ||
                    currentRoute == Routes.NOW_PLAYING,
            )
            if (currentRoute != Routes.NOW_PLAYING) {
                MiniPlayer(
                    onExpand = { navController.navigate(Routes.NOW_PLAYING) },
                    palette = palette,
                )
                // A deliberate gap, not just the two components' own edge
                // insets touching: at 6dp+6dp those two floating pills read
                // as one stacked block. This makes them two independent
                // things sitting near each other, which is what they
                // actually are — nothing ties the mini player's visibility
                // to the nav bar's.
                Spacer(Modifier.height(10.dp))
            }
            val topLevelIndex = topLevel.indexOfFirst { it.route == currentRoute }
            if (topLevelIndex >= 0) {
                LiquidGlassNavBar(
                    items = topLevel.map { NavItem(it.label, it.icon) },
                    selectedIndex = topLevelIndex,
                    onSelect = { index ->
                        val route = topLevel[index].route
                        // Only an ordinary tab tap resets search — this
                        // does NOT run when popping back from Now Playing,
                        // since that uses navController.popBackStack()
                        // directly and never goes through this handler.
                        if (route == Routes.LIBRARY) librarySearchResetSignal++
                        goTo(route)
                    },
                    palette = palette,
                )
            }
        }
    }
    }
    LibraryActionsHost(libraryActions)
}
