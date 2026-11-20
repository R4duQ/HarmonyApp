package com.harmony.feature.player.gesture

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stage 3: the finger, the buttons and the screen reader, on the real player with its real timelines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h300dp-xhdpi")
class GestureMiniBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var skips = 0
    private var replays = 0
    private var expands = 0
    private var current by mutableStateOf(midnight)
    private var next by mutableStateOf<MiniTrack?>(solar)
    private lateinit var state: MiniMotionState

    /** When the last command came, in ms of the test clock. */
    private var commandAt = -1L

    private fun setUp(reducedMotion: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            state = rememberMiniMotionState()
            Box(Modifier.width(388.dp)) {
                GestureMiniPlayer(
                    current = current, next = next, isPlaying = true,
                    progress = { FIGMA_PROGRESS }, positionMs = { 120_000L },
                    actions = GestureMiniActions(
                        onSkip = { skips++; commandAt = rule.mainClock.currentTime; next?.let { current = it; next = null } },
                        onReplay = { replays++; commandAt = rule.mainClock.currentTime },
                        onExpand = { expands++ },
                    ),
                    labels = labels, state = state, reducedMotion = reducedMotion,
                    artwork = { _, _ -> },
                )
            }
        }
        rule.mainClock.advanceTimeBy(100)
    }

    private fun swipe(dxDp: Float, durationMs: Long = 400) {
        rule.onNodeWithTag(MiniTags.PLAYER).performTouchInput {
            val y = centerY
            val x0 = centerX
            val dxPx = dxDp * density
            down(Offset(x0, y))
            val steps = (durationMs / 16).toInt()
            repeat(steps) { moveBy(Offset(dxPx / steps, 0f), delayMillis = 16) }
            up()
        }
    }

    private fun settle(ms: Long = 2_000) = rule.mainClock.advanceTimeBy(ms)

    @Test
    fun `a short swipe goes back to rest without a command`() {
        setUp()
        swipe(30f)
        settle()
        assertEquals(0, skips)
        assertEquals(0, replays)
        assertEquals(MiniMode.Rest, state.mode)
        swipe(-30f)
        settle()
        assertEquals(0, replays)
        assertEquals(MiniMode.Rest, state.mode)
    }

    @Test
    fun `a swipe right past the threshold skips once, at the handoff, and ends at rest on the next song`() {
        setUp()
        val start = rule.mainClock.currentTime
        swipe(140f)
        rule.mainClock.advanceTimeBy(100)
        assertEquals(0, skips) // still absorbing: the song changes when nothing is visible
        assertTrue(state.busy)
        settle()
        assertEquals(1, skips)
        assertTrue(commandAt > start)
        assertEquals(MiniMode.Rest, state.mode)
        assertTrue(!state.busy)
        assertEquals(solar, state.shown(current))
    }

    @Test
    fun `a swipe left past the threshold replays once`() {
        setUp()
        swipe(-140f)
        settle()
        assertEquals(1, replays)
        assertEquals(0, skips)
        assertEquals(MiniMode.Rest, state.mode)
        assertEquals(midnight, state.shown(current))
    }

    @Test
    fun `swipes and taps during a committed transition do nothing more`() {
        setUp()
        swipe(140f)
        rule.mainClock.advanceTimeBy(50)
        swipe(140f)
        swipe(-140f)
        rule.onNodeWithTag(MiniTags.SKIP).performClick()
        rule.onNodeWithTag(MiniTags.REPLAY).performClick()
        settle()
        assertEquals(1, skips)
        assertEquals(0, replays)
    }

    @Test
    fun `with nothing to skip to, a swipe right gives a little and goes back`() {
        next = null
        setUp()
        swipe(400f)
        settle()
        assertEquals(0, skips)
        assertEquals(MiniMode.Rest, state.mode)
        // The skip button says it can't, too.
        val disabled = rule.onNodeWithTag(MiniTags.SKIP).fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        assertTrue(disabled != null)
    }

    @Test
    fun `the buttons play the same transitions with a tap`() {
        setUp()
        rule.onNodeWithTag(MiniTags.SKIP).performClick()
        settle()
        assertEquals(1, skips)
        rule.onNodeWithTag(MiniTags.REPLAY).performClick()
        settle()
        assertEquals(1, replays)
    }

    @Test
    fun `reduced motion changes the song at once, with no timeline`() {
        setUp(reducedMotion = true)
        swipe(140f)
        rule.mainClock.advanceTimeBy(16)
        assertEquals(1, skips)
        assertEquals(MiniMode.Rest, state.mode)
        rule.onNodeWithTag(MiniTags.REPLAY).performClick()
        rule.mainClock.advanceTimeBy(16)
        assertEquals(1, replays)
    }

    @Test
    fun `a screen reader has every action, and the capsule opens the player`() {
        setUp()
        val actions = rule.onNodeWithTag(MiniTags.PLAYER).fetchSemanticsNode().config
        val player = rule.onNode(androidx.compose.ui.test.hasAnyChild(androidx.compose.ui.test.hasTestTag(MiniTags.REPLAY)))
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
        val names = player.orEmpty().map { it.label }
        assertTrue(names.toString(), names.containsAll(listOf("Restart song", "Next song", "Pause")))
        player!!.first { it.label == "Next song" }.action()
        settle()
        assertEquals(1, skips)
        rule.onNodeWithTag(MiniTags.CAPSULE).performClick()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(1, expands)
        assertTrue(actions.contains(SemanticsActions.CustomActions) || player.isNotEmpty())
    }

    @Test
    fun `a song changed elsewhere arrives out of the skip button, and the pin never sticks`() {
        setUp()
        rule.runOnIdle {
            current = solar
            next = null
        }
        // Let the snapshot reach the composition (its apply notification is posted to the main looper).
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(50)
        assertEquals(MiniMode.SkipCompleted, state.mode)
        settle()
        assertEquals(MiniMode.Rest, state.mode)
        assertEquals(solar, state.shown(current))
        assertEquals(0, skips)
    }
}
