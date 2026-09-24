package com.harmony.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.MiniPlayerInk
import com.harmony.core.ui.component.MiniPlayerRim
import com.harmony.core.ui.component.MiniPlayerMuted
import com.harmony.core.ui.component.miniPlayerField
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import kotlin.math.abs
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.harmony.core.model.RepeatMode
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged

private const val MINI_SWIPE_UP_THRESHOLD_PX = 90f

/**
 * How long the peek may stand in for the row before giving up.
 *
 * Generous enough to cover a slow track load, short enough that a skip
 * which never lands doesn't read as a freeze.
 */
private const val COMMIT_TIMEOUT_MS = 700L

/**
 * Persistent player bar above the navigation.
 *
 * It owns a fixed yellow surface rather than taking the current section's
 * palette. The player is the one piece of chrome that persists across every
 * screen, so a constant colour makes it read as a single object following
 * you around, instead of a strip that recolours itself whenever you change
 * tabs. That's the opposite of the earlier decision, which tinted it per
 * section — worth revisiting if the yellow ever stops working against a new
 * section colour.
 *
 * [palette] is still accepted so the call site is unchanged and so a future
 * revision can tint accents from it, but nothing reads it at present.
 *
 * Motion is unchanged from the previous revision:
 *  - the bar slides up into place the first time a song loads (and slides
 *    away if the queue is ever cleared) instead of popping;
 *  - track info cross-fades on song change, sliding gently upward;
 *  - the play/pause icon morphs instead of snapping;
 *  - the progress strip interpolates between position ticks.
 *
 * Implementation note: the last non-null song is retained so the exit
 * animation has content to show while sliding away — otherwise the early
 * return would blank the bar one frame before the animation could run.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    @Suppress("UNUSED_PARAMETER") palette: EditorialPalette,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()

    var lastSong by remember { mutableStateOf<Song?>(null) }
    state.currentSong?.let { lastSong = it }
    val song = lastSong
    val context = LocalContext.current
    val audioInfo by produceState<MiniPlayerAudioInfo?>(
        initialValue = song?.let { MiniPlayerAudioInfo.initial(it) },
        key1 = song?.id,
        key2 = song?.uri,
    ) {
        // Do not leave the previous track's format visible while the new URI
        // is inspected in the background.
        value = song?.let { MiniPlayerAudioInfo.initial(it) }
        value = song?.let { resolveMiniPlayerAudioInfo(context, it) }
    }

    val targetProgress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 500),
        label = "mini-progress",
    )

    AnimatedVisibility(
        // `song` already falls back to the last known track, so this only
        // needs to ask whether there has EVER been one. It previously also
        // required state.currentSong != null, which goes null for a frame or
        // two during a skip — collapsing the whole bar out and back in, and
        // taking every swipe value remembered inside this subtree with it.
        // That is the disappear-then-reappear, and it also reset the gesture
        // mid-flight.
        visible = song != null,
        enter = slideInVertically { it } + expandVertically() + fadeIn(),
        exit = slideOutVertically { it } + shrinkVertically() + fadeOut(),
    ) {
        if (song == null) return@AnimatedVisibility

        val swipeScope = rememberCoroutineScope()
        val dragX = remember { Animatable(0f) }
        // Which way the last skip went, so the incoming track's text travels
        // the same direction the finger did. A track change from the
        // notification or a track simply ending leaves this at its previous
        // value, which is harmless — nothing is mid-swipe to contradict.
        var skipForward by remember { mutableStateOf(true) }
        val haptics = LocalHapticFeedback.current
        // Latched so the tick fires once per crossing, not on every drag
        // event while the finger sits past the line.
        var pastThreshold by remember { mutableStateOf(false) }
        // True from the moment a skip is committed until the player reports
        // the new track. It is what lets the peek stand in for the row
        // across that gap instead of the row snapping back showing the OLD
        // song for a frame or two before the state catches up.
        var committing by remember { mutableStateOf(false) }
        // The peek is derived from the live queue, so the instant the player
        // advances it would recompute to the track AFTER the new one — and
        // it is still on screen at that point, standing in for the row. This
        // pins what it was showing when the swipe committed.
        var heldPeek by remember { mutableStateOf<Song?>(null) }
        // The pill's own width, used as the travel distance for both rows.
        // This is the fix for the double image: the outgoing row was damped
        // to 45% of the finger (about 54px by the commit point) while the
        // incoming one crossed the entire bar, so for most of the gesture
        // the two sat on top of each other — two titles, two artworks, two
        // sets of transport controls, all half-transparent.
        //
        // Separating them by exactly one bar width makes them a filmstrip:
        // whatever is on screen is one row or the other, never a blend.
        var barWidth by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(song.id) {
            if (committing) {
                // The row now holds the new track, so put it exactly where
                // the peek has been sitting. A snap, not an animation —
                // there is nothing to travel, the two are in the same place.
                dragX.snapTo(0f)
                committing = false
                heldPeek = null
            }
        }

        // How far along the gesture is, 0..1 against the commit point. Drives
        // both the peek's opacity and the fade on the current track, so the
        // two cross over each other as the finger travels.
        // Which track the gesture is heading toward, and the shared
        // geometry both rows use. Declared before commitDistance/
        // swipeProgress below, since those are computed FROM travel.
        val skipForwardPeek = dragX.value < 0f
        // Fallback only matters for the first frame before measurement.
        val travel = if (barWidth > 0f) barWidth else 900f
        // -1 when moving toward the next track (content travels left).
        val dirSign = if (skipForwardPeek) -1f else 1f

        // Commit distance is a FRACTION OF THE BAR, not a fixed pixel count.
        // With a fixed 120px threshold the rows had to cross a whole bar
        // width in that distance — roughly eight times finger speed, which
        // is what made the animation feel yanked rather than dragged.
        val commitDistance = travel * 0.28f
        val swipeProgress = (abs(dragX.value) / commitDistance).coerceIn(0f, 1f)

        // Whether a skip in each direction would actually go anywhere.
        // REPEAT_ALL wraps, so both stay available there even at the ends.
        val wraps = state.repeatMode == RepeatMode.ALL && state.queue.size > 1
        val hasNext = wraps || (state.queueIndex >= 0 && state.queueIndex < state.queue.lastIndex)
        val hasPrev = wraps || state.queueIndex > 0
        val peekSong = remember(state.queue, state.queueIndex, skipForwardPeek) {
            val i = state.queueIndex
            when {
                i < 0 -> null
                skipForwardPeek -> state.queue.getOrNull(i + 1)
                else -> state.queue.getOrNull(i - 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(miniPlayerField())
                .border(1.dp, MiniPlayerRim, RoundedCornerShape(26.dp))
                .clickable(onClick = onExpand),
        ) {
          Box(
              Modifier
                  .fillMaxWidth()
                  .onSizeChanged { barWidth = it.width.toFloat() },
          ) {
            // The incoming track, laid out EXACTLY like the real row —
            // same artwork size, same corner radius, same spacing — and
            // travelling into the very position the current row is leaving.
            // That is what makes the gesture read as "the song has already
            // been replaced" rather than as a caption appearing at the edge.
            //
            // Driven by PROGRESS, not by the raw drag. Offsetting it by the
            // drag plus a fixed entry distance looked right in code but the
            // arithmetic didn't work: the row is damped to 45%, so at the
            // commit point it has moved only ~54px while the peek still sat
            // ~106px off centre. It reached its place at roughly 356px of
            // finger travel — three times past the point where the track
            // already changed, so the "already replaced" moment never
            // actually happened on screen.
            //
            // Tying it to progress makes it land dead centre exactly when
            // the gesture commits, whatever the damping or threshold are
            // later tuned to.
            val shownPeek = if (committing) heldPeek else peekSong
            if ((swipeProgress > 0.01f || committing) && shownPeek != null) {
                // Once committing, it stops tracking the gesture and simply
                // holds centre at full strength — it IS the player now, for
                // the moment it takes the new state to arrive.
                // Exactly one bar-width away from the outgoing row at all
                // times, so the two can never occupy the same space.
                // Sits one full bar from the outgoing row and simply
                // travels with it — no separate curve to get out of step
                // with the finger.
                val peekShift =
                    if (committing) 0f else dragX.value - dirSign * travel
                // Fully opaque throughout: the separation does the work, and
                // fading them was what turned the overlap into a smear.
                val peekAlpha = 1f
                Row(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            translationX = peekShift
                            alpha = peekAlpha
                        }
                        .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(
                        shownPeek.artworkUri,
                        null,
                        Modifier.size(44.dp),
                        cornerRadius = 14.dp,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            shownPeek.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiniPlayerInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            shownPeek.artist,
                            fontSize = 11.sp,
                            color = MiniPlayerMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // A direction marker where the transport controls sit on
                    // the real row, so the incoming layout still balances
                    // without pretending its buttons are live.
                    Icon(
                        if (skipForwardPeek) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious,
                        contentDescription = null,
                        tint = MiniPlayerMuted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The row follows the finger while dragging and is
                        // carried off in the direction of travel when the
                        // skip commits. Without it the gesture had no visual
                        // answer at all — the track just changed, with no
                        // sense that the swipe caused it.
                        //
                        // It also fades as it travels, crossing over with the
                        // peek behind it: at the commit point the outgoing
                        // track is faint and the incoming one is legible, so
                        // the swipe reads as an exchange rather than as one
                        // label sliding over another.
                        .graphicsLayer {
                            // The finger's own displacement. The peek is
                            // pinned exactly one bar away from it, so the
                            // pair stays a strip without either needing a
                            // curve of its own.
                            translationX = dragX.value
                            alpha = if (committing) 0f else 1f
                        }
                        // Swipe UP on the bar = expand to the track page,
                        // mirroring the track page's swipe-down-to-collapse.
                        .pointerInput(Unit) {
                            var totalDrag = 0f
                            detectVerticalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onVerticalDrag = { _, amount -> totalDrag += amount },
                                onDragEnd = {
                                    if (totalDrag < -MINI_SWIPE_UP_THRESHOLD_PX) onExpand()
                                },
                            )
                        }
                        // Swipe LEFT/RIGHT = skip, same as on the artwork in
                        // the full player, so the gesture means the same
                        // thing in both places.
                        .pointerInput(Unit) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f; pastThreshold = false },
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    totalDrag += amount
                                    // Refuse to move toward a side with no
                                    // track on it. Letting it drag and spring
                                    // back suggested a skip was available and
                                    // then silently refused it; not moving at
                                    // all says there is nothing there, which
                                    // is the truth.
                                    if (totalDrag < 0f && !hasNext) totalDrag = 0f
                                    if (totalDrag > 0f && !hasPrev) totalDrag = 0f
                                    // 1:1 with the finger. Damping made the
                                    // content move at a different rate from
                                    // the hand moving it, which is the main
                                    // reason the gesture felt driven rather
                                    // than dragged.
                                    swipeScope.launch { dragX.snapTo(totalDrag) }
                                    val past = abs(totalDrag) >= commitDistance
                                    if (past != pastThreshold) {
                                        pastThreshold = past
                                        // Only on the way in. A tick when
                                        // backing off reads as a second
                                        // commit rather than an undo.
                                        if (past) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                },
                                onDragCancel = {
                                    swipeScope.launch {
                                        dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                    }
                                },
                                onDragEnd = {
                                    val forward = totalDrag <= -commitDistance
                                    val back = totalDrag >= commitDistance
                                    swipeScope.launch {
                                        if (!forward && !back) {
                                            // Short of the threshold: spring
                                            // home, which also tells you the
                                            // swipe was seen but not enough.
                                            dragX.animateTo(
                                                0f,
                                                spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                            )
                                            return@launch
                                        }
                                        skipForward = forward
                                        // Hold the peek in place from here
                                        // until the player actually reports
                                        // the new track. Without this the
                                        // row had to make a SECOND journey —
                                        // jump to the far side and spring
                                        // back — after the peek had already
                                        // arrived at centre, so one swipe
                                        // produced two conflicting movements
                                        // and a visible jump between them.
                                        // Only stage the handover when
                                        // there is genuinely a track on that
                                        // side. At the end of the queue
                                        // onNext() does nothing, so nothing
                                        // would ever arrive to end the
                                        // handover.
                                        val staged = peekSong != null
                                        heldPeek = peekSong
                                        committing = staged
                                        // A full bar width, matching the
                                        // filmstrip: the row has to leave the
                                        // frame entirely as the peek lands,
                                        // or the tail of it is still visible
                                        // beside the incoming one.
                                        val span = if (barWidth > 0f) barWidth else 900f
                                        val exit = if (forward) -span else span
                                        // Carries the outgoing row the rest
                                        // of the way out as the peek settles.
                                        // Accelerating out, rather than
                                        // linear, so it reads as leaving
                                        // under its own momentum.
                                        dragX.animateTo(
                                            exit,
                                            tween(150, easing = FastOutLinearInEasing),
                                        )
                                        if (forward) viewModel.onNext() else viewModel.onPrevious()

                                        // Safety net. The handover is ended
                                        // by the song id changing, but a skip
                                        // does not always change it —
                                        // onPrevious() restarts the current
                                        // track once you are a few seconds
                                        // in, and onNext() is a no-op at the
                                        // end of the queue. Those cases left
                                        // committing stuck true, the row
                                        // invisible and the peek frozen on
                                        // screen: the bar appeared to hang
                                        // showing one song.
                                        //
                                        // If nothing has arrived by the time
                                        // this elapses, take the row back
                                        // gracefully instead of waiting for
                                        // an event that is not coming.
                                        if (staged) {
                                            delay(COMMIT_TIMEOUT_MS)
                                            if (committing) {
                                                committing = false
                                                heldPeek = null
                                                dragX.animateTo(
                                                    0f,
                                                    spring(stiffness = Spring.StiffnessMediumLow),
                                                )
                                            }
                                        } else {
                                            dragX.animateTo(
                                                0f,
                                                spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        targetState = song.id,
                        transitionSpec = {
                            // No transition when the change came from a
                            // swipe. The peek has ALREADY performed the
                            // visual handover, so cross-fading here replays
                            // it a second time — and because AnimatedContent
                            // keeps the outgoing content for the length of
                            // that fade, the previous track sat visible at
                            // centre for a beat before the new one resolved.
                            // That is the "song I already heard flashes up"
                            // moment. committing is still true at the instant
                            // song.id changes, which is exactly when this
                            // spec is evaluated.
                            if (committing) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                (fadeIn(tween(300)) + scaleIn(initialScale = 0.9f))
                                    .togetherWith(fadeOut(tween(200)))
                            }
                        },
                        label = "mini-artwork",
                    ) { _ ->
                        Artwork(
                            song.artworkUri,
                            null,
                            Modifier.size(44.dp),
                            cornerRadius = 14.dp,
                        )
                    }
                    AnimatedContent(
                        targetState = song.id,
                        transitionSpec = {
                            // Same reasoning as the artwork above: silent
                            // when a swipe drove the change, since the peek
                            // already did it. The slide stays for changes
                            // that arrive some other way — the track ending,
                            // the notification, a tap in the queue.
                            if (committing) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                val dir = if (skipForward) 1 else -1
                                (slideInHorizontally(tween(280)) { dir * it / 3 } + fadeIn(tween(280)))
                                    .togetherWith(
                                        slideOutHorizontally(tween(180)) { -dir * it / 3 } + fadeOut(tween(180)),
                                    )
                            }
                        },
                        label = "mini-title",
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    ) { _ ->
                        Column {
                            Text(
                                song.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiniPlayerInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                song.artist,
                                fontSize = 11.sp,
                                color = MiniPlayerMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Previous / play-pause / next, with only the middle
                    // control carrying a filled disc. Three equally-weighted
                    // buttons would give the bar no focal point; one solid
                    // disc between two bare glyphs makes the primary action
                    // obvious without labelling it.
                    IconButton(
                        onClick = viewModel::onPrevious,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MiniPlayerInk,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(
                                role = Role.Button,
                                onClick = viewModel::onPlayPause,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = state.isPlaying,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.6f, animationSpec = tween(160)) + fadeIn(tween(160)))
                                    .togetherWith(scaleOut(targetScale = 0.6f, animationSpec = tween(120)) + fadeOut(tween(120)))
                            },
                            label = "mini-play-pause",
                        ) { playing ->
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = MiniPlayerInk,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = viewModel::onNext,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = MiniPlayerInk,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
          }

                AnimatedContent(
                    targetState = audioInfo,
                    transitionSpec = {
                        fadeIn(tween(220)).togetherWith(fadeOut(tween(140)))
                    },
                    label = "mini-audio-quality",
                ) { info ->
                    if (info != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (info.isLossless) {
                                Row(
                                    modifier = Modifier
                                        .background(
                                            MiniPlayerInk.copy(alpha = 0.12f),
                                            RoundedCornerShape(50),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.GraphicEq,
                                        contentDescription = "Lossless audio",
                                        tint = MiniPlayerInk,
                                        modifier = Modifier.size(10.dp),
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        "LOSSLESS",
                                        fontSize = 7.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiniPlayerInk,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                info.summary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiniPlayerMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Progress as a hairline inside the capsule's lower edge
                // rather than a Material bar above it — it belongs to this
                // track, so it should live within the track's frame.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                        .height(3.dp)
                        .background(MiniPlayerInk.copy(alpha = 0.18f), RoundedCornerShape(50)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animatedProgress.coerceAtLeast(0.004f))
                            .fillMaxHeight()
                            .background(MiniPlayerInk, RoundedCornerShape(50)),
                    )
                }
        }
    }
}
