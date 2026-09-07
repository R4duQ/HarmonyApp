package com.harmony.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Bottom-anchored waveform used as the seek bar.
 *
 * The shape is generated deterministically from the song id, NOT decoded from
 * the audio: nothing in the analysis pipeline currently stores an amplitude
 * envelope (FeatureLayout is aggregate features only), and decoding a whole
 * file to draw a scrubber would be an absurd cost on the UI path. Because it's
 * seeded by id, a track's shape is stable across sessions and different from
 * its neighbours, which is the property that actually matters for orientation
 * ("I'm about a third into the loud bit"). If a real envelope is added later,
 * it drops straight into the [waveform] parameter and nothing else changes.
 *
 * @param progress 0..1, where the playhead sits.
 * @param onScrub fired continuously while dragging — update local UI state only.
 * @param onScrubFinished fired once on release or tap — commit the seek here.
 */
@Composable
fun WaveformSeekBar(
    progress: Float,
    waveform: FloatArray,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    onScrubCancel: () -> Unit,
    playedColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val fraction = progress.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            // Replacing Slider means replacing what Slider gave us for free.
            // Range info makes the position readable, setProgress makes it
            // seekable without a drag gesture.
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    onScrubFinished(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onScrubFinished((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                var x = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        x = start.x
                        onScrub((x / size.width).coerceIn(0f, 1f))
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        x += amount
                        onScrub((x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubFinished((x / size.width).coerceIn(0f, 1f)) },
                    onDragCancel = { onScrubCancel() },
                )
            },
    ) {
        if (waveform.isEmpty() || size.width <= 0f) return@Canvas

        val slot = size.width / waveform.size
        val barWidth = slot * 0.62f
        val playheadX = size.width * fraction

        waveform.forEachIndexed { i, amplitude ->
            val left = i * slot + (slot - barWidth) / 2f
            val height = max(barWidth, size.height * amplitude)
            val corner = min(barWidth / 2f, height / 2f)
            drawRoundRect(
                color = if (left + barWidth / 2f <= playheadX) playedColor else trackColor,
                topLeft = Offset(left, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(corner, corner),
            )
        }

        // Playhead. Inset from the edges so it never half-disappears at 0% or
        // 100%, which otherwise looks like a rendering bug rather than an end.
        val lineWidth = 2.5.dp.toPx()
        val x = playheadX.coerceIn(lineWidth / 2f, size.width - lineWidth / 2f)
        drawRoundRect(
            color = playedColor,
            topLeft = Offset(x - lineWidth / 2f, 0f),
            size = Size(lineWidth, size.height),
            cornerRadius = CornerRadius(lineWidth / 2f, lineWidth / 2f),
        )
    }
}

/**
 * Stable pseudo-envelope for a track. Same id in, same shape out, forever.
 */
@Composable
fun rememberWaveform(songId: Long?, bars: Int = 80): FloatArray =
    remember(songId, bars) { buildWaveform(songId ?: 0L, bars) }

private fun buildWaveform(seed: Long, bars: Int): FloatArray {
    if (bars <= 1) return FloatArray(bars) { 0.5f }
    // java.util.Random has a specified algorithm, so this is reproducible
    // across devices and Android versions — which is the whole point.
    val random = java.util.Random(seed)
    var current = FloatArray(bars) { random.nextFloat() }

    // Two passes of a 3-tap blur turn white noise into something with runs of
    // loud and quiet, which is what a real envelope looks like at this zoom.
    repeat(2) {
        val next = FloatArray(bars)
        for (i in 0 until bars) {
            val a = current[max(0, i - 1)]
            val b = current[i]
            val c = current[min(bars - 1, i + 1)]
            next[i] = (a + b * 2f + c) / 4f
        }
        current = next
    }

    return FloatArray(bars) { i ->
        val t = i / (bars - 1f)
        // Taper the first and last few bars so the shape starts and ends
        // rather than being sliced off at the edges.
        val edge = min(1f, min(t, 1f - t) * 9f)
        // Gentle arc: most tracks are quieter at the top and tail than in the
        // middle, and a flat rectangle of noise reads as a texture, not a song.
        val arc = 0.74f + 0.26f * sin(t * Math.PI).toFloat()
        (0.16f + current[i] * 1.30f * arc * edge).coerceIn(0.07f, 1f)
    }
}
