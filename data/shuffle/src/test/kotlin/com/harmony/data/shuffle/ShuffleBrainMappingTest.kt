package com.harmony.data.shuffle

import com.harmony.domain.shuffle.model.ShuffleConfig
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ShuffleBrainMappingTest {
    @Test
    fun coldStartVectorFollowsTheBandSplit() {
        val bassy = ColdStartVectors.fromAnalysis(0.7f, 0.25f, 0.05f, rms = 0.2f, peak = 0.9f, bpm = 128f, beatConfidence = 0.8f)
        val bright = ColdStartVectors.fromAnalysis(0.1f, 0.4f, 0.5f, rms = 0.2f, peak = 0.4f, bpm = 90f, beatConfidence = 0.1f)
        assertEquals(24, bassy.size)
        val norm = sqrt((0 until 10).sumOf { (bassy[it] * bassy[it]).toDouble() })
        assertEquals(1.0, norm, 1e-5)
        assertTrue(bassy[0] > bassy[9] && bright[9] > bright[0])
        assertTrue("centroid darker for the bass song", bassy[21] < bright[21])
        assertTrue("dynamics dims unknown", (10 until 21).all { bassy[it].isNaN() })
        assertTrue("crest from peak/rms", bassy[22] > bright[22] && bassy[22] in 0f..1f)
        assertEquals("128 bpm: 2.13 beats/s", 2.1333f / 5.1333f, bassy[23], 1e-3f)
        assertTrue("an unsure tempo stays unknown", bright[23].isNaN())
    }

    @Test
    fun coldStartWithNothingIsAllUnknown() {
        val nothing = ColdStartVectors.fromAnalysis(0f, 0f, 0f, rms = 0f, peak = 0f, bpm = null, beatConfidence = null)
        assertTrue(nothing.all { it.isNaN() })
    }

    @Test
    fun slidersMoveTheWeights() {
        val familiar = BrainWeights.forConfig(ShuffleConfig(familiarity = 1f, discovery = 0f, variety = 0f))
        val discover = BrainWeights.forConfig(ShuffleConfig(familiarity = 0f, discovery = 1f, variety = 1f))
        assertTrue(familiar.affinity > discover.affinity && familiar.markov > discover.markov)
        assertTrue(discover.explore > familiar.explore)
        assertTrue(discover.temperature > familiar.temperature)
        assertEquals(1.05f, BrainWeights.forConfig(ShuffleConfig(style = SmartShuffleStyle.FLOW)).jerkThreshold)
        assertEquals(1.25f, BrainWeights.forConfig(ShuffleConfig(style = SmartShuffleStyle.BALANCED)).jerkThreshold)
        assertEquals(3, familiar.minArtistSpacing)
    }
}
