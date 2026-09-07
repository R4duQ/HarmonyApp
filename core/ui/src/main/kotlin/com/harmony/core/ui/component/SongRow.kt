package com.harmony.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harmony.core.model.Song
import kotlinx.coroutines.launch

/** Fraction of the row width a swipe must cross to commit "add to queue". */
private const val SWIPE_QUEUE_FRACTION = 0.32f

/**
 * The list row used by every song list in the app.
 *
 * Optional swipe-right-to-queue: when [onSwipeToQueue] is provided, dragging
 * the row rightward reveals a "Play next" affordance behind it and commits on
 * release past a threshold, then springs back (the row is NOT dismissed —
 * queueing doesn't remove the song from the list it lives in). The callback
 * inserts the song immediately after the current track rather than appending
 * to the queue's end.
 *
 * The gesture is opt-in per call site rather than always-on: rows inside the
 * queue sheet and playlist editor already own horizontal/drag semantics of
 * their own, and silently stacking another gesture there would fight them.
 */
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    onOverflowClick: (() -> Unit)? = null,
    onSwipeToQueue: (() -> Unit)? = null,
) {
    if (onSwipeToQueue == null) {
        SongRowContent(song, onClick, modifier, isPlaying, onOverflowClick)
        return
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offsetX = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width },
    ) {
        // Behind-the-row affordance, revealed as the row slides right.
        val progress = (offsetX.value / (rowWidth * SWIPE_QUEUE_FRACTION)).coerceIn(0f, 1f)
        if (offsetX.value > 1f) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.graphicsLayer {
                        // Icon settles in as the threshold approaches, so the
                        // commit point is legible before you let go.
                        scaleX = 0.7f + 0.3f * progress
                        scaleY = 0.7f + 0.3f * progress
                        alpha = progress
                    },
                )
                Text(
                    "Play next",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .graphicsLayer { alpha = progress },
                )
            }
        }

        Surface(
            modifier = Modifier
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(song.id) {
                    var total = 0f
                    var committed = false
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f; committed = false },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            // Right-only: a leftward drag stays at rest so the
                            // gesture can't be triggered by accident in the
                            // opposite direction.
                            total = (total + amount).coerceAtLeast(0f)
                            scope.launch { offsetX.snapTo(total) }
                            if (!committed && total >= rowWidth * SWIPE_QUEUE_FRACTION) {
                                committed = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragEnd = {
                            val shouldQueue = total >= rowWidth * SWIPE_QUEUE_FRACTION
                            scope.launch {
                                if (shouldQueue) {
                                    onSwipeToQueue()
                                    // Brief settle at the threshold so the
                                    // confirmation is visible, then return.
                                    offsetX.animateTo(rowWidth * SWIPE_QUEUE_FRACTION, tween(90))
                                }
                                offsetX.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f) }
                        },
                    )
                },
        ) {
            SongRowContent(song, onClick, Modifier, isPlaying, onOverflowClick)
        }
    }
}

@Composable
private fun SongRowContent(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier,
    isPlaying: Boolean,
    onOverflowClick: (() -> Unit)?,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            artworkUri = song.artworkUri,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${song.artist} · ${formatDuration(song.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onOverflowClick != null) {
            IconButton(onClick = onOverflowClick) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
            }
        } else {
            Spacer(Modifier.width(4.dp))
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
