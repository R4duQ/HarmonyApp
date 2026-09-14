package com.harmony.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.abs
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
    level: Float = 0f,
    brightness: Float = 0f,
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
        val playheadBar = fraction * (waveform.size - 1)

        waveform.forEachIndexed { i, amplitude ->
            val left = i * slot + (slot - barWidth) / 2f

            // Live response, strongest at the playhead and fading out over a
            // few bars either side. Driving the WHOLE bar row from the meter
            // would throw away the stable per-track shape that makes the
            // scrubber usable for orientation; confining it to a travelling
            // window keeps the shape readable while the part you're actually
            // listening to breathes.
            val distance = abs(i - playheadBar)
            val reach = (waveform.size * 0.09f).coerceAtLeast(3f)
            val nearness = (1f - distance / reach).coerceIn(0f, 1f)
            // Squared so the falloff is a soft bump rather than a hard cone.
            val influence = nearness * nearness

            // Brightness redistributes the response instead of adding to it:
            // bright passages lift the bars ahead of the playhead, bass-heavy
            // ones lean behind, so the row leans with the character of the
            // sound rather than just pulsing uniformly louder.
            val lean = if (i >= playheadBar) brightness else 1f - brightness
            val boost = 1f + level * influence * (0.45f + 0.75f * lean)

            val height = max(barWidth, size.height * (amplitude * boost).coerceAtMost(1f))
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

/** Mutable carrier for the shape currently on screen. */
private class WaveformMorph(initial: FloatArray) {
    var from: FloatArray = initial
    var displayed: FloatArray = initial
    /**
     * The target this holder has already reacted to. Compared by IDENTITY
     * against the incoming array — that is what makes "the song changed"
     * detectable during composition, before any effect has had a chance to
     * run.
     */
    var acknowledged: FloatArray = initial
}

/**
 * Like [rememberWaveform], but the shape MORPHS into the next track's
 * instead of being swapped for it.
 *
 * Every bar travels from where it currently stands to its new height, so a
 * track change reads as the waveform rearranging itself rather than one
 * picture being replaced by another. The bars are staggered left to right,
 * which gives the change a direction — the same direction playback runs —
 * and stops all eighty bars snapping in unison, which looks mechanical.
 *
 * The morph starts from what is ON SCREEN, not from the outgoing track's
 * final shape. That distinction only shows up when you skip twice quickly:
 * starting from the previous target would jump the bars back to a shape
 * that was never finished being drawn, whereas starting from the displayed
 * values lets the second skip pick up mid-flight.
 */
@Composable
fun rememberMorphingWaveform(songId: Long?, bars: Int = 80): FloatArray {
    val target = remember(songId, bars) { buildWaveform(songId ?: 0L, bars) }
    val morph = remember(bars) { WaveformMorph(target) }
    val progress = remember(bars) { Animatable(1f) }

    // The change has to be caught HERE, in composition, not in the effect
    // below. A LaunchedEffect runs after the composition that triggered it,
    // and by then this function has already computed and stored an output
    // for the new target using the OLD progress — which is sitting at 1f
    // from the previous morph. The effect would then capture that
    // already-new shape as its starting point and animate it to itself, so
    // the bars snapped and nothing appeared to move.
    val changed = morph.acknowledged !== target
    if (changed) {
        morph.from = morph.displayed.copyOf()
        morph.acknowledged = target
    }

    LaunchedEffect(target) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 620, easing = FastOutSlowInEasing))
    }

    // On the change frame the animation has not been reset yet, so read 0
    // rather than the stale 1f. Without this the first frame of every morph
    // is drawn fully at the destination and the animation visibly starts by
    // jumping backwards.
    val t = if (changed) 0f else progress.value
    // Portion of the duration spent spreading the start times across the
    // row. Too high and the last bars only begin as the first ones finish,
    // which reads as a wipe rather than a morph.
    val stagger = 0.38f
    val out = FloatArray(bars) { i ->
        val start = (i / (bars - 1f).coerceAtLeast(1f)) * stagger
        val local = ((t - start) / (1f - stagger)).coerceIn(0f, 1f)
        // Smoothstep per bar, on top of the curve already applied to the
        // whole sweep: the bars should also ease individually, or each one
        // starts and stops abruptly inside an otherwise smooth animation.
        val eased = local * local * (3f - 2f * local)
        val a = morph.from.getOrElse(i) { 0f }
        a + (target[i] - a) * eased
    }
    morph.displayed = out
    return out
}

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
