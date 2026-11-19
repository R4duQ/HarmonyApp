package com.harmony.core.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The Listening Room's motion tokens, in milliseconds. */
object RoomMotion {
    const val Tap = 100
    const val Small = 200
    const val Medium = 300
    const val Exit = 200
    const val Large = 450
    /** One turn of the record while it plays. */
    const val RecordTurn = 9_000

    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/**
 * True when the system's "Remove animations" is on (animator duration scale 0).
 * The record stops turning, pulses become still and transitions become
 * crossfades; gestures keep working.
 */
val LocalReducedMotion = compositionLocalOf { false }

val reducedMotion: Boolean
    @Composable @ReadOnlyComposable get() = LocalReducedMotion.current

@Composable
internal fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
