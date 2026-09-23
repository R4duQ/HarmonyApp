package com.harmony.feature.discover.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.component.coralPalette
import com.harmony.domain.library.discovery.DraftItem
import com.harmony.domain.library.discovery.DraftStep
import com.harmony.domain.library.discovery.ExplorationLevel
import com.harmony.domain.library.repository.DiscoveryBatch
import com.harmony.domain.library.repository.DiscoveryFilter

/**
 * Discover: preferences → songs → playlist.
 *
 * [onOpenPlaylist] opens the playlist the flow just saved; [downloadPanel]
 * is the Downloads module's panel for a saved selection (this module does
 * not depend on the download engines).
 */
@Composable
fun DiscoverScreen(
    onOpenPlaylists: () -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = { onOpenPlaylists() },
    downloadPanel: @Composable (DiscoveryBatch) -> Unit = {},
    viewModel: DiscoveryFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.preview.state.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpenPlaylist)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> when (event) { is DiscoveryEvent.OpenPlaylist -> open(event.id) } }
    }
    // Leaving the screen (or the app) silences the preview.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.preview.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.preview.stop() }
    }
    val actions = remember(viewModel) { DiscoveryActions.from(viewModel) }
    DiscoveryFlowContent(state, preview, actions, downloadPanel = downloadPanel)
}

/** Everything callable from the flow; tests pass lambdas instead of a ViewModel. */
internal class DiscoveryActions(
    val setLevel: (ExplorationLevel) -> Unit = {},
    val setSize: (Int) -> Unit = {},
    val toggleArtist: (String) -> Unit = {},
    val toggleGenre: (String) -> Unit = {},
    val searchArtists: (String) -> Unit = {},
    val setFilter: (DiscoveryFilter) -> Unit = {},
    val generate: () -> Unit = {},
    val resume: () -> Unit = {},
    val changeLevel: (ExplorationLevel) -> Unit = {},
    val regenerate: () -> Unit = {},
    val fill: () -> Unit = {},
    val toggleKeep: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
    val replace: (String) -> Unit = {},
    val notInterested: (String) -> Unit = {},
    val moreLike: (String) -> Unit = {},
    val focus: (String?) -> Unit = {},
    val togglePreview: (DraftItem) -> Unit = {},
    val backToPreferences: () -> Unit = {},
    val goToReview: () -> Unit = {},
    val backFromReview: () -> Unit = {},
    val createPlaylist: () -> Unit = {},
    val rename: (String) -> Unit = {},
    val openBatch: (String) -> Unit = {},
    val startNew: () -> Unit = {},
    val consumeMessage: () -> Unit = {},
) {
    companion object {
        fun from(vm: DiscoveryFlowViewModel) = DiscoveryActions(
            setLevel = vm::setLevel, setSize = vm::setSize, toggleArtist = vm::togglePickedArtist, toggleGenre = vm::togglePickedGenre,
            searchArtists = vm::searchArtists, setFilter = vm::setFilter, generate = vm::generate, resume = vm::resume,
            changeLevel = vm::changeLevel, regenerate = vm::regenerate, fill = vm::fill, toggleKeep = vm::toggleKeep, remove = vm::remove,
            replace = vm::replace, notInterested = vm::notInterested, moreLike = vm::moreLikeThis, focus = vm::focus,
            togglePreview = vm::togglePreview, backToPreferences = vm::backToPreferences, goToReview = vm::goToReview,
            backFromReview = vm::backFromReview, createPlaylist = vm::createPlaylist, rename = vm::rename, openBatch = vm::openBatch,
            startNew = vm::startNewSelection, consumeMessage = vm::consumeMessage,
        )
    }
}

@Composable
internal fun DiscoveryFlowContent(
    state: DiscoveryUiState,
    preview: PreviewState,
    actions: DiscoveryActions,
    palette: EditorialPalette = coralPalette(),
    bottomChrome: Dp = LocalFloatingChromeHeight.current,
    downloadPanel: @Composable (DiscoveryBatch) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) { snackbar.showSnackbar(message); actions.consumeMessage() }
    }
    Box(Modifier.fillMaxSize().background(palette.field)) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "discover-step",
        ) { step ->
            when (step) {
                DraftStep.PREFERENCES -> PreferencesStep(state, palette, actions, bottomChrome)
                DraftStep.SONGS -> SongsStep(state, preview, palette, actions, bottomChrome)
                DraftStep.REVIEW -> ReviewStep(state, palette, actions, bottomChrome, downloadPanel)
            }
        }
        // Sticky action above the mini player and nav bar, on a fade so list text doesn't run under it.
        if (state.step != DraftStep.REVIEW) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to palette.field.copy(alpha = 0f), 0.12f to palette.field, 1f to palette.field))
                .padding(start = Gutter, end = Gutter, top = 32.dp, bottom = bottomChrome + 12.dp),
        ) {
            when (state.step) {
                DraftStep.PREFERENCES -> PreferencesAction(state, palette, actions, Modifier.fillMaxWidth())
                else -> SongsAction(state, palette, actions, Modifier.fillMaxWidth())
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomChrome + 84.dp))
    }
}

internal object DiscoveryTags {
    const val PREFERENCES = "discover:preferences"
    const val SONGS = "discover:songs"
    const val REVIEW_STEP = "discover:review-step"
    const val OFFLINE = "discover:offline"
    const val RESUME = "discover:resume"
    const val TASTE = "discover:taste"
    const val COLD_START = "discover:cold-start"
    const val ADD_PICKS = "discover:add-picks"
    const val ARTIST_SEARCH = "discover:artist-search"
    const val SIZE = "discover:size"
    const val SOURCES = "discover:sources"
    const val GENERATE = "discover:generate"
    const val WORK = "discover:work"
    const val COUNT = "discover:count"
    const val NOTES = "discover:notes"
    const val SAVED = "discover:saved"
    const val PAGER = "discover:pager"
    const val PREVIEW = "discover:preview"
    const val PREVIEW_NOTE = "discover:preview-note"
    const val KEEP = "discover:keep"
    const val MORE_LIKE = "discover:more-like"
    const val REPLACE = "discover:replace"
    const val NOT_INTERESTED = "discover:not-interested"
    const val REMOVE = "discover:remove"
    const val FILL = "discover:fill"
    const val REGENERATE = "discover:regenerate"
    const val REVIEW = "discover:review"
    const val BACK = "discover:back"
    const val SUMMARY = "discover:summary"
    const val NAME = "discover:name"
    const val DURATION = "discover:duration"
    const val AVAILABILITY = "discover:availability"
    const val DOWNLOADS = "discover:downloads"
    const val CREATE = "discover:create"
    const val CREATE_REASON = "discover:create-reason"
    const val OPEN_PLAYLIST = "discover:open-playlist"
    const val NEW_SELECTION = "discover:new-selection"
    fun level(level: ExplorationLevel) = "discover:level:${level.name}"
    fun focus(key: String) = "discover:focus:$key"
    fun row(key: String) = "discover:row:$key"
    fun reviewRow(key: String) = "discover:review-row:$key"
    fun batch(id: String) = "discover:batch:$id"
}
