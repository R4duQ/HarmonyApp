package com.harmony.feature.player.gesture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

@Composable
fun rememberMiniMotionState(): MiniMotionState = remember { MiniMotionState() }

/**
 * What the mini player is doing: at rest, following a finger, or playing one
 * of the Figma timelines. Plays each timeline at its own speed, in real time
 * (the curves are in the tracks: time advances linearly), stops at the
 * handoff to change the song, and refuses a second transition until the
 * first is over, so one gesture is always one playback command.
 */
@Stable
class MiniMotionState internal constructor() {
    internal var mode by mutableStateOf(MiniMode.Rest)
        private set
    internal val time = Animatable(0f)

    /** The song the incoming capsule shows: the next one, or the same one restarted. */
    internal var incoming by mutableStateOf<MiniTrack?>(null)
        private set

    /** Shown in place of the player's song from the handoff until the player reports the same one. */
    private var pinned by mutableStateOf<MiniTrack?>(null)

    /** A committed transition is playing: no other gesture or button starts another. */
    var busy by mutableStateOf(false)
        private set

    private var dragging by mutableStateOf(false)
    private var dragTime by mutableFloatStateOf(0f)

    /** Automatic next: the player has moved on, so the held preparation can let the next song out. */
    private var released = false

    /** Automatic next: how far the orbit has turned in the hold cycles so far, carried into the pose. */
    private var spin by mutableFloatStateOf(0f)

    /**
     * Where the left button is in frame 07: 0 is previous track (the first 5 s
     * of a song), [LEFT_MORPH_REPLAY_MS] the replay arrow. It plays Figma's
     * turn forward when a song passes 5 s and backward when one starts again.
     */
    internal val leftMorph = Animatable(LEFT_MORPH_REPLAY_MS)

    /** The left button takes the song back to the one before (else it restarts this one). */
    var leftIsPrevious by mutableStateOf(false)
        private set

    /** The replay timeline is taking the song back to the one before (frame 04 with the previous-track glyph). */
    private var previousRun by mutableStateOf(false)

    private var lastKey: Any? = null
    private var nextOfLast: Any? = null
    private var unpin: Job? = null

    internal fun pose(g: MiniGeometry): MiniPose {
        val left = leftMorph.value
        return when {
            mode == MiniMode.Rest -> miniPose(MiniMode.Rest, 0f, g, leftMorphMs = left)
            dragging -> miniPose(mode, dragTime, g, leftMorphMs = left, previousRun = previousRun)
            else -> miniPose(mode, time.value, g, extraSpinDegrees = spin, leftMorphMs = left, previousRun = previousRun)
        }
    }

    /** The song the outgoing (resting) capsule shows. */
    internal fun shown(current: MiniTrack): MiniTrack = pinned ?: current

    // ---- Transitions -------------------------------------------------------------------

    /** Skips to [target]: absorbed into the skip button, [target] unfolding out of it. */
    fun skip(scope: CoroutineScope, target: MiniTrack, reducedMotion: Boolean, fromDrag: Boolean = false, command: () -> Unit) {
        if (busy || (dragging && !fromDrag)) return
        if (reducedMotion) {
            toRest()
            pinned = target
            command()
            return
        }
        run(scope, MiniMode.Skip, target, from = if (fromDrag) dragTime else 0f, command)
    }

    /** Restarts [song]: absorbed into the replay button, the same song settling back in. */
    fun replay(scope: CoroutineScope, song: MiniTrack, reducedMotion: Boolean, fromDrag: Boolean = false, command: () -> Unit) {
        if (busy || (dragging && !fromDrag)) return
        previousRun = false
        if (reducedMotion) {
            toRest()
            command()
            return
        }
        run(scope, MiniMode.Replay, song, from = if (fromDrag) dragTime else 0f, command)
    }

    /**
     * Back to [target], the song before: the replay timeline (frame 04), the
     * left button keeping its previous-track glyph, and [target] growing back
     * out of it where the song went in.
     */
    fun previous(scope: CoroutineScope, target: MiniTrack, reducedMotion: Boolean, fromDrag: Boolean = false, command: () -> Unit) {
        if (busy || (dragging && !fromDrag)) return
        previousRun = true
        if (reducedMotion) {
            toRest()
            pinned = target
            command()
            return
        }
        run(scope, MiniMode.Replay, target, from = if (fromDrag) dragTime else 0f, command)
    }

    private fun run(scope: CoroutineScope, mode: MiniMode, track: MiniTrack?, from: Float, command: (() -> Unit)?) {
        busy = true
        unpin?.cancel()
        scope.launch {
            this@MiniMotionState.incoming = track
            this@MiniMotionState.mode = mode
            time.snapTo(from)
            dragging = false
            val timeline = timelineOf(mode)!!
            if (command != null) {
                val handoff = handoffMs(mode)
                if (time.value < handoff) play(handoff)
                // Neither capsule is visible: the song changes now, and so does what is shown.
                if (track != null) pinned = track
                command()
            }
            play(timeline.durationMs)
            settle()
        }
    }

    /**
     * The song is ending on its own (frame 06): [old] goes into the skip
     * button, which prepares the next song, pulsing through its hold cycle
     * for as long as the player hasn't started it, then lets [next] out. The
     * player moves on by itself: there is no command. If it never does (a
     * pause at the very end, a refused advance) the button gives up after
     * [AUTO_NEXT_GIVE_UP_MS] and the song comes back out.
     */
    internal fun autoNext(scope: CoroutineScope, old: MiniTrack, next: MiniTrack) {
        if (busy || dragging) return
        busy = true
        released = false
        spin = 0f
        unpin?.cancel()
        pinned = old
        scope.launch {
            incoming = next
            mode = MiniMode.AutoNext
            time.snapTo(0f)
            play(AUTO_NEXT_HOLD_FROM_MS)
            var held = 0f
            while (!released) {
                play(AUTO_NEXT_HOLD_TO_MS)
                if (released) break
                held += AUTO_NEXT_HOLD_TO_MS - AUTO_NEXT_HOLD_FROM_MS
                if (held >= AUTO_NEXT_GIVE_UP_MS) {
                    giveUp()
                    return@launch
                }
                // One more cycle: the ring, core and button are back where it began; the orbit turns on.
                spin += autoNextLoopSpinDegrees()
                time.snapTo(AUTO_NEXT_HOLD_FROM_MS)
            }
            val born = MiniMotionData.AUTO_NEXT.hiddenUntil("incoming")
            if (time.value < born) play(born)
            pinned = incoming
            play(MiniMotionData.AUTO_NEXT.durationMs)
            spin = 0f
            settle()
        }
    }

    /** The player didn't move on: the same timeline back to rest, the orbit unwinding with it. */
    private suspend fun giveUp() {
        val from = time.value
        val turned = spin
        kotlinx.coroutines.coroutineScope {
            launch {
                androidx.compose.animation.core.animate(turned, 0f, animationSpec = tween((from * timeScale).toInt(), easing = LinearEasing)) { v, _ -> spin = v }
            }
            time.animateTo(0f, tween((from * timeScale).toInt().coerceAtLeast(1), easing = LinearEasing))
        }
        spin = 0f
        pinned = null
        mode = MiniMode.Rest
        incoming = null
        busy = false
    }

    private fun toRest() {
        dragging = false
        mode = MiniMode.Rest
        incoming = null
    }

    /** 1 is Figma's own speed; the motion lab slows it down to look closely. */
    internal var timeScale = 1f

    private suspend fun play(to: Float) {
        val span = to - time.value
        if (span <= 0f) return
        time.animateTo(to, tween(durationMillis = (span * timeScale).toInt().coerceAtLeast(1), easing = LinearEasing))
    }

    /** Plays [mode] on its own, with no playback command (the motion lab). */
    internal fun preview(scope: CoroutineScope, mode: MiniMode, track: MiniTrack?) {
        if (busy || dragging) return
        run(scope, mode, track, from = 0f, command = null)
    }

    /** Exactly at rest: the incoming capsule becomes the resting one. */
    private suspend fun settle() {
        incoming?.let { if (pinned == null && (mode == MiniMode.SkipCompleted || mode == MiniMode.AutoNext)) pinned = it }
        mode = MiniMode.Rest
        incoming = null
        previousRun = false
        time.snapTo(0f)
        busy = false
    }

    /** Plays [mode] for a change made elsewhere (no command: it already happened). */
    private fun announce(scope: CoroutineScope, mode: MiniMode, track: MiniTrack?, from: Float = 0f) {
        if (busy || dragging) return
        run(scope, mode, track, from, command = null)
    }

    /** Holds the player at [t] ms into [mode] with [incoming] (the motion lab's scrubber and the frame tests). */
    internal suspend fun showFrame(mode: MiniMode, t: Float, incoming: MiniTrack?) {
        this.mode = mode
        this.incoming = incoming
        time.snapTo(t)
    }

    // ---- Following the player ----------------------------------------------------------

    /**
     * Keeps up with the player: a song it reports that we asked for ends the
     * pin; one that changed elsewhere (the notification, a headset, Android
     * Auto, the end of a song) still arrives the designed way: forward out of
     * the skip button, back with the replay entrance. A restart made
     * elsewhere gets the restart pulse.
     */
    @Composable
    internal fun Follow(
        current: MiniTrack,
        next: MiniTrack?,
        positionMs: () -> Long,
        reducedMotion: Boolean,
        durationMs: () -> Long = { 0L },
        isPlaying: Boolean = false,
        hasPrevious: Boolean = false,
    ) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // Frame 07: previous track for a song's first 5 s, then the replay arrow.
        LaunchedEffect(hasPrevious, reducedMotion) {
            var first = true
            snapshotFlow { hasPrevious && positionMs() < LEFT_MORPH_FROM_MS }
                .collectLatest { early ->
                    leftIsPrevious = early
                    if (first || reducedMotion) {
                        leftMorph.snapTo(if (early) 0f else LEFT_MORPH_REPLAY_MS)
                        first = false
                        return@collectLatest
                    }
                    // A restart turns the arrow back once its own timeline has finished.
                    snapshotFlow { busy }.first { !it }
                    if (early) turnLeft(back = true) else turnLeft(back = false)
                }
        }
        SideEffect {
            val previous = lastKey
            if (previous != null && previous != current.key) {
                // What came next before this change says whether it went forward.
                songChanged(scope, current, forward = nextOfLast == current.key, reducedMotion)
            }
            lastKey = current.key
            nextOfLast = next?.key
        }
        LaunchedEffect(busy, pinned) {
            // A pin that the player never confirms (the skip was refused) gives way to what it does play.
            if (!busy && pinned != null && pinned?.key != current.key) {
                delay(UNPIN_AFTER_MS)
                pinned = null
            } else if (!busy && pinned?.key == current.key) {
                pinned = null
            }
        }
        // The song about to end on its own: start frame 06 so the capsule is in the button as it ends.
        LaunchedEffect(current.key, next?.key, reducedMotion, isPlaying) {
            if (reducedMotion || !isPlaying || next == null || next.key == current.key) return@LaunchedEffect
            var scheduled = false
            snapshotFlow { durationMs() to positionMs() }.collect { (duration, position) ->
                val remaining = duration - position
                if (scheduled || duration <= 0L || remaining !in 1..AUTO_NEXT_WATCH_MS) return@collect
                scheduled = true
                scope.launch {
                    delay((remaining - AUTO_NEXT_LEAD_MS.toLong()).coerceAtLeast(0L))
                    if (lastKey == current.key) autoNext(scope, shown(current), next)
                }
            }
        }
        LaunchedEffect(current.key, reducedMotion) {
            var last = positionMs()
            snapshotFlow { positionMs() }.collect { now ->
                // Back to the start of the same song, not by us: the restart pulse.
                if (!reducedMotion && !busy && !dragging && last > RESTART_FROM_MS && now < RESTART_TO_MS) {
                    announce(scope, MiniMode.ReplayCompleted, null)
                }
                last = now
            }
        }
    }

    /** Frame 07's turn, forward (previous track to replay) or played back, at Figma's speed. */
    private suspend fun turnLeft(back: Boolean) {
        if (back) {
            if (leftMorph.value <= 0f) return
            if (leftMorph.value > LEFT_MORPH_TO_MS) leftMorph.snapTo(LEFT_MORPH_TO_MS)
            val span = leftMorph.value - LEFT_MORPH_FROM_MS
            if (span > 0f) leftMorph.animateTo(LEFT_MORPH_FROM_MS, tween((span * timeScale).toInt().coerceAtLeast(1), easing = LinearEasing))
            leftMorph.snapTo(0f)
        } else {
            if (leftMorph.value >= LEFT_MORPH_TO_MS) return
            if (leftMorph.value < LEFT_MORPH_FROM_MS) leftMorph.snapTo(LEFT_MORPH_FROM_MS)
            val span = LEFT_MORPH_TO_MS - leftMorph.value
            leftMorph.animateTo(LEFT_MORPH_TO_MS, tween((span * timeScale).toInt().coerceAtLeast(1), easing = LinearEasing))
        }
    }

    private fun songChanged(scope: CoroutineScope, current: MiniTrack, forward: Boolean, reducedMotion: Boolean) {
        if (busy && mode == MiniMode.AutoNext) {
            // The player moved on while the button prepares: release. Whatever it went to is what comes out.
            if (incoming?.key != current.key && time.value < MiniMotionData.AUTO_NEXT.hiddenUntil("incoming")) incoming = current
            released = true
            return
        }
        if (pinned?.key == current.key) {
            // The song we asked for: the pin has done its job.
            if (!busy) pinned = null
            return
        }
        pinned = null
        if (reducedMotion || busy || dragging) return
        if (forward) {
            announce(scope, MiniMode.SkipCompleted, current)
        } else {
            // Back (or anywhere else): the entrance of the replay timeline, from its handoff, as the song before.
            previousRun = true
            announce(scope, MiniMode.Replay, current, from = handoffMs(MiniMode.Replay))
        }
    }

    // ---- The finger --------------------------------------------------------------------

    /**
     * Sideways: the capsule follows the finger toward the button it is going
     * to, through the first part of that timeline, with resistance. Let go
     * past [COMMIT_DP] (or flung that way) and the timeline plays on from
     * there; short of it, it plays back to rest. Up: opens the player.
     */
    internal suspend fun detectSwipes(
        input: PointerInputScope,
        scope: CoroutineScope,
        g: MiniGeometry,
        nextTrack: () -> MiniTrack?,
        /** What comes back out of the left button: the song before, or this one restarted. */
        leftTrack: () -> MiniTrack,
        reducedMotion: Boolean,
        onSkip: () -> Unit,
        onReplay: () -> Unit,
        onExpand: () -> Unit,
    ) = with(input) {
        val dp = density
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (busy) return@awaitEachGesture
            var slop = androidx.compose.ui.geometry.Offset.Zero
            val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                slop = over
                change.consume()
            } ?: return@awaitEachGesture
            if (abs(slop.y) > abs(slop.x)) {
                // Up opens the player; down is left to the page.
                var dy = slop.y
                drag(start.id) { dy += it.positionChange().y; it.consume() }
                if (-dy / dp > EXPAND_DP) onExpand()
                return@awaitEachGesture
            }
            val tracker = VelocityTracker()
            var dx = slop.x / dp
            val hasNext = nextTrack() != null
            dragging = true
            fun follow() {
                // Reduced motion: the finger is followed in the decision only, nothing moves.
                if (reducedMotion) return
                val skipping = dx > 0f
                val wanted = if (skipping) MiniMode.Skip else MiniMode.Replay
                if (mode != wanted) {
                    mode = wanted
                    previousRun = !skipping && leftIsPrevious
                    incoming = if (skipping) nextTrack() else leftTrack()
                }
                dragTime = if (reducedMotion) 0f else scrubTime(wanted, g, resisted(wanted, g, abs(dx), hasNext))
            }
            follow()
            drag(start.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                dx += change.positionChange().x / dp
                change.consume()
                follow()
            }
            val velocity = tracker.calculateVelocity().x / dp
            val skipping = dx > 0f
            val mode = if (skipping) MiniMode.Skip else MiniMode.Replay
            val reached = resisted(mode, g, abs(dx), hasNext)
            val flung = abs(velocity) > FLING_DP_S && (velocity > 0f) == skipping && reached > FLING_MIN_DP
            val commit = (!skipping || hasNext) && (reached >= COMMIT_DP || flung)
            when {
                commit && skipping -> onSkip()
                commit -> onReplay()
                else -> scope.launch { back() }
            }
            if (!commit) dragging = false
        }
    }

    /** Short of the threshold: the same timeline, played back to rest. */
    private suspend fun back() {
        val from = dragTime
        dragging = false
        time.snapTo(from)
        if (from > 0f) time.animateTo(0f, tween(durationMillis = from.toInt().coerceAtLeast(1), easing = LinearEasing))
        if (!busy) {
            mode = MiniMode.Rest
            incoming = null
            previousRun = false
        }
    }

    internal companion object {
        /** Past this (after resistance) a released swipe commits. */
        const val COMMIT_DP = 56f
        const val FLING_DP_S = 700f
        const val FLING_MIN_DP = 12f
        const val EXPAND_DP = 32f
        /** With nothing to skip to, the capsule gives this much and no more. */
        const val NO_NEXT_DP = 24f
        const val UNPIN_AFTER_MS = 3_000L
        const val RESTART_FROM_MS = 3_000L
        const val RESTART_TO_MS = 1_000L
        /** Frame 06 fills the rail for its first 20.7% (580 ms): it starts that long before the song ends. */
        const val AUTO_NEXT_LEAD_MS = 0.207f * 2800f
        /** Within this of the end, the start of frame 06 is scheduled. */
        const val AUTO_NEXT_WATCH_MS = 2_500L
        /** The longest the skip button prepares before it gives the song back. */
        const val AUTO_NEXT_GIVE_UP_MS = 6_000f

        /** How far the finger moves the capsule's leading edge: one to one at first, never past the scrub's end. */
        fun resisted(mode: MiniMode, g: MiniGeometry, finger: Float, hasNext: Boolean): Float {
            val limit = if (mode == MiniMode.Skip && !hasNext) NO_NEXT_DP else Scrub.of(mode, g).maxEdge
            return limit * (1f - exp(-finger / limit))
        }

        /** The finger distance that moves the edge [edge] (the inverse of [resisted]). */
        fun fingerFor(mode: MiniMode, g: MiniGeometry, edge: Float, hasNext: Boolean = true): Float {
            val limit = if (mode == MiniMode.Skip && !hasNext) NO_NEXT_DP else Scrub.of(mode, g).maxEdge
            return -limit * ln(1f - edge / limit)
        }

        /** The time in [mode]'s timeline at which the capsule's leading edge has moved [edge] dp. */
        fun scrubTime(mode: MiniMode, g: MiniGeometry, edge: Float): Float = Scrub.of(mode, g).timeFor(edge)
    }
}

/**
 * The drag is the first part of the timeline. The capsule's leading edge
 * (its left side going to skip, its right side going to replay) moves with
 * the finger; the time is wherever that edge is in Figma's timeline, and
 * everything else (compression, fades, the button's response) is at that
 * same time.
 */
internal class Scrub private constructor(private val times: FloatArray, private val edges: FloatArray) {
    val maxEdge: Float get() = edges.last()

    fun timeFor(edge: Float): Float {
        if (edge <= 0f) return 0f
        if (edge >= edges.last()) return times.last()
        var i = 0
        while (edges[i + 1] < edge) i++
        val f = (edge - edges[i]) / (edges[i + 1] - edges[i])
        return times[i] + (times[i + 1] - times[i]) * f
    }

    companion object {
        private const val STEP_MS = 2f
        /** The drag covers the timeline until the leading edge has gone this share of the way. */
        private const val SHARE = 0.6f
        private val cache = HashMap<Pair<MiniMode, Float>, Scrub>()

        fun edge(mode: MiniMode, g: MiniGeometry, t: Float): Float {
            val pose = miniPose(mode, t, g).outgoing
            return if (mode == MiniMode.Skip) pose.x - g.capsuleX
            else (g.capsuleX + g.capsuleWidth) - (pose.x + pose.width)
        }

        fun of(mode: MiniMode, g: MiniGeometry): Scrub = cache.getOrPut(mode to g.width) {
            val end = handoffMs(mode)
            val total = edge(mode, g, end)
            // From the anticipation's furthest point back, the edge only moves forward.
            var t = 0f
            var lowest = 0f
            var lowestAt = 0f
            while (t <= end) {
                val e = edge(mode, g, t)
                if (e < lowest) { lowest = e; lowestAt = t }
                t += STEP_MS
            }
            t = lowestAt
            while (edge(mode, g, t) < 0f) t += STEP_MS
            val times = ArrayList<Float>()
            val edges = ArrayList<Float>()
            times += 0f; edges += 0f
            var last = 0f
            while (t <= end) {
                val e = edge(mode, g, t)
                if (e > last) { times += t; edges += e; last = e }
                if (e >= total * SHARE) break
                t += STEP_MS
            }
            Scrub(times.toFloatArray(), edges.toFloatArray())
        }
    }
}
