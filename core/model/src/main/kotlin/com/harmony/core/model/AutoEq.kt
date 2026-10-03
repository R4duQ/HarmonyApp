package com.harmony.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.random.Random

/**
 * The automatic equalizer's three switches. Each adds a gentle correction on
 * top of whatever the listener set by hand, and each can be used alone.
 */
data class AutoEqSettings(
    /** Even out each song's tonal balance towards a typical well-mastered record. */
    val tone: Boolean = false,
    /** Apply the speaker/room correction measured for the device that is playing. */
    val room: Boolean = false,
    /** On headphones, lift what the noise around you is covering up. */
    val noise: Boolean = false,
) {
    val any: Boolean get() = tone || room || noise
}

/** A speaker-and-room correction measured with the microphone, one gain per EQ band. */
data class RoomCorrection(
    val gainsDb: List<Float>,
    val measuredAtMs: Long,
    /** The device as the listener knows it, for the screen. */
    val label: String,
)

/** What kind of listening an output is, as far as the automatic equalizer cares. */
enum class ListeningKind {
    /** The ears are sealed off from the room: the microphone hears the room, not the music. */
    HEADPHONES,

    /** The music fills the room: the microphone hears it, so it can be measured. */
    SPEAKER,

    /** A Bluetooth device nobody has told us the kind of. */
    UNKNOWN,
}

/** What the automatic equalizer is doing right now, for the screen. */
data class AutoEqReadout(
    /** The current song's correction, in dB per band. */
    val toneDb: List<Float> = ZERO,
    /** The correction for the device playing now, if one was measured and is on. */
    val roomDb: List<Float> = ZERO,
    /** The lift for the noise around you. */
    val noiseDb: List<Float> = ZERO,
    /** Seconds of the current song heard so far; the tone correction fades in over these. */
    val songHeardSeconds: Float = 0f,
    /** Loudness of the surroundings in dB SPL while listening, null when the microphone is off. */
    val ambientDb: Float? = null,
) {
    val totalDb: List<Float> get() = AutoEqDesign.combine(toneDb, roomDb, noiseDb)

    private companion object {
        val ZERO = List(EqSettings.BAND_COUNT) { 0f }
    }
}

/**
 * The automatic equalizer's arithmetic, kept free of Android so it can be
 * tested on its own. Everything works in the Harmony equalizer's ten octave
 * bands ([EqSettings.BAND_CENTERS_HZ]).
 *
 *  - Tone: a song's long-term spectrum is compared with the average spectrum
 *    of commercial records, and about 40 % of the difference is corrected,
 *    a few dB at most. Thin recordings get a little body, harsh ones a little
 *    less edge; a song's own character stays.
 *  - Room: pink noise played through the speaker is recorded by the phone's
 *    microphone. Pink noise has equal energy in every octave, so whatever the
 *    microphone hears unequally is the speaker and the room; the correction
 *    takes most of it back. Only the bands a phone microphone hears reliably
 *    are trusted.
 *  - Noise: on headphones the microphone hears only the surroundings. Where
 *    the noise is above a quiet room's, the music is lifted so it isn't
 *    covered up: bass on a bus, the middle in a crowd.
 */
object AutoEqDesign {
    private const val BANDS = EqSettings.BAND_COUNT

    /**
     * Octave-band levels of the average modern commercial recording, relative
     * to its loudest octave. Pink noise would be flat here; real records lean
     * down about 3 dB per octave above the low mids and fall faster at the top.
     */
    val REFERENCE_DB = floatArrayOf(-7f, 0f, 0f, -2f, -4f, -7f, -10f, -13f, -16f, -24f)

    /** How much of a song's difference from [REFERENCE_DB] is corrected. */
    const val TONE_STRENGTH = 0.4f
    const val TONE_MAX_BOOST_DB = 3f
    const val TONE_MAX_CUT_DB = 4f

    /** Seconds of a song after which its tone correction applies in full. */
    const val TONE_SETTLE_SECONDS = 12f

    /** A band this far under the reference isn't thin, it's absent (an MP3's cut-off top, say): leave it. */
    private const val ABSENT_DB = 18f

    /** Bands whose measured level isn't a number count as absent. */
    private fun Float.usable() = !isNaN() && !isInfinite()

    /**
     * The tone correction for a song with octave-band [levelsDb] (from
     * [OctaveBandMeter]), in dB per band.
     */
    fun toneCorrection(levelsDb: FloatArray): FloatArray {
        require(levelsDb.size == BANDS)
        val diff = FloatArray(BANDS) { levelsDb[it] - REFERENCE_DB[it] }
        // Line the two up on the bands every song has (125 Hz to 4 kHz).
        val offset = median((2..7).map { diff[it] }.filter { it.usable() }) ?: return FloatArray(BANDS)
        val raw = FloatArray(BANDS) { i ->
            val d = diff[i] - offset
            if (!d.usable() || d < -ABSENT_DB) 0f else -TONE_STRENGTH * d
        }
        return smooth(raw).also { c ->
            for (i in c.indices) {
                val maxBoost = if (i == 0 || i == BANDS - 1) TONE_MAX_BOOST_DB - 1f else TONE_MAX_BOOST_DB
                c[i] = c[i].coerceIn(-TONE_MAX_CUT_DB, maxBoost)
            }
        }
    }

    // ---- Room ------------------------------------------------------------

    /** How far a phone microphone can be trusted per band: not at 31 Hz or 16 kHz, half at 62 Hz and 8 kHz. */
    val MIC_TRUST = floatArrayOf(0f, 0.5f, 1f, 1f, 1f, 1f, 1f, 1f, 0.5f, 0f)

    const val ROOM_STRENGTH = 0.8f
    const val ROOM_MAX_BOOST_DB = 4f
    const val ROOM_MAX_CUT_DB = 8f

    /** Over the noise of the room by this much, a band's reading counts. */
    private const val MIN_SNR_DB = 10f

    sealed interface RoomResult {
        data class Measured(val gainsDb: FloatArray) : RoomResult
        /** The test sound hardly rose above the room's own noise. */
        data object TooQuiet : RoomResult
        /** The microphone overloaded. */
        data object TooLoud : RoomResult
    }

    /**
     * The correction for a speaker and room, from three octave-band readings:
     * [playedDb], the test signal as it was sent; [heardDb], what the
     * microphone recorded while it played; and [ambientDb], what it recorded
     * just before, in silence.
     */
    fun roomCorrection(playedDb: FloatArray, heardDb: FloatArray, ambientDb: FloatArray, clipped: Boolean): RoomResult {
        require(playedDb.size == BANDS && heardDb.size == BANDS && ambientDb.size == BANDS)
        if (clipped) return RoomResult.TooLoud
        val snr = FloatArray(BANDS) { heardDb[it] - ambientDb[it] }
        if ((2..7).count { snr[it].usable() && snr[it] >= MIN_SNR_DB } < 5) return RoomResult.TooQuiet
        // The test signal on its own: what was heard, less the room's noise, against what was sent.
        val response = FloatArray(BANDS) { i ->
            if (!snr[i].usable() || snr[i] < MIN_SNR_DB) Float.NaN
            else powerDb(dbToPower(heardDb[i]) - dbToPower(ambientDb[i])) - playedDb[i]
        }
        val reference = median((2..7).map { response[it] }.filter { it.usable() }) ?: return RoomResult.TooQuiet
        val raw = FloatArray(BANDS) { i ->
            val d = response[i] - reference
            if (!d.usable()) 0f else -ROOM_STRENGTH * MIC_TRUST[i] * d
        }
        val gains = smooth(raw)
        for (i in gains.indices) {
            // A small speaker can't make deep bass: asking it to only makes it distort.
            val maxBoost = if (i <= 1) 0f else ROOM_MAX_BOOST_DB
            gains[i] = gains[i].coerceIn(-ROOM_MAX_CUT_DB, maxBoost)
        }
        return RoomResult.Measured(gains)
    }

    // ---- Noise -----------------------------------------------------------

    /**
     * Octave-band levels of a quiet room, dB SPL (close to the NC-35 curve).
     * Noise below these covers nothing up.
     */
    val QUIET_SPL = floatArrayOf(60f, 54f, 47f, 42f, 38f, 35f, 33f, 32f, 31f, 30f)

    /** dB of lift per dB of noise over [QUIET_SPL]. */
    const val NOISE_SLOPE = 0.3f
    const val NOISE_MAX_BOOST_DB = 6f

    /**
     * Android's compatibility rules set the voice-recognition input so that
     * a 90 dB SPL tone at 1 kHz reads 2500 (16-bit) RMS, which is −22.4 dBFS.
     * Good to a few dB on real phones, which is enough here.
     */
    const val MIC_SPL_OFFSET_DB = 112.4f

    /** The lift for ambient octave-band levels [splDb], dB SPL; NaN bands get none. */
    fun noiseBoost(splDb: FloatArray): FloatArray {
        require(splDb.size == BANDS)
        val raw = FloatArray(BANDS) { i ->
            val s = splDb[i]
            if (!s.usable()) 0f else ((s - QUIET_SPL[i]) * NOISE_SLOPE).coerceIn(0f, NOISE_MAX_BOOST_DB)
        }
        return smooth(raw).also { b -> for (i in b.indices) b[i] = b[i].coerceIn(0f, NOISE_MAX_BOOST_DB) }
    }

    /** Overall loudness from octave-band levels, the same units in and out. */
    fun overallDb(levelsDb: FloatArray): Float =
        powerDb(levelsDb.filter { it.usable() }.sumOf { dbToPower(it).toDouble() }.toFloat())

    // ---- Together ----------------------------------------------------------

    const val TOTAL_MAX_BOOST_DB = 8f
    const val TOTAL_MAX_CUT_DB = 10f

    fun combine(vararg layers: List<Float>): List<Float> = List(BANDS) { i ->
        layers.sumOf { (it.getOrNull(i) ?: 0f).toDouble() }.toFloat()
            .coerceIn(-TOTAL_MAX_CUT_DB, TOTAL_MAX_BOOST_DB)
    }

    /** Where a room correction is filed: the kind of output and the device's name. */
    fun outputKey(output: AudioOutput): String =
        output.type.name + ":" + (output.name?.trim()?.lowercase() ?: "")

    /** [form] is what the listener chose for this device, or the guess. */
    fun kind(output: AudioOutput, form: OutputForm?): ListeningKind = when {
        form == OutputForm.SPEAKER || form == OutputForm.CAR_STEREO -> ListeningKind.SPEAKER
        form != null -> ListeningKind.HEADPHONES
        output.type == AudioOutputType.WIRED -> ListeningKind.HEADPHONES
        output.type == AudioOutputType.SPEAKER || output.type == AudioOutputType.HDMI ||
            output.type == AudioOutputType.CAR -> ListeningKind.SPEAKER
        else -> ListeningKind.UNKNOWN
    }

    // ---- Helpers -----------------------------------------------------------

    /** 1-2-1 smoothing across neighbouring bands, so no band fights its neighbours. */
    private fun smooth(v: FloatArray): FloatArray = FloatArray(v.size) { i ->
        val l = v[(i - 1).coerceAtLeast(0)]
        val r = v[(i + 1).coerceAtMost(v.size - 1)]
        0.25f * l + 0.5f * v[i] + 0.25f * r
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    fun dbToPower(db: Float): Float = 10f.pow(db / 10f)
    fun powerDb(power: Float): Float = if (power > 0f) 10f * log10(power) else Float.NEGATIVE_INFINITY
}

/**
 * Follows the noise around the listener from the microphone's octave-band
 * readings and turns it into the lift the music gets. Readings rise quickly
 * (a bus pulling up, about 2 s) and fall slowly (about 6 s), so the music
 * doesn't pump with every passing car.
 */
class NoiseTracker {
    private val levels = FloatArray(EqSettings.BAND_COUNT) { Float.NaN }

    /** The smoothed noise, dB SPL per band. */
    val levelsDb: FloatArray get() = levels.copyOf()

    /** Overall loudness of the surroundings, dB SPL. */
    val ambientDb: Float get() = AutoEqDesign.overallDb(levels)

    /** Adds one reading of [seconds] (dBFS per band from [OctaveBandMeter]) and returns the lift now due. */
    fun add(readingDbfs: FloatArray, seconds: Float): FloatArray {
        for (i in levels.indices) {
            val spl = readingDbfs[i] + AutoEqDesign.MIC_SPL_OFFSET_DB
            if (spl.isNaN() || spl.isInfinite()) continue
            val now = levels[i]
            levels[i] = if (now.isNaN()) spl else {
                val tau = if (spl > now) RISE_SECONDS else FALL_SECONDS
                now + (spl - now) * (1f - kotlin.math.exp(-seconds / tau))
            }
        }
        return AutoEqDesign.noiseBoost(levels)
    }

    private companion object {
        const val RISE_SECONDS = 2f
        const val FALL_SECONDS = 6f
    }
}

/**
 * Loudness in each of the equalizer's ten octave bands: one band-pass filter
 * per band, one octave wide, and the mean power of what comes out.
 *
 * Feed it samples with [add]; [levelsDb] is the power per band since the
 * last [reset], in dB relative to full scale (a full-scale sine reads −3 dB
 * in its band). Bands the sample rate can't carry read NaN.
 *
 * Doubles inside: at 31 Hz and 48 kHz the filter's poles sit so close to the
 * unit circle that single precision drifts.
 */
class OctaveBandMeter(val sampleRate: Int) {
    private val bands = EqSettings.BAND_COUNT
    private val b0 = DoubleArray(bands)
    private val a1 = DoubleArray(bands)
    private val a2 = DoubleArray(bands)
    private val active = BooleanArray(bands)
    // Two identical sections per band, in series: [band * 2 + section].
    private val x1 = DoubleArray(bands * 2)
    private val x2 = DoubleArray(bands * 2)
    private val y1 = DoubleArray(bands * 2)
    private val y2 = DoubleArray(bands * 2)
    private val sum = DoubleArray(bands)
    var count = 0L
        private set

    init {
        for (i in 0 until bands) {
            val f = EqSettings.BAND_CENTERS_HZ[i].toDouble()
            active[i] = f < sampleRate * 0.45
            if (!active[i]) continue
            val w = 2 * PI * f / sampleRate
            // RBJ band-pass, 0 dB at the centre, one octave wide. Two in series
            // narrow the band a little and make its skirts fall twice as fast
            // (over 20 dB two octaves out), so loud bass doesn't leak into the
            // reading of the mids.
            val alpha = sin(w) * sinh(ln(2.0) / 2 * SECTION_OCTAVES * w / sin(w))
            val a0 = 1 + alpha
            b0[i] = alpha / a0
            a1[i] = -2 * cos(w) / a0
            a2[i] = (1 - alpha) / a0
        }
    }

    fun add(sample: Float) {
        val x = sample.toDouble()
        for (i in 0 until bands) {
            if (!active[i]) continue
            var v = x
            for (k in i * 2..i * 2 + 1) {
                // b1 = 0 and b2 = −b0 for this band-pass.
                val y = b0[i] * (v - x2[k]) - a1[i] * y1[k] - a2[i] * y2[k]
                x2[k] = x1[k]; x1[k] = v
                y2[k] = y1[k]; y1[k] = if (kotlin.math.abs(y) < 1e-30) 0.0 else y
                v = y
            }
            sum[i] += v * v
        }
        count++
    }

    fun add(samples: FloatArray, from: Int = 0, until: Int = samples.size) {
        for (k in from until until) add(samples[k])
    }

    fun add(samples: ShortArray, from: Int = 0, until: Int = samples.size) {
        for (k in from until until) add(samples[k] / 32768f)
    }

    /** Mean power per band since [reset], dB. */
    fun levelsDb(): FloatArray = FloatArray(bands) { i ->
        when {
            !active[i] -> Float.NaN
            count == 0L || sum[i] <= 0.0 -> Float.NEGATIVE_INFINITY
            else -> (10 * log10(sum[i] / count)).toFloat()
        }
    }

    /** Mean power per band since [reset], linear. */
    fun powers(): DoubleArray = DoubleArray(bands) { i -> if (count == 0L || !active[i]) 0.0 else sum[i] / count }

    /** Starts a new average; the filters keep running, so there is no settling to wait for. */
    fun reset() {
        sum.fill(0.0)
        count = 0
    }

    private companion object {
        /** Each section is an octave wide; two in series make a band about two thirds of an octave wide at -3 dB. */
        const val SECTION_OCTAVES = 1.0
    }
}

/**
 * Pink noise: equal energy in every octave, so through [OctaveBandMeter] it
 * reads flat. Paul Kellet's filter on white noise (within 0.05 dB of a
 * −3 dB/octave slope from 9 Hz up), scaled to about −15 dBFS RMS.
 */
class PinkNoise(seed: Int = 1) {
    private val random = Random(seed)
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0

    fun next(): Float {
        val white = random.nextDouble() * 2 - 1
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        return (pink * 0.11).toFloat()
    }
}
