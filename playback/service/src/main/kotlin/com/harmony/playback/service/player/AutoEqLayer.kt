package com.harmony.playback.service.player

import com.harmony.core.model.AutoEqDesign
import com.harmony.core.model.EqSettings
import com.harmony.core.model.OctaveBandMeter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * The automatic equalizer inside the audio chain: ten gentle peaking filters
 * at the equalizer's octaves, after the listener's own settings, whose gains
 * are the sum of the three automatic parts (see AutoEqDesign).
 *
 * It also listens to the song for the tone part. Every 100 ms the input's
 * octave-band power is added to a running average for the song (quiet
 * passages and silence don't count), and the tone correction is worked out
 * from that average, fading in over the first [AutoEqDesign.TONE_SETTLE_SECONDS]
 * so a song doesn't change colour as it starts. A song heard before starts
 * from what was learned about it last time ([ToneMemory]).
 *
 * Gains never jump: each 100 ms they move at most [STEP_DB] towards their
 * target, and the filters keep their state as their coefficients change,
 * so nothing clicks.
 *
 * Single-threaded: everything here runs on the audio thread.
 */
internal class AutoEqLayer(val sampleRate: Int, val channels: Int, private val memory: ToneMemory) {
    private val bands = EqSettings.BAND_COUNT
    private val meter = OctaveBandMeter(sampleRate)
    private val blockFrames = (sampleRate / 10).coerceAtLeast(1)
    private var frameInBlock = 0
    private var blocks = 0L

    private val songPower = DoubleArray(bands)
    private var songBlocks = 0
    private var trackId: String? = null
    private var remembered: FloatArray? = null

    private val b0 = FloatArray(bands) { 1f }
    private val b1 = FloatArray(bands)
    private val b2 = FloatArray(bands)
    private val a1 = FloatArray(bands)
    private val a2 = FloatArray(bands)
    private val state = Array(bands) { Array(channels) { FloatArray(2) } }

    /** Gains the filters are set to now, and where they are heading. */
    val appliedDb = FloatArray(bands)
    private val targetDb = FloatArray(bands)

    /** The current song's tone correction, already faded in. */
    val toneDb = FloatArray(bands)

    /** Largest boost in play, which the processor's preamp makes room for. */
    var headroomDb = 0f
        private set

    /** Seconds of the current song that counted towards its average. */
    val heardSeconds: Float get() = songBlocks / 10f

    /** Inputs, re-read by the processor before each buffer. */
    var toneEnabled = false
    var roomDb: FloatArray = FloatArray(bands)
    var noiseDb: FloatArray = FloatArray(bands)

    /** A new song is starting: remember the last one and start listening afresh. */
    fun startTrack(id: String?) {
        val previous = trackId
        if (previous != null && heardSeconds >= REMEMBER_AFTER_SECONDS) memory.put(previous, songLevels())
        trackId = id
        remembered = id?.let(memory::get)
        songPower.fill(0.0)
        songBlocks = 0
        meter.reset()
        frameInBlock = 0
    }

    /** One frame of the input, mixed to mono, before any equalizing. */
    fun analyze(mono: Float) {
        if (toneEnabled) meter.add(mono)
        if (++frameInBlock >= blockFrames) {
            frameInBlock = 0
            endBlock()
        }
    }

    fun process(x: Float, ch: Int): Float {
        var y = x
        for (i in 0 until bands) {
            if (appliedDb[i] == 0f) continue
            val s = state[i][ch]
            val out = b0[i] * y + s[0]
            s[0] = b1[i] * y - a1[i] * out + s[1]
            s[1] = b2[i] * y - a2[i] * out
            y = out
        }
        return y
    }

    private fun endBlock() {
        blocks++
        if (toneEnabled && meter.count > 0) {
            val p = meter.powers()
            meter.reset()
            if (p.sum() > SILENCE_POWER) {
                for (i in 0 until bands) songPower[i] += p[i]
                songBlocks++
            }
        }
        if (blocks % TONE_EVERY_BLOCKS == 0L) updateTone()
        for (i in 0 until bands) {
            targetDb[i] = (toneDb[i] + roomDb.getOrElse(i) { 0f } + noiseDb.getOrElse(i) { 0f })
                .coerceIn(-AutoEqDesign.TOTAL_MAX_CUT_DB, AutoEqDesign.TOTAL_MAX_BOOST_DB)
        }
        glide()
    }

    private fun songLevels(): FloatArray = FloatArray(bands) { i ->
        val p = songPower[i] / songBlocks.coerceAtLeast(1)
        if (p > 0) (10 * log10(p)).toFloat() else Float.NEGATIVE_INFINITY
    }

    private fun updateTone() {
        if (!toneEnabled) {
            toneDb.fill(0f)
            return
        }
        val seconds = heardSeconds
        val known = remembered
        val (levels, weight) = when {
            seconds >= AutoEqDesign.TONE_SETTLE_SECONDS || (known == null && songBlocks > 0) ->
                songLevels() to (seconds / AutoEqDesign.TONE_SETTLE_SECONDS).coerceAtMost(1f)
            known != null -> known to 1f
            else -> null to 0f
        }
        if (levels == null) {
            toneDb.fill(0f)
            return
        }
        val c = AutoEqDesign.toneCorrection(levels)
        for (i in 0 until bands) toneDb[i] = c[i] * weight
    }

    private fun glide() {
        var maxBoost = 0f
        for (i in 0 until bands) {
            val delta = targetDb[i] - appliedDb[i]
            if (delta != 0f) {
                var next = appliedDb[i] + delta.coerceIn(-STEP_DB, STEP_DB)
                if (abs(next) < 0.01f && abs(targetDb[i]) < 0.01f) next = 0f
                if (next == 0f && appliedDb[i] != 0f) state[i].forEach { it.fill(0f) }
                appliedDb[i] = next
                setPeaking(i, next)
            }
            maxBoost = maxOf(maxBoost, appliedDb[i])
        }
        headroomDb = maxBoost
    }

    /** RBJ peaking filter, the same shape the manual bands use. */
    private fun setPeaking(i: Int, gainDb: Float) {
        val fc = EqSettings.BAND_CENTERS_HZ[i]
        if (gainDb == 0f || fc >= sampleRate * 0.45f) {
            b0[i] = 1f; b1[i] = 0f; b2[i] = 0f; a1[i] = 0f; a2[i] = 0f
            return
        }
        val a = 10f.pow(gainDb / 40f)
        val w = 2f * Math.PI.toFloat() * fc / sampleRate
        val alpha = sin(w) / (2f * Q)
        val a0 = 1 + alpha / a
        b0[i] = (1 + alpha * a) / a0
        b1[i] = (-2 * cos(w)) / a0
        b2[i] = (1 - alpha * a) / a0
        a1[i] = (-2 * cos(w)) / a0
        a2[i] = (1 - alpha / a) / a0
    }

    companion object {
        /** Most a gain moves per 100 ms: 3 dB a second, too slow to hear as a change. */
        const val STEP_DB = 0.3f
        const val Q = 1.1f
        /** Tone is re-worked every half second. */
        const val TONE_EVERY_BLOCKS = 5L
        /** Below about −60 dBFS a block is silence or a fade, not the song's sound. */
        const val SILENCE_POWER = 1e-6
        /** A song heard at least this long is remembered for next time. */
        const val REMEMBER_AFTER_SECONDS = 20f
    }
}

/** What each recently played song's spectrum turned out to be, so its tone is right from the first second next time. */
class ToneMemory(private val capacity: Int = 2_000) {
    private val map = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > capacity
    }

    @Synchronized fun get(id: String): FloatArray? = map[id]?.copyOf()

    @Synchronized fun put(id: String, levelsDb: FloatArray) {
        // Bands the sample rate can't carry are NaN or silent; a song with nothing usable isn't worth keeping.
        if (levelsDb.none { !it.isNaN() && !it.isInfinite() }) return
        map[id] = levelsDb.copyOf()
    }

    companion object {
        /** One for the process: the main and crossfade players share what they learn. */
        val shared = ToneMemory()
    }
}
