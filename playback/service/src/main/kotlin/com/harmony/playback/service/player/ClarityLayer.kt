package com.harmony.playback.service.player

import com.harmony.core.model.ClarityBands
import com.harmony.core.model.ClarityFilterBank
import com.harmony.core.model.ClarityModel
import com.harmony.core.model.ClarityReadout
import com.harmony.core.model.ClaritySettings
import com.harmony.core.model.RealFft
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Clarity inside the audio chain, after everything else the equalizer does.
 *
 * It hears what is about to come out: the last [fftSize] samples (about
 * 43 ms) mixed to mono, a new short-time spectrum every quarter of that
 * (about 94 a second), summed into [ClarityBands] and handed to the hearing
 * model ([ClarityModel]). The model's curve is fitted to the 24 peaking
 * filters ([ClarityFilterBank.fit]).
 *
 * The filters follow the curve every [SUB_BLOCK] frames (about 750 times a
 * second): quickly when a band has to move further from flat ([ATTACK_S]),
 * slowly on the way back ([RELEASE_S]), so changes are heard as the music
 * and not as pumping. Coefficients change between frames and the filters
 * keep their state, so nothing clicks. The level follows the model's
 * loudness matching slowly ([MATCH_S]), with Boost on top.
 *
 * Single-threaded: everything here runs on the audio thread.
 */
internal class ClarityLayer(val sampleRate: Int, val channels: Int) {
    private val n = ClarityBands.COUNT
    val fftSize = if (sampleRate > 50_000) 4096 else 2048
    private val hop = fftSize / 4
    private val window = RealFft.hann(fftSize)
    private val fft = RealFft(fftSize)
    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var sinceHop = 0
    private var filled = 0
    private val frame = FloatArray(fftSize)
    private val spectrum = FloatArray(fftSize / 2 + 1)
    private val bandPower = FloatArray(n)

    // Which FFT bins make up each band. A band narrower than a bin takes the nearest one.
    private val binFrom = IntArray(n)
    private val binTo = IntArray(n)

    // Turns summed |X|² into mean power: a full-scale sine reads 0.5 in its band.
    private val powerScale = (2.0 / (fftSize * window.sumOf { (it * it).toDouble() })).toFloat()

    private val model = ClarityModel()
    private val bank = ClarityFilterBank(sampleRate)
    private val fitted = FloatArray(n)

    /** Filter gains playing now. */
    val appliedDb = FloatArray(n)
    private val b0 = FloatArray(n) { 1f }
    private val b1 = FloatArray(n)
    private val b2 = FloatArray(n)
    private val a1 = FloatArray(n)
    private val a2 = FloatArray(n)
    private val coef = FloatArray(5)
    private val state = Array(channels) { FloatArray(n * 2) }
    private var frameInSub = 0

    private val subSeconds = SUB_BLOCK.toFloat() / sampleRate
    private val attack = 1f - exp(-subSeconds / ATTACK_S)
    private val release = 1f - exp(-subSeconds / RELEASE_S)
    private val matchFollow = 1f - exp(-subSeconds / MATCH_S)
    private val levelFollow = 1f - exp(-subSeconds / LEVEL_S)
    private var matchDb = 0f
    private var outputDb = 0f
    private var outputGain = 1f

    /** Re-read by the processor before each buffer. */
    var settings = ClaritySettings()

    init {
        val binHz = sampleRate.toFloat() / fftSize
        val last = fftSize / 2
        for (k in 0 until n) {
            val lo = (ClarityBands.EDGES_HZ[k] / binHz).let { kotlin.math.ceil(it).toInt() }.coerceIn(1, last)
            val hi = (ClarityBands.EDGES_HZ[k + 1] / binHz).let { kotlin.math.ceil(it).toInt() }.coerceIn(1, last + 1)
            if (hi > lo) {
                binFrom[k] = lo; binTo[k] = hi
            } else {
                val nearest = (ClarityBands.CENTERS_HZ[k] / binHz).toInt().coerceIn(1, last)
                binFrom[k] = nearest; binTo[k] = nearest + 1
            }
        }
    }

    /** A new song: forget the last one's balance. The filters glide on from where they are. */
    fun startTrack() = model.reset()

    /** One frame, mixed to mono, as it reaches Clarity. Call once per frame after [process] has run for it. */
    fun analyze(mono: Float) {
        ring[ringPos] = mono
        ringPos = if (ringPos == fftSize - 1) 0 else ringPos + 1
        if (filled < fftSize) filled++
        if (++sinceHop >= hop) {
            sinceHop = 0
            if (filled == fftSize) listen()
        }
        if (++frameInSub >= SUB_BLOCK) {
            frameInSub = 0
            glide()
        }
    }

    fun process(x: Float, ch: Int): Float {
        var y = x
        val s = state[ch]
        for (k in 0 until n) {
            if (appliedDb[k] == 0f) continue
            val i = k * 2
            val out = b0[k] * y + s[i]
            s[i] = b1[k] * y - a1[k] * out + s[i + 1]
            s[i + 1] = b2[k] * y - a2[k] * out
            y = out
        }
        return y * outputGain
    }

    private fun listen() {
        // Oldest sample first, windowed.
        var r = ringPos
        for (i in 0 until fftSize) {
            frame[i] = ring[r] * window[i]
            r = if (r == fftSize - 1) 0 else r + 1
        }
        fft.power(frame, spectrum)
        for (k in 0 until n) {
            var sum = 0f
            for (b in binFrom[k] until binTo[k]) sum += spectrum[b]
            bandPower[k] = sum * powerScale
        }
        model.analyze(bandPower, hop.toFloat() / sampleRate, settings)
        bank.fit(model.targetDb, fitted)
    }

    private fun glide() {
        for (k in 0 until n) {
            val target = fitted[k]
            val now = appliedDb[k]
            if (target == now) continue
            val rate = if (abs(target) > abs(now)) attack else release
            var next = now + (target - now) * rate
            if (abs(next) < 0.01f && abs(target) < 0.01f) next = 0f
            if (next == 0f) {
                state.forEach { it[k * 2] = 0f; it[k * 2 + 1] = 0f }
            }
            appliedDb[k] = next
            bank.coefficients(k, next, coef)
            b0[k] = coef[0]; b1[k] = coef[1]; b2[k] = coef[2]; a1[k] = coef[3]; a2[k] = coef[4]
        }
        matchDb += (model.matchDb - matchDb) * matchFollow
        val wanted = matchDb + settings.boostDb.coerceIn(-ClaritySettings.MAX_BOOST_DB, ClaritySettings.MAX_BOOST_DB)
        if (wanted != outputDb) {
            outputDb += (wanted - outputDb) * levelFollow
            outputGain = 10f.pow(outputDb / 20f)
        }
    }

    /** What the model hears and the curve playing now, for the screen. */
    fun readout(): ClarityReadout = ClarityReadout(
        levelDb = model.levelDb.toList(),
        maskDb = model.maskDb.toList(),
        recoverDb = model.recoverDb.toList(),
        tameDb = model.tameDb.toList(),
        gainDb = playingDb().toList(),
        outputDb = outputDb,
        silent = model.silent,
    )

    /** The curve playing now on the equalizer's ten octaves, dB. */
    fun octavesDb(): FloatArray = ClarityBands.toOctaves(playingDb())

    private fun playingDb(): FloatArray = FloatArray(n).also { bank.atCenters(appliedDb, it) }

    companion object {
        /** Frames between filter updates: about 750 a second at 48 kHz. */
        const val SUB_BLOCK = 64
        const val ATTACK_S = 0.04f
        const val RELEASE_S = 0.25f
        const val MATCH_S = 1.5f
        const val LEVEL_S = 0.05f
    }
}
