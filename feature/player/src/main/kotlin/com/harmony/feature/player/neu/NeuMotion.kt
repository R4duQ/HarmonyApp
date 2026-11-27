package com.harmony.feature.player.neu

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.harmony.core.model.Song
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * The record's motion, from the prototype's frames (Bj9Z7C1JD7kaXGK8h5Tw8M):
 *
 *  - Playing, the label turns clockwise a quarter every 780 ms, linear
 *    (Vinyl Spin, Phase 0 → 90 → 180 → 270, about 19 rpm), and the grooves
 *    catch two thin highlights. Paused ("Still"), the highlights go and the
 *    label turns back to upright, with the play key's 180 ms ease out.
 *
 *  - A new song: the record slides 24 dp under the sleeve while the play key
 *    shows Paused and the elapsed time fades ("Discul se retrage"); under
 *    the sleeve the label changes to the new song ("Eticheta se schimbă sub
 *    copertă"); 12 ms later it slides back out in 210 ms ("Discul nou este
 *    introdus"); 15 ms later, in 245 ms, the cover, title, artist, album and
 *    length cross-fade to the new song, the time returns and the key and
 *    the highlights show what the player is doing ("Piesa următoare în
 *    redare"). Tapped next or previous, the slide under takes the pressed
 *    frames' 140 ms ease in and out, then 14 ms and the label frame's 85 ms
 *    as the key rises; at the end of a song ("Final de melodie") it takes
 *    170 ms, then 8 ms.
 */

/** What the screen shows of a song. During a swap it lags the player by design. */
@Immutable
internal data class SongFace(
    val id: Long?,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUri: String?,
    val durationMs: Long,
) {
    companion object {
        fun of(song: Song?, durationMs: Long): SongFace = SongFace(
            id = song?.id,
            title = song?.title ?: "Nothing playing",
            artist = song?.artist.orEmpty(),
            album = song?.album,
            artworkUri = song?.artworkUri,
            durationMs = durationMs,
        )
    }
}

/** The label's turn: 90° every 780 ms. */
internal const val SPIN_DEG_PER_MS = 90f / 780f

/** How far the record slides under the sleeve, in the frame's dp. */
internal const val TUCK_DP = 24f

private val EndOfSongEasing = CubicBezierEasing(0.42f, 0f, 0.36f, 1f)
private val InsertEasing = CubicBezierEasing(0.22f, 0.7f, 0.28f, 1f)
private val SettleEasing = CubicBezierEasing(0.35f, 0f, 0.28f, 1f)

/**
 * One song change's timing: the slide under the sleeve, the wait before the
 * label changes, and the label's change itself (the tapped frames reach the
 * label frame with an 85 ms transition, the key rising meanwhile; the end
 * of a song cuts to it). Figma counts each "after delay" from the end of
 * the transition before it.
 */
private class Retract(val ms: Int, val easing: Easing, val holdMs: Long, val labelMs: Long)

private val Tapped = Retract(140, FigmaEaseInOut, 14, 85)
private val Ended = Retract(170, EndOfSongEasing, 8, 0)

@Stable
internal class NowPlayingMotion(initial: SongFace, playing: Boolean) {
    /** The song the text and cover show. */
    var face by mutableStateOf(initial)
        private set

    /** The song fading out under [face] while [settle] runs. */
    var outgoing by mutableStateOf<SongFace?>(null)
        private set

    /** The art on the record's label (it changes while the label is hidden). */
    var labelArt by mutableStateOf(initial.artworkUri)
        private set

    /** True from the first slide to the end of the cross-fade. */
    var swapping by mutableStateOf(false)
        private set

    /** The elapsed time and progress, held while the record is away. */
    var heldElapsedMs by mutableStateOf<Long?>(null)
        private set
    var heldProgress by mutableStateOf<Float?>(null)
        private set

    /** [outgoing] → [face]: 0 shows the old song, 1 the new. */
    val settle = Animatable(1f)

    /** 0 out beside the sleeve, 1 tucked 24 dp under it. */
    val tuck = Animatable(0f)

    val elapsedAlpha = Animatable(1f)

    /** The play key's look: 0 Paused, 1 Playing. */
    val playLook = Animatable(if (playing) 1f else 0f)

    /** The groove highlights: on while playing. */
    val gloss = Animatable(if (playing) 1f else 0f)

    /** The label's angle in degrees, clockwise. */
    val spin = Animatable(0f)

    private var tappedAt = Long.MIN_VALUE

    /** The last elapsed time and progress shown of [face]'s song. */
    internal var lastElapsed = 0L
    internal var lastProgress = 0f

    /** The elapsed time to show: the old song's, held, from the moment the player moves on until the cross-fade. */
    fun elapsed(liveMs: Long, liveId: Long?): Long = heldElapsedMs ?: if (swapping || liveId != face.id) lastElapsed else liveMs

    fun progress(live: Float, liveId: Long?): Float = heldProgress ?: if (swapping || liveId != face.id) lastProgress else live

    /** Previous or next was tapped (or the record swiped): the next song change is the tapped one. */
    fun markTapped() {
        tappedAt = SystemClock.uptimeMillis()
    }

    private fun takeTapped(): Boolean {
        val tapped = SystemClock.uptimeMillis() - tappedAt < 3_000
        tappedAt = Long.MIN_VALUE
        return tapped
    }

    internal fun update(face: SongFace) {
        this.face = face
        if (!swapping) labelArt = face.artworkUri
    }

    /** Turns the label back to upright the short way round. */
    suspend fun stopSpin(spec: AnimationSpec<Float>) {
        val a = ((spin.value % 360f) + 360f) % 360f
        spin.snapTo(a)
        spin.animateTo(if (a <= 180f) 0f else 360f, spec)
        spin.snapTo(0f)
    }

    /** Plays the swap to [target]. [elapsedMs] and [progress] are what the screen showed of the old song. */
    suspend fun swapTo(target: SongFace, playing: () -> Boolean, elapsedMs: Long, progress: Float, still: Boolean) {
        val retract = if (takeTapped()) Tapped else Ended
        if (still) {
            outgoing = null
            face = target
            labelArt = target.artworkUri
            settle.snapTo(1f); tuck.snapTo(0f); elapsedAlpha.snapTo(1f)
            playLook.snapTo(if (playing()) 1f else 0f); gloss.snapTo(if (playing()) 1f else 0f); spin.snapTo(0f)
            swapping = false
            heldElapsedMs = null; heldProgress = null
            return
        }
        // A swap cut short by another song: whatever was fading in is the song now.
        if (outgoing != null) { outgoing = null; settle.snapTo(1f) }
        if (!swapping) { heldElapsedMs = elapsedMs; heldProgress = progress }
        swapping = true

        // The record slides under the sleeve; the key shows Paused, the time fades.
        val a = tween<Float>(retract.ms, easing = retract.easing)
        coroutineScope {
            launch { tuck.animateTo(1f, a) }
            launch { elapsedAlpha.animateTo(0f, a) }
            launch { playLook.animateTo(0f, a) }
            launch { gloss.animateTo(0f, a) }
            launch { stopSpin(a) }
        }
        delay(retract.holdMs)
        // Under the sleeve, the label becomes the new song's.
        labelArt = target.artworkUri
        delay(retract.labelMs + 12)
        tuck.animateTo(0f, tween(210, easing = InsertEasing))
        delay(15)
        // Everything else follows: cover, words, length, the time and the key.
        outgoing = face
        face = target
        heldElapsedMs = null
        heldProgress = null
        settle.snapTo(0f)
        val c = tween<Float>(245, easing = SettleEasing)
        coroutineScope {
            launch { settle.animateTo(1f, c) }
            launch { elapsedAlpha.animateTo(1f, c) }
            launch { playLook.animateTo(if (playing()) 1f else 0f, c) }
            launch { gloss.animateTo(if (playing()) 1f else 0f, c) }
        }
        outgoing = null
        swapping = false
    }
}

/**
 * The motion of the Now Playing screen for [song]: the record swap on a song
 * change, the label's spin while [playing], and the key's look.
 */
@Composable
internal fun rememberNowPlayingMotion(
    song: Song?,
    durationMs: Long,
    playing: Boolean,
    elapsedMs: Long,
    progress: Float,
): NowPlayingMotion {
    val still = com.harmony.core.ui.theme.reducedMotion
    val face = SongFace.of(song, durationMs)
    val motion = remember { NowPlayingMotion(face, playing) }
    val currentFace by rememberUpdatedState(face)
    val currentPlaying by rememberUpdatedState(playing)
    val currentStill by rememberUpdatedState(still)

    // Song changes: the swap. The same song's details (a better length, new art) just update.
    if (!motion.swapping && face.id == motion.face.id) {
        motion.lastElapsed = elapsedMs
        motion.lastProgress = progress
    }
    LaunchedEffect(motion) {
        snapshotFlow { currentFace }.collectLatest { target ->
            if (target.id == motion.face.id && motion.outgoing == null && !motion.swapping) {
                motion.update(target)
                return@collectLatest
            }
            motion.swapTo(target, { currentPlaying }, motion.lastElapsed, motion.lastProgress, currentStill)
        }
    }

    // Play and pause outside a swap: the key, the highlights and the spin, 180 ms ease out.
    LaunchedEffect(motion, playing, motion.swapping, still) {
        if (motion.swapping) return@LaunchedEffect
        val spec = tween<Float>(if (still) 0 else 180, easing = FigmaEaseOut)
        val on = if (playing) 1f else 0f
        launch { motion.playLook.animateTo(on, spec) }
        launch { motion.gloss.animateTo(on, spec) }
        if (!playing || still) {
            motion.stopSpin(spec)
            return@LaunchedEffect
        }
        // The turn, frame by frame, from wherever the label is.
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val step = (now - last) / 1_000_000f * SPIN_DEG_PER_MS
            last = now
            motion.spin.snapTo((motion.spin.value + step) % 360f)
        }
    }
    return motion
}
