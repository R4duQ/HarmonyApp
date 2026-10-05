package com.harmony.desktop.engine

import com.harmony.core.model.RealFft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bass and treble: an RBJ low shelf and high shelf. Pure maths, so the
 * screen can draw the same curve the sound gets.
 */
object ToneDesign {
    const val MAX_DB = 12f
    const val BASS_HZ = 105.0
    const val TREBLE_HZ = 7_500.0
    private const val SLOPE = 0.8

    /** b0, b1, b2, a1, a2 (a0 divided out). */
    fun lowShelf(gainDb: Float, sampleRate: Int): DoubleArray = shelf(gainDb, BASS_HZ, sampleRate, low = true)

    fun highShelf(gainDb: Float, sampleRate: Int): DoubleArray = shelf(gainDb, TREBLE_HZ, sampleRate, low = false)

    private fun shelf(gainDb: Float, freq: Double, sampleRate: Int, low: Boolean): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / SLOPE - 1) + 2)
        val sa = 2 * sqrt(a) * alpha
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        if (low) {
            b0 = a * ((a + 1) - (a - 1) * cw + sa)
            b1 = 2 * a * ((a - 1) - (a + 1) * cw)
            b2 = a * ((a + 1) - (a - 1) * cw - sa)
            a0 = (a + 1) + (a - 1) * cw + sa
            a1 = -2 * ((a - 1) + (a + 1) * cw)
            a2 = (a + 1) + (a - 1) * cw - sa
        } else {
            b0 = a * ((a + 1) + (a - 1) * cw + sa)
            b1 = -2 * a * ((a - 1) + (a + 1) * cw)
            b2 = a * ((a + 1) + (a - 1) * cw - sa)
            a0 = (a + 1) - (a - 1) * cw + sa
            a1 = 2 * ((a - 1) - (a + 1) * cw)
            a2 = (a + 1) - (a - 1) * cw - sa
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** What bass [bassDb] and treble [trebleDb] do at [hz], in dB. */
    fun responseDb(bassDb: Float, trebleDb: Float, hz: Double, sampleRate: Int = 48_000): Float =
        (magnitudeDb(lowShelf(bassDb, sampleRate), hz, sampleRate) + magnitudeDb(highShelf(trebleDb, sampleRate), hz, sampleRate)).toFloat()

    private fun magnitudeDb(c: DoubleArray, hz: Double, sampleRate: Int): Double {
        val w = 2 * PI * hz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c[0] + c[1] * c1 + c[2] * c2
        val ni = -(c[1] * s1 + c[2] * s2)
        val dr = 1 + c[3] * c1 + c[4] * c2
        val di = -(c[3] * s1 + c[4] * s2)
        return 20 * log10(hypot(nr, ni) / hypot(dr, di))
    }
}

/**
 * Bass, treble and stereo width on the audio thread. Each setting moves to
 * its new value in small steps, so turning a knob mid-song never clicks.
 */
internal class ToneStage(private val sampleRate: Int) {
    @Volatile var bassDb = 0f
    @Volatile var trebleDb = 0f
    /** 1 = as recorded, 0 = mono, 2 = twice as wide. */
    @Volatile var width = 1f

    private var curBass = 0f
    private var curTreble = 0f
    private var curWidth = 1f
    private var low = ToneDesign.lowShelf(0f, sampleRate)
    private var high = ToneDesign.highShelf(0f, sampleRate)
    private val lowState = Array(2) { DoubleArray(4) }
    private val highState = Array(2) { DoubleArray(4) }
    private var countdown = 0

    /** True when, [active] or not, it would change nothing, so it can be skipped. */
    fun idle(active: Boolean): Boolean =
        curBass == 0f && curTreble == 0f && curWidth == 1f && (!active || (bassDb == 0f && trebleDb == 0f && width == 1f))

    /** Processes one stereo frame in place in [lr] (left, right); when not [active] it eases back to neutral. */
    fun process(lr: FloatArray, active: Boolean) {
        if (--countdown <= 0) {
            countdown = STEP_FRAMES
            curBass = approach(curBass, if (active) bassDb.coerceIn(-ToneDesign.MAX_DB, ToneDesign.MAX_DB) else 0f, 0.25f)
            curTreble = approach(curTreble, if (active) trebleDb.coerceIn(-ToneDesign.MAX_DB, ToneDesign.MAX_DB) else 0f, 0.25f)
            curWidth = approach(curWidth, if (active) width.coerceIn(0f, 2f) else 1f, 0.02f)
            low = ToneDesign.lowShelf(curBass, sampleRate)
            high = ToneDesign.highShelf(curTreble, sampleRate)
        }
        for (ch in 0..1) {
            var x = lr[ch].toDouble()
            if (curBass != 0f) x = biquad(low, lowState[ch], x)
            if (curTreble != 0f) x = biquad(high, highState[ch], x)
            lr[ch] = x.toFloat()
        }
        if (curWidth != 1f) {
            val mid = (lr[0] + lr[1]) * 0.5f
            val side = (lr[0] - lr[1]) * 0.5f * curWidth
            lr[0] = mid + side
            lr[1] = mid - side
        }
    }

    private fun biquad(c: DoubleArray, z: DoubleArray, x: Double): Double {
        val y = c[0] * x + c[1] * z[0] + c[2] * z[1] - c[3] * z[2] - c[4] * z[3]
        z[1] = z[0]; z[0] = x
        z[3] = z[2]; z[2] = if (abs(y) < 1e-20) 0.0 else y
        return y
    }

    private fun approach(from: Float, to: Float, step: Float): Float =
        if (abs(to - from) <= step) to else from + if (to > from) step else -step

    private companion object {
        /** Settings move every 64 frames (~1.3 ms). */
        const val STEP_FRAMES = 64
    }
}

/**
 * Even volume: brings quiet songs up and loud ones down towards the same
 * loudness, slowly, so it never pumps with the beat.
 */
internal class Leveler(private val sampleRate: Int) {
    private var power = 0.0
    private var levelDb = TARGET_DB.toDouble()
    private var gainDb = 0.0
    private var warm = false

    /** The gain to apply now, as a factor; fed with each frame's mono sample. */
    fun next(mono: Float): Float {
        power += (mono.toDouble() * mono - power) * POWER_EASE
        val db = 10 * log10(power + 1e-12)
        // Silence and the quiet ends of songs don't count.
        if (db > GATE_DB) {
            if (!warm) { levelDb = db; warm = true }
            levelDb += (db - levelDb) * LEVEL_EASE
        }
        val want = (TARGET_DB - levelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val maxStep = SLEW_DB_PER_S / sampleRate
        gainDb += (want - gainDb).coerceIn(-maxStep, maxStep)
        return 10.0.pow(gainDb / 20).toFloat()
    }

    val currentGainDb: Float get() = gainDb.toFloat()

    private companion object {
        const val TARGET_DB = -17f
        const val GATE_DB = -45.0
        const val MIN_GAIN_DB = -8.0
        const val MAX_GAIN_DB = 6.0
        const val SLEW_DB_PER_S = 1.5
        /** ~0.4 s for the short-term power, ~4 s for the level. */
        val POWER_EASE = 1 - exp(-1.0 / (0.4 * 48_000))
        val LEVEL_EASE = 1 - exp(-1.0 / (4.0 * 48_000))
    }
}

/** Two spectra: the music as it comes in, and as it goes out after the equalizer. */
class SpectrumFrame(val before: FloatArray, val after: FloatArray)

/**
 * The analyzer behind the equalizer: [BANDS] bars from 30 Hz to 18 kHz, in
 * dB (0 = a full-scale tone), worked out every ~21 ms and kept a little
 * while, so the screen can show what is being heard, not what was decoded.
 */
internal class SpectrumTap(private val sampleRate: Int) {
    private val fft = RealFft(SIZE)
    private val window = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }
    private val inBuf = FloatArray(SIZE)
    private val outBuf = FloatArray(SIZE)
    private var filled = 0
    private val frame = FloatArray(SIZE)
    private val power = FloatArray(SIZE / 2 + 1)
    private val edges = IntArray(BANDS + 1) { i ->
        val hz = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(i.toDouble() / BANDS)
        (hz * SIZE / sampleRate).toInt().coerceIn(1, SIZE / 2)
    }

    private class Entry(val at: Long, val frame: SpectrumFrame)
    private val ring = arrayOfNulls<Entry>(RING)
    private var head = 0

    /** Takes one frame's mono sample before and after the sound shaping; [at] counts frames written. */
    fun push(before: Float, after: Float, at: Long) {
        inBuf[filled] = before
        outBuf[filled] = after
        if (++filled == SIZE) {
            val f = SpectrumFrame(bands(inBuf), bands(outBuf))
            synchronized(ring) {
                ring[head] = Entry(at, f)
                head = (head + 1) % RING
            }
            // Half overlapping: keep the newer half.
            inBuf.copyInto(inBuf, 0, HOP, SIZE)
            outBuf.copyInto(outBuf, 0, HOP, SIZE)
            filled = SIZE - HOP
        }
    }

    /** The newest spectrum at or before frame [at], or null. */
    fun at(at: Long): SpectrumFrame? = synchronized(ring) {
        var best: Entry? = null
        for (e in ring) if (e != null && e.at <= at && (best == null || e.at > best.at)) best = e
        best?.frame
    }

    fun clear() = synchronized(ring) {
        ring.fill(null)
        filled = 0
    }

    private fun bands(samples: FloatArray): FloatArray {
        for (i in 0 until SIZE) frame[i] = samples[i] * window[i]
        fft.power(frame, power)
        val norm = 1f / ((SIZE / 4f) * (SIZE / 4f))
        return FloatArray(BANDS) { b ->
            val lo = edges[b]
            val hi = maxOf(edges[b + 1], lo + 1)
            var sum = 0f
            for (k in lo until hi) sum += power[k]
            val mean = sum / (hi - lo)
            (10 * log10((mean * norm).toDouble() + 1e-12)).toFloat().coerceAtLeast(FLOOR_DB)
        }
    }

    companion object {
        const val BANDS = 56
        const val LOW_HZ = 30.0
        const val HIGH_HZ = 18_000.0
        const val FLOOR_DB = -90f
        private const val SIZE = 2048
        private const val HOP = 1024
        private const val RING = 24

        /** The centre of band [b], in Hz. */
        fun centreHz(b: Int): Double = LOW_HZ * (HIGH_HZ / LOW_HZ).pow((b + 0.5) / BANDS)
    }
}
