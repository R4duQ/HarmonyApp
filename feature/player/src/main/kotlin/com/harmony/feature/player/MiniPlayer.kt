package com.harmony.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.OutputForms
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.core.ui.component.EditorialPalette

/** How many of the next songs the mini player lists. */
private const val UP_NEXT_COUNT = 8

/**
 * Persistent player above the navigation: [MiniPlayerCard] fed from the
 * player. It rises into place the first time a song loads and sinks away if
 * the queue is ever cleared.
 *
 * The last song is kept while it sinks, so the exit animation has something
 * to show; and because only "has there ever been a song" decides visibility,
 * the frame or two without a current song during a skip doesn't make the
 * whole card drop out and come back.
 *
 * [palette] is accepted so the call site stays the same; the card has its
 * own colours, set against the page's light or dark background.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    @Suppress("UNUSED_PARAMETER") palette: EditorialPalette,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val forms by viewModel.outputForms.collectAsStateWithLifecycle()

    var lastSong by remember { mutableStateOf<Song?>(null) }
    state.currentSong?.let { lastSong = it }
    val song = lastSong

    AnimatedVisibility(
        visible = song != null,
        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) { it } +
            fadeIn(tween(300)) + scaleIn(initialScale = 0.92f),
        exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(200)) + scaleOut(targetScale = 0.92f),
    ) {
        if (song == null) return@AnimatedVisibility
        val output = state.audioOutput
        val ui = MiniPlayerUi(
            song = song,
            isPlaying = state.isPlaying,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            shuffleOn = state.shuffleMode != ShuffleMode.OFF,
            repeatMode = state.repeatMode,
            deviceLabel = output.label,
            deviceIcon = OutputIcons.forOutput(output.type, OutputForms.effective(output, forms)),
            upNext = upNext(state),
        )
        val actions = remember(viewModel, onExpand) {
            MiniPlayerActions(
                onExpand = onExpand,
                onPlayPause = viewModel::onPlayPause,
                onNext = viewModel::onNext,
                onPrevious = viewModel::onPrevious,
                onShuffle = viewModel::cycleShuffleMode,
                onRepeat = viewModel::cycleRepeatMode,
                onSeek = viewModel::onSeek,
                onUpNext = viewModel::onQueueItemClick,
            )
        }
        MiniPlayerCard(ui, actions)
    }
}

/** The songs after the current one, with their queue positions; wraps round when the whole queue repeats. */
internal fun upNext(state: PlayerState, count: Int = UP_NEXT_COUNT): List<Pair<Int, Song>> {
    val queue = state.queue
    val current = state.queueIndex
    if (current < 0 || queue.isEmpty()) return emptyList()
    val after = (current + 1 until queue.size).map { it to queue[it] }
    val wrapped = if (state.repeatMode == RepeatMode.ALL) (0 until current).map { it to queue[it] } else emptyList()
    return (after + wrapped).take(count)
}
