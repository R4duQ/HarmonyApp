package com.harmony.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.Song
import com.harmony.core.ui.component.amberPalette
import java.util.Calendar

/**
 * The Home destination.
 *
 * The mini player and bottom navigation are NOT drawn here. They belong to
 * the app shell, which floats them over every top-level destination; the
 * shell's measured clearance arrives as [contentPadding] and the page adds
 * its own breathing room on top, so the last card always scrolls clear.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onOpenFlacCheck: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onSongClick: (Song) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mixStarting by viewModel.mixStarting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val progress = viewModel.progress.collectAsStateWithLifecycle()
    val online = rememberHomeOnline()
    val palette = amberPalette()
    val snackbarHostState = remember { SnackbarHostState() }
    // Saveable, and owned by this back-stack entry: returning from an
    // album, a playlist or another tab lands on the same scroll position.
    val listState = rememberLazyListState()

    // Picked when Home is composed; it does not need to tick over at
    // midnight, and a timer would cost more than it is worth.
    val greeting = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    val callbacks = rememberUpdatedState(
        HomeCallbacks(
            onOpenSettings, onOpenSearch, onOpenDownloads, onOpenDiscover, onOpenFlacCheck,
            onOpenLibrary, onOpenNowPlaying, onOpenAlbum, onOpenPlaylist, onOpenPlaylists, onSongClick,
        ),
    )
    val actions = remember(viewModel) {
        HomeActions(
            onOpenSearch = { callbacks.value.openSearch() },
            onOpenSettings = { callbacks.value.openSettings() },
            onOpenNowPlaying = { callbacks.value.openNowPlaying() },
            onTogglePlayback = viewModel::togglePlayback,
            onStartMix = viewModel::startSmartMix,
            onPlayRecent = { song ->
                viewModel.playRecent(song)
                callbacks.value.songClick(song)
            },
            onOpenPlaylist = { callbacks.value.openPlaylist(it) },
            onPlayPlaylist = viewModel::playPlaylist,
            onOpenPlaylists = { callbacks.value.openPlaylists() },
            onOpenAlbum = { callbacks.value.openAlbum(it) },
            onPlayAlbum = viewModel::playAlbum,
            onOpenLibrary = { callbacks.value.openLibrary() },
            onOpenDiscover = { callbacks.value.openDiscover() },
            onOpenDownloads = { callbacks.value.openDownloads() },
            onOpenFlacCheck = { callbacks.value.openFlacCheck() },
        )
    }

    Box(Modifier.fillMaxSize()) {
        HomeContent(
            state = state,
            palette = palette,
            actions = actions,
            greeting = greeting,
            progress = { progress.value },
            mixStarting = mixStarting,
            online = online,
            contentPadding = contentPadding,
            listState = listState,
        )

        val direction = LocalLayoutDirection.current
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = contentPadding.calculateStartPadding(direction),
                    end = contentPadding.calculateEndPadding(direction),
                    bottom = contentPadding.calculateBottomPadding(),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(14.dp),
                containerColor = palette.ink,
                contentColor = palette.field,
            )
        }
    }
}

/** The navigation callbacks, held in one updated state so [HomeActions] can be built once. */
private class HomeCallbacks(
    val openSettings: () -> Unit,
    val openSearch: () -> Unit,
    val openDownloads: () -> Unit,
    val openDiscover: () -> Unit,
    val openFlacCheck: () -> Unit,
    val openLibrary: () -> Unit,
    val openNowPlaying: () -> Unit,
    val openAlbum: (Long) -> Unit,
    val openPlaylist: (Long) -> Unit,
    val openPlaylists: () -> Unit,
    val songClick: (Song) -> Unit,
)
