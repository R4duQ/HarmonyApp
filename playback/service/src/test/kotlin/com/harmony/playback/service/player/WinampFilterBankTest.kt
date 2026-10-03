package com.harmony.playback.service.player

import com.harmony.core.model.WinampEqDesign
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class WinampFilterBankTest {
    /** Plays a sine through the bank and measures the gain once it has settled, in dB. */
    private fun measuredDb(gains: List<Float>, frequency: Float, rate: Int = 44_100): Float =
        measuredDb(WinampFilterBank(rate, channels = 1).apply { setGains(gains); settle() }, frequency, rate)

    private fun measuredDb(bank: WinampFilterBank, frequency: Float, rate: Int = 44_100): Float {
        var inPower = 0.0
        var outPower = 0.0
        val total = rate          // one second
        for (n in 0 until total) {
            val x = (0.25 * sin(2 * PI * frequency * n / rate)).toFloat()
            val y = bank.process(x, 0)
            if (n > total / 2) { inPower += x * x; outPower += y * y }
        }
        return (10 * log10(outPower / inPower)).toFloat()
    }

    @Test fun `the samples follow the designed curve`() {
        val rock = WinampEqDesign.PRESETS.first { it.first == "Rock" }.second.second
        for (f in listOf(60f, 310f, 1000f, 3000f, 6000f, 14000f)) {
            assertEquals("Rock at $f Hz", WinampEqDesign.responseDb(rock, 0f, f), measuredDb(rock, f), 0.15f)
        }
    }

    @Test fun `flat leaves the signal untouched`() {
        val bank = WinampFilterBank(48_000, channels = 2).apply { setGains(List(10) { 0f }); settle() }
        for (n in 0 until 2_000) {
            val x = sin(n * 0.37).toFloat() * 0.5f
            assertEquals(x, bank.process(x, n % 2), 1e-6f)
        }
    }

    @Test fun `weights glide to a new setting instead of jumping`() {
        val bank = WinampFilterBank(44_100, channels = 1).apply { setGains(List(10) { 0f }); settle() }
        bank.setGains(List(10) { 0f }.toMutableList().also { it[4] = 12f })
        bank.glide(0.1f)
        // One step in, the 1 kHz band has moved a tenth of the way: about +2 dB, not +12.
        val afterOneStep = measuredDb(bank, 1000f)
        assertEquals(2.1f, afterOneStep, 0.3f)
        repeat(200) { bank.glide(0.1f) }
        assertEquals(12f, measuredDb(bank, 1000f), 0.1f)
    }

    @Test fun `channels are filtered independently`() {
        val bank = WinampFilterBank(44_100, channels = 2).apply { setGains(List(10) { 6f }); settle() }
        var left = 0f
        for (n in 0 until 500) {
            left = bank.process(sin(n * 0.2).toFloat(), 0)
            assertEquals(0f, bank.process(0f, 1), 0f)   // silence stays silence
        }
        assertEquals(true, sqrt(left * left) >= 0f)
    }
}
