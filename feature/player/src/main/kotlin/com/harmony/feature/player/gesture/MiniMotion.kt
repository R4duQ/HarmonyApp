package com.harmony.feature.player.gesture

import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/*
 * The mini player's motion, played exactly as Figma exports it.
 *
 * A [Timeline] is the set of keyframe tracks of one Figma frame. Each track is
 * CSS keyframes: times in ms, a value per keyframe, and the timing function
 * of each keyframe, which applies to the segment that starts there (null is
 * linear, [STEP_END] holds the value). Figma bakes its springs into densely
 * sampled linear keyframes and gives its eased moves as cubic-bezier
 * segments; both are reproduced here without re-timing anything.
 */

/** An animated property of a layer. */
internal enum class Prop {
    TranslateX, TranslateY, ScaleX, ScaleY, Rotate, Width, Height, Radius, Opacity,
    Background, Fill, Stroke, Blur, ShadowBlur, ShadowAlpha,
}

/** A layer (by its role in the player) and one of its properties. */
internal data class Key(val layer: String, val prop: Prop)

/** CSS step-end: the value holds until the next keyframe. */
internal val STEP_END = Easing { 0f }

internal sealed interface Track {
    val times: FloatArray
    val easings: Array<Easing?>

    /** The segment [t] falls in and how far along it, eased; -1 before the first keyframe, size-1 after the last. */
    fun locate(t: Float): Pair<Int, Float> {
        if (t <= times.first()) return -1 to 0f
        if (t >= times.last()) return times.lastIndex to 0f
        var i = 0
        while (i < times.lastIndex - 1 && t >= times[i + 1]) i++
        val span = times[i + 1] - times[i]
        val linear = if (span <= 0f) 1f else (t - times[i]) / span
        val easing = easings[i]
        return i to (easing?.transform(linear) ?: linear)
    }
}

internal class NumberTrack(
    override val times: FloatArray,
    val values: FloatArray,
    override val easings: Array<Easing?>,
) : Track {
    fun at(t: Float): Float {
        val (i, f) = locate(t)
        return when {
            i < 0 -> values.first()
            i >= times.lastIndex -> values.last()
            else -> values[i] + (values[i + 1] - values[i]) * f
        }
    }
}

internal class ColorTrack(
    override val times: FloatArray,
    private val argb: LongArray,
    override val easings: Array<Easing?>,
) : Track {
    fun at(t: Float): Color {
        val (i, f) = locate(t)
        return when {
            i < 0 -> Color(argb.first())
            i >= times.lastIndex -> Color(argb.last())
            // CSS interpolates colours channel by channel in sRGB, as lerp does.
            else -> lerp(Color(argb[i]), Color(argb[i + 1]), f)
        }
    }
}

/** One Figma frame's animation. */
internal class Timeline(val durationMs: Float, private val tracks: Map<Key, Track>) {

    fun has(layer: String, prop: Prop): Boolean = Key(layer, prop) in tracks

    /** [prop] of [layer] at [t] ms, or [rest] when Figma doesn't animate it. */
    fun number(layer: String, prop: Prop, t: Float, rest: Float): Float =
        (tracks[Key(layer, prop)] as? NumberTrack)?.at(t) ?: rest

    fun color(layer: String, prop: Prop, t: Float, rest: Color): Color =
        (tracks[Key(layer, prop)] as? ColorTrack)?.at(t) ?: rest

    /**
     * The first time from which [layer]'s opacity stays at 0 for good (or
     * until [until]); the outgoing song is fully gone from here.
     */
    fun goneFrom(layer: String): Float {
        val track = tracks[Key(layer, Prop.Opacity)] as? NumberTrack ?: return durationMs
        for (i in track.values.indices) {
            if ((i until track.values.size).all { track.values[it] == 0f }) return track.times[i]
        }
        return durationMs
    }

    /** The last time [layer]'s opacity is still 0 before it first shows. */
    fun hiddenUntil(layer: String): Float {
        val track = tracks[Key(layer, Prop.Opacity)] as? NumberTrack ?: return 0f
        var last = 0f
        for (i in track.values.indices) {
            if (track.values[i] == 0f) last = track.times[i] else break
        }
        return last
    }
}
