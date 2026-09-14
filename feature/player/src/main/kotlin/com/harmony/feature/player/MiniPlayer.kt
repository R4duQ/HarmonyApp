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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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

private const val MINI_SWIPE_UP_THRESHOLD_PX = 90f
private const val MINI_SWIPE_SKIP_THRESHOLD_PX = 120f

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
        visible = state.currentSong != null && song != null,
        enter = slideInVertically { it } + expandVertically() + fadeIn(),
        exit = slideOutVertically { it } + shrinkVertically() + fadeOut(),
    ) {
        if (song == null) return@AnimatedVisibility
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(miniPlayerField())
                .border(1.dp, MiniPlayerRim, RoundedCornerShape(26.dp))
                .clickable(onClick = onExpand),
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                                onDragStart = { totalDrag = 0f },
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    totalDrag += amount
                                },
                                onDragEnd = {
                                    when {
                                        totalDrag <= -MINI_SWIPE_SKIP_THRESHOLD_PX -> viewModel.onNext()
                                        totalDrag >= MINI_SWIPE_SKIP_THRESHOLD_PX -> viewModel.onPrevious()
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
                            (fadeIn(tween(300)) + scaleIn(initialScale = 0.9f))
                                .togetherWith(fadeOut(tween(200)))
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
                            (slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300)))
                                .togetherWith(slideOutVertically(tween(200)) { -it / 3 } + fadeOut(tween(200)))
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
