package com.harmony.playback.service.player

import com.harmony.core.model.WinampEqDesign

/**
 * The Winamp equalizer running on samples: ten band-passes in parallel on
 * the dry signal, each added back with its band's weight. See
 * [WinampEqDesign] for the design and where it comes from.
 *
 * Owned by the audio thread. Weights glide towards their targets once per
 * frame (~10 ms), so dragging a slider mid-song doesn't click, and the
 * filter memory survives weight changes for the same reason.
 */
internal class WinampFilterBank(val sampleRate: Int, val channels: Int) {
    private val bands = WinampEqDesign.FREQUENCIES_HZ.size
    private val alpha = FloatArray(bands)
    private val beta = FloatArray(bands)
    private val gamma = FloatArray(bands)

    /** x[n-1] and x[n-2], shared by every band of a channel. */
    private val x1 = FloatArray(channels)
    private val x2 = FloatArray(channels)
    /** y[n-1] and y[n-2] per channel and band. */
    private val y1 = Array(channels) { FloatArray(bands) }
    private val y2 = Array(channels) { FloatArray(bands) }

    private val weights = FloatArray(bands)

    @Volatile
    private var targets = FloatArray(bands)

    init {
        WinampEqDesign.coefficients(sampleRate).forEachIndexed { i, c ->
            alpha[i] = c[0]; beta[i] = c[1]; gamma[i] = c[2]
        }
    }

    /** From any thread: the slider values the weights should move to. */
    fun setGains(gainsDb: List<Float>) {
        targets = FloatArray(bands) { WinampEqDesign.weight(gainsDb.getOrElse(it) { 0f }) }
    }

    /** Snaps the weights to their targets; for a fresh bank, so it doesn't fade in. */
    fun settle() {
        targets.copyInto(weights)
    }

    /** Moves the weights a step towards their targets; call once per frame. */
    fun glide(ease: Float) {
        val t = targets
        for (b in 0 until bands) weights[b] += (t[b] - weights[b]) * ease
    }

    fun process(x: Float, ch: Int): Float {
        val ya = y1[ch]
        val yb = y2[ch]
        val xm2 = x2[ch]
        var sum = 0f
        for (b in 0 until bands) {
            var y = alpha[b] * (x - xm2) + gamma[b] * ya[b] - beta[b] * yb[b]
            // Decaying filters would otherwise end in denormals, which are slow on some CPUs.
            if (y > -DENORMAL && y < DENORMAL) y = 0f
            yb[b] = ya[b]
            ya[b] = y
            sum += y * weights[b]
        }
        x2[ch] = x1[ch]
        x1[ch] = x
        return x + sum
    }

    private companion object {
        const val DENORMAL = 1e-20f
    }
}
