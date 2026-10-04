package com.harmony.playback.service.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffAlignmentTest {
    @Test fun `players within a few milliseconds are left alone`() {
        assertNull(HandoffAlignment.catchUpFactor(0))
        assertNull(HandoffAlignment.catchUpFactor(12))
        assertNull(HandoffAlignment.catchUpFactor(-12))
    }

    @Test fun `a player behind runs faster, one ahead slower, never past the limit`() {
        assertEquals(1.2f, HandoffAlignment.catchUpFactor(-100)!!, 0.0001f)
        assertEquals(0.8f, HandoffAlignment.catchUpFactor(100)!!, 0.0001f)
        assertEquals(1f + HandoffAlignment.MAX_NUDGE, HandoffAlignment.catchUpFactor(-600)!!, 0.0001f)
        assertEquals(1f - HandoffAlignment.MAX_NUDGE, HandoffAlignment.catchUpFactor(600)!!, 0.0001f)
        // Gentler as the gap closes, so it settles rather than overshooting.
        assertTrue(HandoffAlignment.catchUpFactor(-20)!! < HandoffAlignment.catchUpFactor(-80)!!)
    }

    @Test fun `the aim learns half the error each time and stays in range`() {
        assertEquals(300L, HandoffAlignment.nextLead(250, offsetMs = -100))  // landed behind: aim further ahead
        assertEquals(200L, HandoffAlignment.nextLead(250, offsetMs = 100))   // landed ahead: aim less far
        assertEquals(HandoffAlignment.MAX_LEAD_MS, HandoffAlignment.nextLead(1_400, offsetMs = -900))
        assertEquals(HandoffAlignment.MIN_LEAD_MS, HandoffAlignment.nextLead(60, offsetMs = 500))
    }

    @Test fun `median ignores one stray reading`() {
        assertEquals(10L, HandoffAlignment.median(10, 90, 8))
        assertEquals(-5L, HandoffAlignment.median(-5, -5, 200))
        assertEquals(3L, HandoffAlignment.median(3, 1, 5))
    }

    @Test fun `the swap never adds up to more than the song itself`() {
        // Both players carry the same song, so their gains add as amplitudes.
        for (i in 0..10) {
            val (incoming, outgoing) = HandoffAlignment.swapGains(i / 10f)
            assertEquals(1f, incoming + outgoing, 0.0001f)
        }
        assertEquals(0f, HandoffAlignment.swapGains(0f).first, 0.0001f)
        assertEquals(1f, HandoffAlignment.swapGains(1f).first, 0.0001f)
    }
}
