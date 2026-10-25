package com.harmony.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.OutputForms
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.core.ui.component.EditorialPalette
import kotlinx.coroutines.delay

/** How many of the next songs the mini player lists. */
private const val UP_NEXT_COUNT = 8

/**
 * Persistent player above the navigation: [MiniPlayerCard] fed from the
 * player. It rises into place the first time a song loads and sinks away if
 * the queue is ever cleared.
 *
 * [shown] is false while Now Playing is open. The card then lifts and fades
 * into the player as it opens, and when the player closes the card drops
 * back into place from above, with a little bounce, its record spinning in.
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
    shown: Boolean = true,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val forms by viewModel.outputForms.collectAsStateWithLifecycle()
    val brain by viewModel.brainTelemetry.collectAsStateWithLifecycle()
    val liveEq by viewModel.liveSpectralEq.collectAsStateWithLifecycle()

    var lastSong by remember { mutableStateOf<Song?>(null) }
    state.currentSong?.let { lastSong = it }
    val song = lastSong

    // Whether the card is (or last was) hidden because Now Playing opened, rather than because there was no song.
    var hiddenByPlayer by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (!shown) {
            hiddenByPlayer = true
        } else {
            delay(900) // long enough for the return to play out
            hiddenByPlayer = false
        }
    }
    val intoPlayer = !shown || hiddenByPlayer

    AnimatedVisibility(
        visible = song != null && shown,
        enter = if (intoPlayer) {
            // Back from Now Playing: dropping in from above, following the player down.
            slideInVertically(spring(dampingRatio = 0.6f, stiffness = 260f)) { -it / 2 } +
                fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 260f), initialScale = 1.08f)
        } else {
            slideInVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) { it } +
                fadeIn(tween(300)) + scaleIn(initialScale = 0.92f)
        },
        exit = if (intoPlayer) {
            // Into Now Playing: rising and swelling as the player grows up out of it.
            slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { -it / 2 } +
                fadeOut(tween(200, delayMillis = 40)) +
                scaleOut(tween(260), targetScale = 1.1f, transformOrigin = TransformOrigin(0.5f, 1f))
        } else {
            slideOutVertically(tween(250)) { it } + fadeOut(tween(200)) + scaleOut(targetScale = 0.92f)
        },
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
            telemetry = miniTelemetryLine(
                brain.nowPlaying?.takeIf { it.trace.trackId == song.id },
                liveSpectralEq = liveEq,
            ),
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

/**
 * The micro-subtitle under the title: what the engines are doing for this
 * song, from live data only. "LIVE SPECTRAL EQ" while the equalizer's AUTO
 * LIVE calibrates to the music; why Smart Shuffle picked the song when it
 * did ("VIBE MATCH 94%"). Null when neither applies.
 */
internal fun miniTelemetryLine(decision: com.harmony.core.model.QueuedDecision?, liveSpectralEq: Boolean): String? {
    val parts = buildList {
        if (liveSpectralEq) add("LIVE SPECTRAL EQ")
        decision?.let {
            add(
                when (it.trace.reason) {
                    com.harmony.core.model.ReasonTag.VIBE_MATCH -> "VIBE MATCH ${it.vibeMatchPct}%"
                    com.harmony.core.model.ReasonTag.MARKOV_FAVORITE -> "YOUR USUAL NEXT"
                    com.harmony.core.model.ReasonTag.BANDIT_GEM -> "REDISCOVERY"
                    com.harmony.core.model.ReasonTag.POST_SKIP_PIVOT -> "POST-SKIP PIVOT"
                    com.harmony.core.model.ReasonTag.PROVEN_FAVORITE -> "PROVEN FAVORITE"
                    com.harmony.core.model.ReasonTag.SONIC_ADVENTURE -> "SONIC ADVENTURE"
                    com.harmony.core.model.ReasonTag.COLD_START -> "WILDCARD"
                },
            )
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
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
