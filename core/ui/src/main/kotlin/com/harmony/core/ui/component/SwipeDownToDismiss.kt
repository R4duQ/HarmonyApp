package com.harmony.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * State for "drag the page down to go back".
 *
 * The page follows the finger 1:1 through [offset]. On release it either
 * commits — [onDismiss] runs exactly once and the page keeps travelling down
 * while the navigation transition brings the previous page in — or springs
 * back to rest.
 *
 * [dismissing] is also the double-back guard for the page's own Back button:
 * once a dismissal has been requested, by gesture or by button, nothing on
 * this page can ask for a second one.
 */
@Stable
class SwipeDownToDismissState internal constructor(
    private val scope: CoroutineScope,
    private val commitDistancePx: Float,
    private val flingVelocityPx: Float,
    private val minFlingDistancePx: Float,
) {
    internal val offset = Animatable(0f)

    /** Current downward travel of the page, in px. */
    val offsetPx: Float get() = offset.value

    /** True once a dismissal has been requested; never resets on this page. */
    var dismissing by mutableStateOf(false)
        private set

    /** True while a finger is actually dragging the page. */
    var dragging by mutableStateOf(false)
        internal set

    /** Height of the page, for "0 → 1" progress and the exit travel. */
    var pageHeightPx by mutableStateOf(0f)

    /** 0 at rest, 1 once the page has travelled its full height. */
    val progress: Float
        get() = if (pageHeightPx <= 0f) 0f else (offset.value / pageHeightPx).coerceIn(0f, 1f)

    /**
     * Requests the dismissal. Returns false if one was already requested —
     * the caller must then do nothing, which is what stops a quick double tap
     * on Back (or Back during the exit animation) from popping twice.
     */
    fun requestDismiss(onDismiss: () -> Unit): Boolean {
        if (dismissing) return false
        dismissing = true
        onDismiss()
        return true
    }

    internal fun dragBy(deltaPx: Float) {
        if (dismissing) return
        val next = (offset.value + deltaPx).coerceAtLeast(0f)
        scope.launch { offset.snapTo(next) }
    }

    internal fun release(velocityPxPerSec: Float, onDismiss: () -> Unit) {
        dragging = false
        if (dismissing) return
        val travelled = offset.value
        val commit = travelled >= commitDistancePx ||
            (velocityPxPerSec >= flingVelocityPx && travelled >= minFlingDistancePx)
        if (commit && requestDismiss(onDismiss)) {
            val end = if (pageHeightPx > 0f) pageHeightPx else travelled + commitDistancePx * 3
            scope.launch { offset.animateTo(end, tween(durationMillis = 240)) }
        } else {
            settle()
        }
    }

    internal fun cancel() {
        dragging = false
        if (!dismissing) settle()
    }

    private fun settle() {
        scope.launch {
            offset.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }
}

@Composable
fun rememberSwipeDownToDismissState(): SwipeDownToDismissState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scope, density) {
        with(density) {
            SwipeDownToDismissState(
                scope = scope,
                // Long enough that a stray drag across the title never
                // leaves the page, short enough to finish with a thumb.
                commitDistancePx = 140.dp.toPx(),
                flingVelocityPx = 1_100.dp.toPx(),
                minFlingDistancePx = 36.dp.toPx(),
            )
        }
    }
}

/**
 * Makes this element a handle for [state]: a DOWNWARD drag that starts here
 * moves the page; everything else is left alone.
 *
 * Why not `Modifier.draggable`: that would claim vertical drags in both
 * directions, so an upward swipe on the header could no longer scroll the
 * song list. Here nothing is consumed until the finger has crossed the
 * touch slop, and then only if the travel is downward and [canStart] allows
 * it. An upward drag, a sideways drag or a plain tap passes straight through
 * to the list, the swipe-to-queue rows and the buttons underneath.
 *
 * [canStart] is re-read on every gesture: the large title only hands the
 * gesture to the page while the list is scrolled to the very top, so a
 * downward swipe on a partly scrolled header scrolls the list back first.
 */
fun Modifier.swipeDownToDismiss(
    state: SwipeDownToDismissState,
    onDismiss: () -> Unit,
    canStart: () -> Boolean = { true },
): Modifier = this.then(
    Modifier.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (state.dismissing) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var pointerId = down.id
            var dx = 0f
            var dy = 0f
            // Phase 1: wait for the slop, without consuming anything.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                // Someone below us (the reorder grip, a row's swipe) already
                // owns this gesture.
                if (change.isConsumed) return@awaitEachGesture
                tracker.addPointerInputChange(change)
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (abs(dx) > slop || abs(dy) > slop) {
                    val downward = dy > slop && abs(dy) > abs(dx)
                    if (!downward || !canStart()) return@awaitEachGesture
                    change.consume()
                    break
                }
            }
            // Phase 2: the page is ours until the finger lifts.
            state.dragging = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: event.changes.firstOrNull { it.pressed }?.also { pointerId = it.id }
                if (change == null) {
                    state.cancel()
                    return@awaitEachGesture
                }
                tracker.addPointerInputChange(change)
                if (!change.pressed) {
                    change.consume()
                    state.release(tracker.calculateVelocity().y, onDismiss)
                    return@awaitEachGesture
                }
                state.dragBy(change.positionChange().y)
                change.consume()
            }
        }
    },
)

/** Stable holder so [swipeDownToDismiss] can read the latest callback without restarting. */
@Composable
fun rememberDismissCallback(onDismiss: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onDismiss)
    return remember { { latest() } }
}
