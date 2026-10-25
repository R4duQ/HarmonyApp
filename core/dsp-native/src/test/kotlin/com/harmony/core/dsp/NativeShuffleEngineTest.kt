package com.harmony.core.dsp

import com.harmony.core.model.AuditCheckKind
import com.harmony.core.model.AuditStatus
import com.harmony.core.model.SessionEvent
import com.harmony.core.model.VectorQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Kotlin side of the JNI bridge against the real engine. Needs the host
 * build of libharmonyshuffle (core/dsp-native/src/test/cpp; CI points
 * HARMONY_SHUFFLE_HOST_LIB_DIR at it); skipped without it.
 */
class NativeShuffleEngineTest {
    @Before
    fun needsTheLibrary() = assumeTrue("host libharmonyshuffle not built", SmartShuffleNative.available)

    private fun library(engine: NativeShuffleEngine, n: Int = 60) {
        val random = Random(4)
        val ids = LongArray(n) { 10L + it }
        val raw = FloatArray(n * 24) { i -> ((i / 24) / 20 + 1) * random.nextFloat() }
        val quality = ByteArray(n) { VectorQuality.REAL.ordinal.toByte() }
        assertEquals(n, engine.setLibrary(ids, raw, quality, IntArray(n) { it % 9 }, IntArray(n) { -1 }))
    }

    @Test
    fun queueTracesAndAuditCrossTheBridge() = NativeShuffleEngine().use { engine ->
        library(engine)
        engine.setWeights(ShuffleWeights())
        assertEquals(SessionEvent.SEEDED, engine.onTrackStarted(10))
        assertEquals(SessionEvent.PLAY_EMA, engine.onPlaybackOutcome(10, 0.9f, 1_000L, shufflePick = false))
        val queue = engine.generateQueue(10, LongArray(0), longArrayOf(10), LongArray(0), 8, 1_000L, 7)
        assertEquals(8, queue.size)
        assertTrue(queue.none { it == 10L })

        val traces = engine.lastTraces()
        assertEquals(queue.toList(), traces.map { it.trackId })
        traces.forEach {
            val sum = it.vibeSimilarityPct + it.markovHabitPct + it.userAffinityPct + it.explorationBonusPct
            assertEquals(100f, sum, 0.5f)
            assertTrue(it.primaryReasonTag.isNotBlank())
        }

        val audit = engine.lastAudit()
        assertEquals(AuditCheckKind.entries, audit.checks.map { it.kind })
        assertTrue(audit.checks.all { it.detail.isNotBlank() })
        assertEquals(1f, audit.sessionNorm, 1e-3f)
        assertEquals(100f, audit.realVectorPct, 1e-3f)
        assertEquals(60, audit.realTracks)
        assertTrue(audit.generation >= 1)

        val s = engine.sessionVector()
        assertNotNull(s)
        s!!
        assertEquals(1f, sqrt(s.sumOf { (it * it).toDouble() }).toFloat(), 1e-3f)
    }

    @Test
    fun fastSkipIsAuditedAfterTheReRank() = NativeShuffleEngine().use { engine ->
        library(engine)
        engine.onTrackStarted(10)
        engine.onPlaybackOutcome(10, 1f, 1_000L, false)
        val first = engine.generateQueue(10, LongArray(0), longArrayOf(10), LongArray(0), 8, 1_000L, 1)
        engine.onTrackStarted(first[0])
        assertEquals(SessionEvent.FAST_SKIP, engine.onPlaybackOutcome(first[0], 0.05f, 2_000L, true))
        engine.onTrackStarted(first[1])
        engine.generateQueue(first[1], LongArray(0), longArrayOf(10, first[0], first[1]), first.copyOfRange(2, 8), 8, 2_000L, 2)
        val audit = engine.lastAudit()
        assertTrue(audit.afterFastSkip)
        val skip = audit.checks.first { it.kind == AuditCheckKind.SKIP_REPULSION }
        assertTrue(skip.detail, skip.status != AuditStatus.FAILED)
        val bandit = engine.lastBanditUpdate()
        assertNotNull(bandit)
        assertEquals("a fast skip rewards nothing", 0f, bandit!!.third)
    }

    @Test
    fun habitsAndBanditRoundTrip() = NativeShuffleEngine().use { engine ->
        library(engine)
        engine.addTransitions(longArrayOf(10, 10), longArrayOf(20, 30), floatArrayOf(4f, 1f))
        assertTrue(engine.markovProbability(10, 20) > engine.markovProbability(10, 30))
        engine.clearTransitions()
        assertEquals(0f, engine.markovProbability(10, 20))

        val pulls = FloatArray(100) { it.toFloat() }
        val rewards = FloatArray(100) { it / 2f }
        engine.setZoneBandit(pulls, rewards)
        val (p, r) = engine.zoneBandit()
        assertTrue(p.contentEquals(pulls) && r.contentEquals(rewards))

        engine.onTrackStarted(11)
        engine.onPlaybackOutcome(11, 1f, 1L, false)
        engine.onTrackStarted(12)
        engine.onPlaybackOutcome(12, 1f, 2L, false)
        assertEquals(Triple(11L, 12L, 1f), engine.lastLearnedTransition())
        engine.onPlaybackOutcome(12, 0.3f, 3L, false)
        assertNull(engine.lastLearnedTransition())
    }

    @Test
    fun journeyStepsAndSelfTest() = NativeShuffleEngine().use { engine ->
        library(engine)
        val step = engine.journeyStep(10, 60, 0.5f, 10, longArrayOf(10), 1L, 3)
        assertNotNull(step)
        assertEquals(1, engine.lastTraces().size)
        val report = NativeShuffleEngine.runSyntheticBrainSimulation()
        assertTrue(report, report.contains("RESULT: PASS"))
    }

    @Test
    fun badArraysThrowInsteadOfCrashing() = NativeShuffleEngine().use { engine ->
        val thrown = runCatching { engine.setLibrary(LongArray(3), FloatArray(5), ByteArray(3), IntArray(3), IntArray(3)) }
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun extractorMeasuresAKick() {
        NativeAcousticExtractor(44_100).use { x ->
            val chunk = FloatArray(4410)
            var t = 0
            repeat(60) {
                for (i in chunk.indices) {
                    val s = (t + i) / 44_100.0
                    val phase = s % 0.5
                    chunk[i] = (0.8 * kotlin.math.exp(-phase / 0.15) * sin(2 * PI * 55 * phase)).toFloat()
                }
                t += chunk.size
                x.process(chunk)
            }
            val measured = x.finish()
            assertNotNull(measured)
            val features = measured!!
            assertEquals(24, features.size)
            val peak = (0 until 10).maxBy { features[it] }
            assertTrue("kick peaks in the bass, band $peak", peak <= 1)
            assertTrue(features.all { it.isFinite() && it in 0f..1f })
        }
        NativeAcousticExtractor(44_100).use { assertNull(it.finish()) }
        assertTrue(runCatching { NativeAcousticExtractor(100) }.isFailure)
    }
}
