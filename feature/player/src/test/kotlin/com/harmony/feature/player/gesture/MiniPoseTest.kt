package com.harmony.feature.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The motion's rules, checked on the Figma timelines at the reference width and others. */
class MiniPoseTest {
    private val widths = listOf(388f, 328f, 360f, 412f, 600f)

    @Test
    fun `the next song is born exactly where the outgoing one disappears, at every width`() {
        for (w in widths) {
            val g = MiniGeometry(w)
            val gone = MiniMotionData.SKIP.goneFrom("capsule")
            val born = MiniMotionData.SKIP.hiddenUntil("incoming")
            val out = miniPose(MiniMode.Skip, gone, g).outgoing
            val inc = miniPose(MiniMode.Skip, born, g).incoming!!
            assertEquals("x at $w", out.x, inc.x, 0.001f)
            assertEquals("y at $w", out.y, inc.y, 0.001f)
            assertEquals("width at $w", out.width, inc.width, 0.001f)
            assertEquals("height at $w", out.height, inc.height, 0.001f)
            assertEquals("radius at $w", out.radius, inc.radius, 0.001f)
            // That point is the skip button's centre.
            assertEquals(g.skipX + 24f, out.x + out.width / 2, 0.01f)
            assertEquals(g.buttonY + 24f, out.y + out.height / 2, 0.01f)
            // Skip completed (frame 03) starts from the same point.
            val again = miniPose(MiniMode.SkipCompleted, 0f, g).incoming!!
            assertEquals(out.x, again.x, 0.001f)
            assertEquals(out.y, again.y, 0.001f)
        }
    }

    @Test
    fun `replay absorbs into the replay button, and the song is born again exactly there`() {
        for (w in widths) {
            val g = MiniGeometry(w)
            val out = miniPose(MiniMode.Replay, MiniMotionData.REPLAY.goneFrom("capsule"), g).outgoing
            assertEquals(g.replayX + 24f, out.x + out.width / 2, 0.01f)
            assertEquals(g.buttonY + 24f, out.y + out.height / 2, 0.01f)
            val inc = miniPose(MiniMode.Replay, MiniMotionData.REPLAY.hiddenUntil("incoming"), g).incoming!!
            assertEquals(out.x, inc.x, 0.001f)
            assertEquals(out.y, inc.y, 0.001f)
            assertEquals(out.width, inc.width, 0.001f)
            assertEquals(out.height, inc.height, 0.001f)
            assertEquals(out.radius, inc.radius, 0.001f)
        }
    }

    @Test
    fun `automatic next goes into the skip button and the next song is born exactly there`() {
        for (w in widths) {
            val g = MiniGeometry(w)
            val tl = MiniMotionData.AUTO_NEXT
            val out = miniPose(MiniMode.AutoNext, tl.goneFrom("capsule"), g).outgoing
            val inc = miniPose(MiniMode.AutoNext, tl.hiddenUntil("incoming"), g).incoming!!
            assertEquals(g.skipX + 24f, out.x + out.width / 2, 0.01f)
            assertEquals(out.x, inc.x, 0.001f)
            assertEquals(out.y, inc.y, 0.001f)
            assertEquals(out.width, inc.width, 0.001f)
            assertEquals(out.height, inc.height, 0.001f)
        }
    }

    @Test
    fun `the preparing hold cycle repeats without a seam`() {
        val g = MiniGeometry(388f)
        val a = miniPose(MiniMode.AutoNext, AUTO_NEXT_HOLD_FROM_MS, g)
        val b = miniPose(MiniMode.AutoNext, AUTO_NEXT_HOLD_TO_MS, g)
        // Between the cycle's two ends: the ring, the core and the button are where they were.
        assertEquals(a.prepare!!.ringScale, b.prepare!!.ringScale, 0.01f)
        assertEquals(a.prepare!!.coreScale, b.prepare!!.coreScale, 0.02f)
        assertEquals(a.prepare!!.coreAlpha, b.prepare!!.coreAlpha, 0.02f)
        assertEquals(a.prepare!!.spinAlpha, b.prepare!!.spinAlpha, 0.001f)
        assertEquals(a.prepare!!.spinScale, b.prepare!!.spinScale, 0.001f)
        assertEquals(a.skip.scaleX, b.skip.scaleX, 0.01f)
        assertEquals(a.skip.background, b.skip.background)
        // And the orbit, carried over a cycle, turns on from where it was.
        val carried = miniPose(MiniMode.AutoNext, AUTO_NEXT_HOLD_FROM_MS, g, extraSpinDegrees = autoNextLoopSpinDegrees())
        assertEquals(b.prepare!!.spinDegrees, carried.prepare!!.spinDegrees, 0.01f)
        // Nothing of either song shows while it holds.
        for (t in listOf(AUTO_NEXT_HOLD_FROM_MS, AUTO_NEXT_HOLD_TO_MS)) {
            val p = miniPose(MiniMode.AutoNext, t, g)
            assertEquals(0f, p.outgoing.alpha, 0f)
            assertEquals(0f, p.incoming!!.alpha, 0f)
        }
    }

    @Test
    fun `at the handoff neither song is visible`() {
        for (mode in listOf(MiniMode.Skip, MiniMode.Replay)) {
            val t = handoffMs(mode)
            val pose = miniPose(mode, t, MiniGeometry(388f))
            assertEquals("$mode outgoing", 0f, pose.outgoing.alpha, 0f)
            assertEquals("$mode incoming", 0f, pose.incoming!!.alpha, 0f)
            assertTrue("$mode: the incoming song must not show before the handoff",
                timelineOf(mode)!!.hiddenUntil("incoming") >= t)
        }
        // Figma: gone at 38.514% of 1480 ms, and 42.3% of 1560 ms.
        assertEquals(570f, handoffMs(MiniMode.Skip), 0.1f)
        assertEquals(660f, handoffMs(MiniMode.Replay), 1f)
    }

    @Test
    fun `outgoing content fades before compression clips it`() {
        val tl = MiniMotionData.SKIP
        val textGone = maxOf(tl.goneFrom("title"), tl.goneFrom("artist"), tl.goneFrom("rail"))
        // By then the capsule is still wider than the text box starts (49 dp + some of the 163 dp box).
        val pose = miniPose(MiniMode.Skip, textGone, MiniGeometry(388f))
        assertTrue("capsule ${pose.outgoing.width} wide when the text is gone", pose.outgoing.width > 200f)
    }

    @Test
    fun `every timeline ends exactly at rest`() {
        for (w in widths) {
            val g = MiniGeometry(w)
            val rest = miniPose(MiniMode.Rest, 0f, g)
            for (mode in listOf(MiniMode.Skip, MiniMode.SkipCompleted, MiniMode.Replay, MiniMode.AutoNext)) {
                val end = miniPose(mode, timelineOf(mode)!!.durationMs, g)
                val settled = end.incoming!!
                val r = rest.outgoing
                assertEquals("$mode x", r.x, settled.x, 0.0001f)
                assertEquals("$mode y", r.y, settled.y, 0.0001f)
                assertEquals("$mode width", r.width, settled.width, 0.0001f)
                assertEquals("$mode height", r.height, settled.height, 0.0001f)
                assertEquals("$mode radius", r.radius, settled.radius, 0.0001f)
                assertEquals("$mode colour", r.background, settled.background)
                assertEquals(1f, settled.alpha, 0f)
                assertEquals(1f, settled.scaleX, 0f)
                assertEquals(1f, settled.scaleY, 0f)
                assertEquals("$mode glow", 0f, settled.glowAlpha, 0f)
                for (v in listOf(settled.artBlur, settled.titleBlur, settled.artistBlur, settled.artX, settled.titleX, settled.titleY, settled.artistX, settled.artistY, settled.railX, settled.railY)) {
                    assertEquals("$mode residual", 0f, v, 0f)
                }
                for (v in listOf(settled.artScale, settled.artAlpha, settled.titleAlpha, settled.artistAlpha, settled.railAlpha, settled.railScaleX, settled.railScaleY)) {
                    assertEquals("$mode residual", 1f, v, 0f)
                }
                end.prepare?.let { assertEquals("$mode preparing", 0f, it.spinAlpha + it.ringAlpha, 0f) }
                for (b in listOf(end.replay, end.skip)) {
                    assertEquals(0f, b.offsetX, 0f)
                    assertEquals(1f, b.scaleX, 0f)
                    assertEquals(1f, b.scaleY, 0f)
                    assertEquals(MiniColors.Button, b.background)
                    assertEquals(0f, b.iconX, 0f)
                    assertEquals(1f, b.iconAlpha, 0f)
                    // A full turn (Figma's -2π) is the resting orientation.
                    val turn = ((b.iconRotationDegrees % 360f) + 360f) % 360f
                    assertTrue("rotation ${b.iconRotationDegrees}", turn < 0.001f || turn > 359.999f)
                }
                assertEquals(1f, end.pauseAlpha, 0f)
                assertEquals(1f, end.pauseScale, 0f)
                assertEquals(0f, end.outlineAlpha + end.trailAlpha + end.bridgeAlpha, 0f)
            }
            val pulse = miniPose(MiniMode.ReplayCompleted, MiniMotionData.REPLAY_COMPLETED.durationMs, g)
            assertEquals(1f, pulse.outgoing.scaleX, 0f)
            assertEquals(1f, pulse.replay.scaleX, 0f)
        }
    }

    @Test
    fun `the reference width reproduces Figma's own numbers`() {
        val g = MiniGeometry(388f)
        val rest = miniPose(MiniMode.Rest, 0f, g).outgoing
        assertEquals(64f, rest.x, 0f)
        assertEquals(8f, rest.y, 0f)
        assertEquals(260f, rest.width, 0f)
        assertEquals(64f, rest.height, 0f)
        assertEquals(332f, g.skipX, 0f)
        assertEquals(8f, g.replayX, 0f)
        // Frame 02 at its 29.73% keyframe: translate 172, 12; 112 x 40; radius 20.
        val k = miniPose(MiniMode.Skip, 0.2973f * 1480f, g).outgoing
        assertEquals(64f + 172f, k.x, 0.05f)
        assertEquals(8f + 12f, k.y, 0.05f)
        assertEquals(112f, k.width, 0.1f)
        assertEquals(40f, k.height, 0.05f)
        assertEquals(20f, k.radius, 0.05f)
    }

    @Test
    fun `easing segments are applied where Figma puts them`() {
        // Frame 03, skip button scale X: 1.08 at 0%, 1.16 at 8.824% with cubic-bezier(0.25, 0.05, 0.2, 1) between.
        val tl = MiniMotionData.SKIP_COMPLETED
        val b = 0.08824f * 1020f
        val mid = tl.number("skipButton", Prop.ScaleX, b / 2, 1f)
        val eased = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.05f, 0.2f, 1f).transform(0.5f)
        assertEquals(1.08f + 0.08f * eased, mid, 0.0005f)
    }

    @Test
    fun `the drag follows the finger, then resists, and never reaches the absorption`() {
        val g = MiniGeometry(388f)
        for (mode in listOf(MiniMode.Skip, MiniMode.Replay)) {
            val small = MiniMotionState.resisted(mode, g, 4f, hasNext = true)
            assertEquals("$mode: one to one at first", 4f, small, 0.1f)
            var last = 0f
            for (d in 1..600) {
                val e = MiniMotionState.resisted(mode, g, d.toFloat(), hasNext = true)
                assertTrue(e > last)
                assertTrue(e - last <= 1.0001f)
                last = e
                val t = MiniMotionState.scrubTime(mode, g, e)
                assertTrue("$mode: drag time $t must stay before the handoff", t < handoffMs(mode))
                assertTrue(miniPose(mode, t, g).outgoing.alpha > 0.99f)
            }
            // The commit threshold is reachable with a normal swipe.
            assertTrue(MiniMotionState.fingerFor(mode, g, MiniMotionState.COMMIT_DP) < 120f)
        }
        // Nothing to skip to: it gives a little and can never commit.
        assertTrue(MiniMotionState.resisted(MiniMode.Skip, g, 1000f, hasNext = false) < MiniMotionState.COMMIT_DP)
    }

    @Test
    fun `the scrub's leading edge is where the finger put it`() {
        val g = MiniGeometry(388f)
        for (mode in listOf(MiniMode.Skip, MiniMode.Replay)) {
            for (edge in listOf(10f, 30f, 56f, 90f)) {
                val t = MiniMotionState.scrubTime(mode, g, edge)
                assertEquals("$mode at $edge", edge, Scrub.edge(mode, g, t), 0.6f)
            }
        }
    }
}
