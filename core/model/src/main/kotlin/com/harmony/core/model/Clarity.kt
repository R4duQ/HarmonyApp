package com.harmony.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Clarity's controls, the way the listener sets them.
 *
 * Clarity is the perceptual part of the automatic equalizer: a model of how
 * hearing works listens to the music as it plays and works out which sounds
 * are being covered up by others and which push forward, then moves 24 bands
 * to match (see [ClarityModel]).
 */
data class ClaritySettings(
    /** 0..1: how far sounds covered up by others are brought out. */
    val recover: Float = 0.5f,
    /** 0..1: how far sounds that push forward (resonances, sudden harshness, boom) are held back. */
    val tame: Float = 0.5f,
    /** −1..1: leans towards taming (negative) or recovering (positive). */
    val bias: Float = 0f,
    /** −1..1: darker or brighter, as a tilt around 1 kHz. */
    val brighten: Float = 0f,
    /** Output level on top of Clarity's own loudness matching, dB. */
    val boostDb: Float = 0f,
) {
    fun clamped() = ClaritySettings(
        recover = recover.coerceIn(0f, 1f),
        tame = tame.coerceIn(0f, 1f),
        bias = bias.coerceIn(-1f, 1f),
        brighten = brighten.coerceIn(-1f, 1f),
        boostDb = boostDb.coerceIn(-MAX_BOOST_DB, MAX_BOOST_DB),
    )

    /** How hard Recover works once Bias has had its say: 1 at the default 50 %, up to [MAX_WEIGHT]. */
    val recoverWeight: Float get() = 2f * recover.coerceIn(0f, 1f) * (1f + BIAS_REACH * bias.coerceIn(-1f, 1f))

    /** How hard Tame works once Bias has had its say: 1 at the default 50 %, up to [MAX_WEIGHT]. */
    val tameWeight: Float get() = 2f * tame.coerceIn(0f, 1f) * (1f - BIAS_REACH * bias.coerceIn(-1f, 1f))

    companion object {
        const val MAX_BOOST_DB = 6f

        /** At full Bias one side works 1.6 times as hard and the other 0.4 times. */
        const val BIAS_REACH = 0.6f

        /** Recover or Tame at 100 % with Bias all the way towards it. */
        const val MAX_WEIGHT = 3.2f
    }
}

/** Starting points for Clarity's five controls. */
enum class ClarityPreset(val label: String, val blurb: String, val settings: ClaritySettings) {
    GENTLE("Gentle", "A light touch", ClaritySettings(recover = 0.3f, tame = 0.3f)),
    BALANCED("Balanced", "Clear and even", ClaritySettings()),
    DETAIL("Detail", "Brings out what's hidden", ClaritySettings(recover = 0.8f, tame = 0.35f, bias = 0.3f, brighten = 0.15f)),
    SMOOTH("Smooth", "Less harshness and boom", ClaritySettings(recover = 0.25f, tame = 0.8f, bias = -0.3f, brighten = -0.1f)),
    WARM("Warm", "Fuller and softer", ClaritySettings(recover = 0.4f, tame = 0.5f, brighten = -0.45f)),
    AIRY("Airy", "Open, with more sparkle", ClaritySettings(recover = 0.6f, tame = 0.4f, bias = 0.1f, brighten = 0.45f)),
    ;

    companion object {
        /** The preset [s] is set to, if any (to the nearest percent). Boost is the listener's own and doesn't count. */
        fun matching(s: ClaritySettings): ClarityPreset? = entries.firstOrNull { p ->
            val t = p.settings
            close(t.recover, s.recover) && close(t.tame, s.tame) && close(t.bias, s.bias) && close(t.brighten, s.brighten)
        }

        private fun close(a: Float, b: Float) = abs(a - b) < 0.005f
    }
}

/**
 * Clarity's 24 bands, evenly spaced on the ERB-number scale (Glasberg &
 * Moore): each covers about 1.6 of the ear's own critical bandwidths, from
 * 40 Hz to 16 kHz. Narrow in the bass, wide in the treble, the way hearing
 * resolves pitch.
 */
object ClarityBands {
    const val COUNT = 24
    const val LOW_HZ = 40f
    const val HIGH_HZ = 16_000f

    /** ERB number of [hz]. */
    fun erbNumber(hz: Float): Float = 21.4f * log10(1f + 0.00437f * hz)

    fun hzOfErb(erb: Float): Float = (10f.pow(erb / 21.4f) - 1f) / 0.00437f

    private val erbLow = erbNumber(LOW_HZ)
    private val erbHigh = erbNumber(HIGH_HZ)
    val ERB_STEP: Float = (erbHigh - erbLow) / COUNT

    /** Band edges, [COUNT] + 1 of them. */
    val EDGES_HZ: FloatArray = FloatArray(COUNT + 1) { hzOfErb(erbLow + ERB_STEP * it) }

    val CENTER_ERB: FloatArray = FloatArray(COUNT) { erbLow + ERB_STEP * (it + 0.5f) }
    val CENTERS_HZ: FloatArray = FloatArray(COUNT) { hzOfErb(CENTER_ERB[it]) }

    /**
     * Gains on Clarity's bands seen from the equalizer's ten octaves (for the
     * Auto tab's curves): each octave centre takes the value between the two
     * bands around it on the ERB scale; 31 Hz takes the lowest band's.
     */
    fun toOctaves(db: FloatArray): FloatArray = FloatArray(EqSettings.BAND_COUNT) { i ->
        valueAt(db, EqSettings.BAND_CENTERS_HZ[i])
    }

    /** [db] (one per band) at frequency [hz], interpolated on the ERB scale. */
    fun valueAt(db: FloatArray, hz: Float): Float {
        val pos = (erbNumber(hz) - CENTER_ERB[0]) / ERB_STEP
        if (pos <= 0f) return db[0]
        if (pos >= COUNT - 1) return db[COUNT - 1]
        val i = pos.toInt()
        val f = pos - i
        return db[i] * (1 - f) + db[i + 1] * f
    }

    /** "64 Hz", "1.2 kHz": a band's centre for the screen. */
    fun label(band: Int): String {
        val hz = CENTERS_HZ[band]
        return when {
            hz < 1000f -> "${(hz / 10f).toInt() * 10} Hz"
            hz < 10_000f -> "${(hz / 100f).toInt() / 10f} kHz"
            else -> "${(hz / 1000f).toInt()} kHz"
        }
    }
}

/**
 * The hearing model's constants and the parts of it that don't change from
 * one moment to the next.
 *
 *  - The ear doesn't hear all frequencies alike: [earDb] is a smooth outer-
 *    and middle-ear weighting, half of Terhardt's threshold curve relative to
 *    1 kHz, so deep bass and the very top count for less and the ear canal's
 *    resonance near 3 kHz for a little more.
 *  - [quietDb] is the threshold of hearing in quiet (Terhardt), in dB SPL.
 *    The music's level in the room is unknown, so full scale is taken as
 *    [SPL_OFFSET_DB], a loud but ordinary listening level.
 *  - A loud sound covers up quieter ones near it in pitch: steeply below it
 *    ([LOWER_SLOPE] dB per Bark) and gently above it, more gently the louder
 *    it is ([upperSlope], Terhardt's level-dependent slope).
 */
object ClarityDesign {
    const val SPL_OFFSET_DB = 90f

    /** A sound covers up what is this far under the spread of its level. */
    const val MASK_INDEX_DB = 6f

    /** How much steeper masking falls towards lower pitches, dB per Bark. */
    const val LOWER_SLOPE = 27f

    /** One ERB-number unit is about 0.62 Bark across the range Clarity covers. */
    const val BARK_PER_ERB = 0.62f

    /** Frames whose weighted level is under this are silence or a fade-out: Clarity rests. */
    const val SILENCE_DB = -62f

    /** Stevens/Zwicker: specific loudness grows as excitation to this power. */
    const val LOUDNESS_EXPONENT = 0.23f

    // Recover: lifts a band whose own level is under this much above what covers it.
    // Per unit of weight (the default 50 % is one unit).
    const val CLEAR_MARGIN_DB = 9f
    const val RECOVER_RATIO = 0.6f
    const val MAX_RECOVER_DB = 4f

    // Tame: what pushes forward of the song's usual balance, or out of its neighbours.
    const val EXCESS_KNEE_DB = 3f
    const val EXCESS_RATIO = 0.5f
    const val PEAK_KNEE_DB = 3f
    const val PEAK_RATIO = 0.6f
    const val MAX_TAME_DB = 5f

    /**
     * Hearing integrates loudness over tens of milliseconds; band powers are
     * smoothed over this long so the random flicker of a short spectrum
     * (noise-like sounds especially) isn't mistaken for the music changing.
     */
    const val INTEGRATION_S = 0.06f

    /** Seconds over which the song's usual balance is learned. */
    const val BALANCE_SECONDS = 3f

    /** Brighten at ±1 tilts this much at either end, around [PIVOT_HZ]. */
    const val MAX_TILT_DB = 4f
    const val PIVOT_HZ = 1000f

    const val MAX_BOOST_DB = 8f
    const val MAX_CUT_DB = 9f
    const val MAX_MATCH_DB = 4f

    /** Terhardt's threshold of hearing in quiet, dB SPL. */
    fun quietDb(hz: Float): Float {
        val f = (hz / 1000f).coerceAtLeast(0.02f)
        return 3.64f * f.pow(-0.8f) - 6.5f * exp(-0.6f * (f - 3.3f) * (f - 3.3f)) + 1e-3f * f.pow(4)
    }

    /** Outer- and middle-ear weighting, dB, 0 at 1 kHz. */
    fun earDb(hz: Float): Float = (-0.5f * (quietDb(hz) - quietDb(1000f))).coerceIn(-20f, 6f)

    /** How fast masking falls above a sound at [hz] that is [splDb] loud, dB per Bark. */
    fun upperSlope(hz: Float, splDb: Float): Float =
        (24f + 230f / hz.coerceAtLeast(20f) - 0.2f * splDb).coerceIn(5f, 40f)

    /** −1 at 40 Hz, 0 at [PIVOT_HZ], +1 at 16 kHz, straight on the ERB scale in between. */
    fun tiltShape(hz: Float): Float {
        val z = ClarityBands.erbNumber(hz)
        val zp = ClarityBands.erbNumber(PIVOT_HZ)
        val lo = ClarityBands.erbNumber(ClarityBands.LOW_HZ)
        val hi = ClarityBands.erbNumber(ClarityBands.HIGH_HZ)
        return if (z >= zp) ((z - zp) / (hi - zp)).coerceAtMost(1f) else ((z - zp) / (zp - lo)).coerceAtLeast(-1f)
    }

    internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}

/**
 * The hearing model. Fed one short-time spectrum at a time (band powers from
 * [ClarityBands], about a hundred a second), it works out for each band:
 *
 *  - its level as the ear weights it ([levelDb]), integrated over
 *    [ClarityDesign.INTEGRATION_S] the way loudness is;
 *  - how loud the rest of the music makes it hard to hear there: the masking
 *    threshold ([maskDb]), the other bands' levels spread across pitch the
 *    way hearing spreads them, plus the threshold of hearing in quiet;
 *  - **Recover** ([recoverDb]): a band that is there but only just clears
 *    what covers it is lifted towards [ClarityDesign.CLEAR_MARGIN_DB] above
 *    it. Bands buried deep (noise, reverb tails, an MP3's empty top) or under
 *    the threshold of hearing are left alone, so hiss isn't brought up;
 *  - **Tame** ([tameDb]): a band is held back when it sticks out of the
 *    song's usual balance right now (a harsh note, a boomy bass line) or out
 *    of its neighbours (a resonance);
 *  - Brighten tilts the whole curve, and [targetDb] is the three together,
 *    smoothed across neighbouring bands so no band fights the next one;
 *  - [matchDb]: the level change that keeps the music as loud as it was,
 *    from the loudness the curve adds or takes away (Zwicker: loudness grows
 *    as excitation to the 0.23).
 *
 * No allocation per frame; single-threaded.
 */
class ClarityModel {
    private val n = ClarityBands.COUNT
    private val ear = FloatArray(n) { ClarityDesign.earDb(ClarityBands.CENTERS_HZ[it]) }
    private val quietLevel = FloatArray(n) { ClarityDesign.quietDb(ClarityBands.CENTERS_HZ[it]) - ClarityDesign.SPL_OFFSET_DB + ear[it] }
    private val quietPower = FloatArray(n) { dbToPower(quietLevel[it]) }
    private val bark = FloatArray(n) { ClarityBands.CENTER_ERB[it] * ClarityDesign.BARK_PER_ERB }
    private val tilt = FloatArray(n) { ClarityDesign.tiltShape(ClarityBands.CENTERS_HZ[it]) }
    private val maskScale = dbToPower(-ClarityDesign.MASK_INDEX_DB)

    private val linear = DoubleArray(n)
    private val heard = FloatArray(n)
    private var started = false
    private val excitation = DoubleArray(n)
    private val slopes = FloatArray(n)
    private val balance = FloatArray(n)
    private var learned = false
    private val raw = FloatArray(n)

    /** The ear-weighted level per band, dBFS. */
    val levelDb = FloatArray(n)

    /** The masking threshold per band, dBFS (same weighting). */
    val maskDb = FloatArray(n)

    val recoverDb = FloatArray(n)
    val tameDb = FloatArray(n)

    /** Recover, Tame and Brighten together, smoothed and limited: what Clarity wants the bands at. */
    val targetDb = FloatArray(n)

    /** Level change that keeps the loudness where it was, dB. */
    var matchDb = 0f
        private set

    /** The last frame was silence; the targets are flat. */
    var silent = true
        private set

    /** Forgets the song's balance (a new song starts). */
    fun reset() {
        learned = false
        balance.fill(0f)
    }

    /**
     * One frame: [power] per band (linear, a full-scale sine reads 0.5 in its
     * band), [seconds] since the last frame.
     */
    fun analyze(power: FloatArray, seconds: Float, settings: ClaritySettings) {
        require(power.size == n)
        val integrate = 1f - exp(-seconds / ClarityDesign.INTEGRATION_S)
        for (k in 0 until n) {
            val p = power[k].coerceAtLeast(0f)
            heard[k] = if (!started) p else heard[k] + (p - heard[k]) * integrate
        }
        started = true
        var total = 0.0
        for (k in 0 until n) {
            val p = heard[k]
            levelDb[k] = if (p > 1e-20f) 10f * log10(p) + ear[k] else -200f
            linear[k] = if (p > 1e-20f) dbToPower(levelDb[k]).toDouble() else 0.0
            total += linear[k]
        }
        val overall = if (total > 0) (10 * log10(total)).toFloat() else -200f
        if (overall < ClarityDesign.SILENCE_DB) {
            silent = true
            recoverDb.fill(0f)
            tameDb.fill(0f)
            targetDb.fill(0f)
            for (k in 0 until n) maskDb[k] = quietLevel[k]
            matchDb = 0f
            return
        }
        silent = false

        // Masking: every other band's level spread across pitch.
        for (j in 0 until n) {
            slopes[j] = ClarityDesign.upperSlope(ClarityBands.CENTERS_HZ[j], levelDb[j] + ClarityDesign.SPL_OFFSET_DB)
        }
        for (k in 0 until n) {
            var masking = 0.0
            for (j in 0 until n) {
                if (j == k || linear[j] == 0.0) continue
                val dz = abs(bark[k] - bark[j])
                val fall = (if (k < j) ClarityDesign.LOWER_SLOPE else slopes[j]) * dz
                if (fall > 70f) continue
                masking += linear[j] * dbToPower(-fall)
            }
            excitation[k] = linear[k] + masking
            maskDb[k] = (10 * log10(masking * maskScale + quietPower[k])).toFloat()
        }

        // The song's usual balance: each band's level against the whole, averaged over a few seconds.
        val follow = 1f - exp(-seconds / ClarityDesign.BALANCE_SECONDS)
        for (k in 0 until n) {
            val rel = levelDb[k] - overall
            balance[k] = if (!learned) rel else balance[k] + (rel - balance[k]) * follow
        }
        learned = true

        val rw = settings.recoverWeight
        val tw = settings.tameWeight
        val brighten = settings.brighten.coerceIn(-1f, 1f)
        for (k in 0 until n) {
            val margin = levelDb[k] - maskDb[k]
            val audible = levelDb[k] - quietLevel[k]
            // Recover what is there but only just heard.
            val present = ClarityDesign.smoothstep(-20f, -10f, margin) * ClarityDesign.smoothstep(-6f, 6f, audible)
            val deficit = (ClarityDesign.CLEAR_MARGIN_DB - margin).coerceIn(0f, 12f)
            recoverDb[k] = (deficit * ClarityDesign.RECOVER_RATIO * present * rw)
                .coerceAtMost(ClarityDesign.MAX_RECOVER_DB * rw)

            // Tame what pushes forward: of the song's usual balance, and of its neighbours.
            val excess = (levelDb[k] - overall) - balance[k]
            var sum = 0.0
            var count = 0
            for (j in (k - 2).coerceAtLeast(0)..(k + 2).coerceAtMost(n - 1)) {
                if (j == k) continue
                sum += linear[j]
                count++
            }
            val around = if (sum > 0) (10 * log10(sum / count)).toFloat() else -200f
            val peak = levelDb[k] - around
            val push = (excess - ClarityDesign.EXCESS_KNEE_DB).coerceAtLeast(0f) * ClarityDesign.EXCESS_RATIO +
                (peak - ClarityDesign.PEAK_KNEE_DB).coerceIn(0f, 20f) * ClarityDesign.PEAK_RATIO
            // Only what is clearly heard can push forward.
            val clear = ClarityDesign.smoothstep(-6f, 6f, margin)
            tameDb[k] = -(push * clear * tw).coerceAtMost(ClarityDesign.MAX_TAME_DB * tw)

            raw[k] = recoverDb[k] + tameDb[k] + brighten * ClarityDesign.MAX_TILT_DB * tilt[k]
        }
        for (k in 0 until n) {
            val l = raw[(k - 1).coerceAtLeast(0)]
            val r = raw[(k + 1).coerceAtMost(n - 1)]
            targetDb[k] = (SPREAD * l + (1 - 2 * SPREAD) * raw[k] + SPREAD * r)
                .coerceIn(-ClarityDesign.MAX_CUT_DB, ClarityDesign.MAX_BOOST_DB)
        }

        // Loudness before and after the curve; the level change that evens them out.
        val a = ClarityDesign.LOUDNESS_EXPONENT.toDouble()
        var before = 0.0
        var after = 0.0
        for (k in 0 until n) {
            val e = excitation[k]
            if (e <= 0.0) continue
            before += e.pow(a)
            after += (e * dbToPower(targetDb[k])).pow(a)
        }
        matchDb = if (before > 0 && after > 0) {
            (10.0 / a * log10(before / after)).toFloat().coerceIn(-ClarityDesign.MAX_MATCH_DB, ClarityDesign.MAX_MATCH_DB)
        } else 0f
    }

    private companion object {
        /** How much of each band's wish its neighbours share, so no band fights the next one. */
        const val SPREAD = 0.2f

        fun dbToPower(db: Float): Float = 10f.pow(db / 10f)
    }
}

/**
 * Clarity's filters: one RBJ peaking filter per band, as wide as the band.
 * Neighbouring filters overlap, so setting each to its band's gain would
 * overshoot where they add up; [fit] works out the gains that land on the
 * wanted curve at every band centre (a few rounds of correcting by the
 * filters' measured overlap).
 */
class ClarityFilterBank(val sampleRate: Int) {
    private val n = ClarityBands.COUNT

    /** Bands the sample rate can carry. */
    val active = BooleanArray(n) { ClarityBands.CENTERS_HZ[it] < sampleRate * 0.45f }

    val q = FloatArray(n) { k ->
        val width = ClarityBands.EDGES_HZ[k + 1] - ClarityBands.EDGES_HZ[k]
        ClarityBands.CENTERS_HZ[k] / width * Q_SCALE
    }

    private val cosW = DoubleArray(n) { cos(2 * PI * ClarityBands.CENTERS_HZ[it] / sampleRate) }

    // RBJ's bandwidth form, with its w/sin(w) term: keeps the treble filters as
    // wide as asked for even where the bilinear transform squeezes them.
    private val alpha = DoubleArray(n) { k ->
        val w = 2 * PI * ClarityBands.CENTERS_HZ[k] / sampleRate
        val octaves = 2 / ln(2.0) * asinh(1 / (2.0 * q[k]))
        if (w >= PI) 0.0 else sin(w) * sinh(ln(2.0) / 2 * octaves * w / sin(w))
    }

    /** overlap[j][k]: dB at band k's centre per dB of filter j's gain. */
    private val overlap: Array<FloatArray> = Array(n) { j ->
        FloatArray(n) { k ->
            if (!active[j] || !active[k]) 0f else filterDb(j, REFERENCE_DB, ClarityBands.CENTERS_HZ[k]) / REFERENCE_DB
        }
    }
    private val reached = FloatArray(n)

    /** Filter gains in [out] that put the bank's response on [targetDb] at each band centre. */
    fun fit(targetDb: FloatArray, out: FloatArray) {
        for (k in 0 until n) out[k] = if (active[k]) targetDb[k] else 0f
        repeat(FIT_ROUNDS) {
            atCenters(out, reached)
            for (k in 0 until n) {
                if (active[k]) out[k] = (out[k] + FIT_STEP * (targetDb[k] - reached[k])).coerceIn(-MAX_FILTER_DB, MAX_FILTER_DB)
            }
        }
    }

    /** The bank's response at each band centre for filter gains [gainsDb], from the measured overlap. */
    fun atCenters(gainsDb: FloatArray, out: FloatArray) {
        for (k in 0 until n) {
            var sum = 0f
            for (j in 0 until n) sum += overlap[j][k] * gainsDb[j]
            out[k] = sum
        }
    }

    /** RBJ peaking coefficients for band [k] at [gainDb], normalised: b0, b1, b2, a1, a2. */
    fun coefficients(k: Int, gainDb: Float, out: FloatArray) {
        if (gainDb == 0f || !active[k]) {
            out[0] = 1f; out[1] = 0f; out[2] = 0f; out[3] = 0f; out[4] = 0f
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val al = alpha[k]
        val a0 = 1 + al / a
        out[0] = ((1 + al * a) / a0).toFloat()
        out[1] = (-2 * cosW[k] / a0).toFloat()
        out[2] = ((1 - al * a) / a0).toFloat()
        out[3] = (-2 * cosW[k] / a0).toFloat()
        out[4] = ((1 - al / a) / a0).toFloat()
    }

    /** One filter's response at [hz], dB. */
    fun filterDb(k: Int, gainDb: Float, hz: Float): Float {
        val c = FloatArray(5)
        coefficients(k, gainDb, c)
        val w = 2 * PI * hz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c[0] + c[1] * c1 + c[2] * c2
        val ni = -(c[1] * s1 + c[2] * s2)
        val dr = 1 + c[3] * c1 + c[4] * c2
        val di = -(c[3] * s1 + c[4] * s2)
        return (10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))).toFloat()
    }

    /** The whole bank's response at [hz] with filter gains [gainsDb], dB. */
    fun responseDb(gainsDb: FloatArray, hz: Float): Float {
        var sum = 0f
        for (k in 0 until n) if (gainsDb[k] != 0f && active[k]) sum += filterDb(k, gainsDb[k], hz)
        return sum
    }

    companion object {
        /** Filters a little wider than their bands, so the bank's response is smooth between centres. */
        const val Q_SCALE = 0.8f
        const val MAX_FILTER_DB = 12f
        private const val REFERENCE_DB = 6f
        private const val FIT_ROUNDS = 4
        private const val FIT_STEP = 0.8f
    }
}

/**
 * In-place radix-2 FFT of a real signal, giving its power spectrum. Tables
 * are built once; [power] allocates nothing.
 */
class RealFft(val size: Int) {
    init {
        require(size >= 4 && size and (size - 1) == 0) { "size must be a power of two" }
    }

    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val cosT = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }
    private val reverse = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** |X[k]|² for k in 0..size/2 into [out], from [input] (size samples). */
    fun power(input: FloatArray, out: FloatArray) {
        for (i in 0 until size) {
            re[reverse[i]] = input[i]
            im[reverse[i]] = 0f
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var t = 0
                for (j in 0 until half) {
                    val wr = cosT[t]
                    val wi = -sinT[t]
                    val a = start + j
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    t += step
                }
                start += len
            }
            len *= 2
        }
        for (k in 0..size / 2) out[k] = re[k] * re[k] + im[k] * im[k]
    }

    companion object {
        /** Periodic Hann window. */
        fun hann(size: Int): FloatArray = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
    }
}

/** What Clarity is doing right now, for the screen. */
data class ClarityReadout(
    /** Ear-weighted level per band, dBFS. */
    val levelDb: List<Float> = FLOOR,
    /** Masking threshold per band, dBFS. */
    val maskDb: List<Float> = FLOOR,
    val recoverDb: List<Float> = ZERO,
    val tameDb: List<Float> = ZERO,
    /** The curve playing now, dB per band. */
    val gainDb: List<Float> = ZERO,
    /** Loudness matching plus Boost, dB. */
    val outputDb: Float = 0f,
    val silent: Boolean = true,
) {
    /** Bands being brought out / held back by at least half a dB. */
    val recovering: Int get() = recoverDb.count { it >= 0.5f }
    val taming: Int get() = tameDb.count { it <= -0.5f }

    private companion object {
        val ZERO = List(ClarityBands.COUNT) { 0f }
        val FLOOR = List(ClarityBands.COUNT) { -120f }
    }
}

