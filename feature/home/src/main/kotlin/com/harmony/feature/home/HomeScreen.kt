package com.harmony.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.MoodFilter
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.ui.theme.Room

/**
 * The Listen destination (route "home"), wired to [HomeViewModel].
 *
 * The mini player, the navigation bar and the top-bar utilities (Activity,
 * Sound, Settings) belong to the app shell: their clearance arrives as
 * [contentPadding] and the utilities as [topActions].
 *
 * A mood is applied before the mix starts ([onApplyMood], then
 * [HomeViewModel.startSmartMix]), so the first song already fits the mood.
 */
@Composable
fun HomeScreen(
    onOpenNowPlaying: () -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenSmart: (SmartPlaylistType) -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onOpenAdd: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onApplyMood: (MoodFilter) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    topActions: @Composable RowScope.() -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mixStarting by viewModel.mixStarting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Owned by this back-stack entry: returning from an album or another tab
    // lands on the same scroll position.
    val listState = rememberLazyListState()

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    val nav = rememberUpdatedState(
        ListenActions(
            onOpenNowPlaying = onOpenNowPlaying,
            onOpenAlbum = onOpenAlbum,
            onOpenSmart = onOpenSmart,
            onOpenPlaylist = onOpenPlaylist,
            onOpenDiscover = onOpenDiscover,
            onOpenAdd = onOpenAdd,
            onOpenLibrary = onOpenLibrary,
            onMood = onApplyMood,
        ),
    )
    val actions = remember(viewModel) {
        ListenActions(
            onTogglePlayback = viewModel::togglePlayback,
            onResume = viewModel::playRecent,
            onOpenNowPlaying = { nav.value.onOpenNowPlaying() },
            onStartMix = viewModel::startSmartMix,
            onMood = { mood ->
                nav.value.onMood(mood)
                viewModel.startSmartMix()
            },
            onOpenAlbum = { nav.value.onOpenAlbum(it) },
            onPlayRecent = viewModel::playRecent,
            onOpenSmart = { nav.value.onOpenSmart(it) },
            onOpenPlaylist = { nav.value.onOpenPlaylist(it) },
            onOpenDiscover = { nav.value.onOpenDiscover() },
            onOpenAdd = { nav.value.onOpenAdd() },
            onOpenLibrary = { nav.value.onOpenLibrary() },
        )
    }

    Box(Modifier.fillMaxSize()) {
        ListenContent(
            state = state,
            actions = actions,
            mixStarting = mixStarting,
            contentPadding = contentPadding,
            listState = listState,
            topActions = topActions,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(12.dp),
                containerColor = Room.record,
                contentColor = Room.onRecord,
                actionColor = Room.recordLive,
            )
        }
    }
}
