package com.harmony.feature.downloads

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * A Shazam audio fingerprint: the loudest time/frequency peaks of a 16 kHz
 * mono recording, in four bands between 250 Hz and 5.5 kHz. Only this goes
 * to Shazam, never the recording itself.
 *
 * The generator and the binary format follow SongRec's reference Python
 * implementation (https://github.com/marin-m/SongRec, python-version), which
 * documented how the Shazam app builds its signatures.
 */
internal class ShazamSignature(
    val numberSamples: Int,
    /** Peaks per band (250-520 Hz, 520-1450 Hz, 1450-3500 Hz, 3500-5500 Hz), in time order. */
    val bands: List<List<Peak>>,
) {
    data class Peak(val fftPass: Int, val magnitude: Int, val frequencyBin: Int)

    val peakCount: Int get() = bands.sumOf { it.size }
    val durationMs: Int get() = (numberSamples.toLong() * 1000 / SAMPLE_RATE).toInt()

    fun encode(): ByteArray {
        val content = ByteArrayOutputStream()
        bands.forEachIndexed { band, peaks ->
            if (peaks.isEmpty()) return@forEachIndexed
            val packed = ByteArrayOutputStream()
            var pass = 0
            for (peak in peaks) {
                // Each peak stores its distance in FFT passes from the previous one;
                // a gap that doesn't fit in a byte restarts from an absolute pass.
                if (peak.fftPass - pass >= 255) {
                    packed.write(0xFF)
                    packed.writeLe32(peak.fftPass)
                    pass = peak.fftPass
                }
                packed.write(peak.fftPass - pass)
                packed.writeLe16(peak.magnitude)
                packed.writeLe16(peak.frequencyBin)
                pass = peak.fftPass
            }
            content.writeLe32(BAND_TAG + band)
            content.writeLe32(packed.size())
            packed.writeTo(content)
            repeat((4 - packed.size() % 4) % 4) { content.write(0) }
        }

        val sizeMinusHeader = content.size() + 8
        val out = ByteBuffer.allocate(HEADER_SIZE + sizeMinusHeader).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC_1)
        out.putInt(0) // CRC-32, filled in below
        out.putInt(sizeMinusHeader)
        out.putInt(MAGIC_2)
        repeat(3) { out.putInt(0) }
        out.putInt(SAMPLE_RATE_ID shl 27)
        repeat(2) { out.putInt(0) }
        out.putInt(numberSamples + (SAMPLE_RATE * 0.24).toInt())
        out.putInt((15 shl 19) + 0x40000)
        out.putInt(0x40000000)
        out.putInt(sizeMinusHeader)
        out.put(content.toByteArray())
        val bytes = out.array()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc)
        return bytes
    }

    fun dataUri(): String = "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(encode())

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val HEADER_SIZE = 48
        private const val MAGIC_1 = 0xCAFE2580.toInt()
        private const val MAGIC_2 = 0x94119C00.toInt()
        private const val SAMPLE_RATE_ID = 3 // 16 kHz
        private const val BAND_TAG = 0x60030040

        /** Fingerprints the first [count] samples of 16-bit, 16 kHz mono [pcm]. */
        fun of(pcm: ShortArray, count: Int = pcm.size): ShazamSignature = Generator().run(pcm, count.coerceIn(0, pcm.size))
    }
}

/**
 * A 2048-point FFT every 128 samples, spread in time and frequency so each
 * bin can be compared with its neighbourhood; a bin louder than all of it,
 * 46 passes back, is a peak.
 */
private class Generator {
    private val ring = ShortArray(WINDOW)
    private var ringIndex = 0

    private val fftOutputs = Array(HISTORY) { DoubleArray(BINS) }
    private var fftIndex = 0

    private val spread = Array(HISTORY) { DoubleArray(BINS) }
    private var spreadIndex = 0
    private var spreadWritten = 0

    private val re = DoubleArray(WINDOW)
    private val im = DoubleArray(WINDOW)
    private val bands = List(4) { ArrayList<ShazamSignature.Peak>() }

    fun run(pcm: ShortArray, count: Int): ShazamSignature {
        val chunks = count / CHUNK
        for (c in 0 until chunks) {
            fft(pcm, c * CHUNK)
            spreadPeaks()
            spreadWritten++
            if (spreadWritten >= 46) recognizePeaks()
        }
        return ShazamSignature(chunks * CHUNK, bands)
    }

    private fun fft(pcm: ShortArray, offset: Int) {
        System.arraycopy(pcm, offset, ring, ringIndex, CHUNK)
        ringIndex = (ringIndex + CHUNK) and (WINDOW - 1)
        // Oldest sample first, through a Hanning window without its zero ends.
        for (i in 0 until WINDOW) {
            re[i] = ring[(i + ringIndex) and (WINDOW - 1)] * HANNING[i]
            im[i] = 0.0
        }
        Fft.transform(re, im)
        val out = fftOutputs[fftIndex]
        for (i in 0 until BINS) out[i] = max((re[i] * re[i] + im[i] * im[i]) / (1 shl 17), 1e-10)
        fftIndex = (fftIndex + 1) and (HISTORY - 1)
    }

    private fun spreadPeaks() {
        val last = fftOutputs[(fftIndex - 1) and (HISTORY - 1)]
        val current = spread[spreadIndex]
        System.arraycopy(last, 0, current, 0, BINS)
        for (pos in 0 until BINS) {
            if (pos < BINS - 2) current[pos] = max(current[pos], max(current[pos + 1], current[pos + 2]))
            var value = current[pos]
            for (back in SPREAD_BACK) {
                val former = spread[(spreadIndex - back) and (HISTORY - 1)]
                value = max(former[pos], value)
                former[pos] = value
            }
        }
        spreadIndex = (spreadIndex + 1) and (HISTORY - 1)
    }

    private fun recognizePeaks() {
        val fft46 = fftOutputs[(fftIndex - 46) and (HISTORY - 1)]
        val spread49 = spread[(spreadIndex - 49) and (HISTORY - 1)]
        for (bin in 10..1014) {
            val v = fft46[bin]
            if (v < 1.0 / 64 || v < spread49[bin - 1]) continue
            var around = 0.0
            for (o in FREQUENCY_NEIGHBOURS) around = max(around, spread49[bin + o])
            if (v <= around) continue
            for (o in TIME_NEIGHBOURS) around = max(around, spread[(spreadIndex + o) and (HISTORY - 1)][bin - 1])
            if (v <= around) continue

            val magnitude = magnitude(v)
            val before = magnitude(fft46[bin - 1])
            val after = magnitude(fft46[bin + 1])
            val curvature = magnitude * 2 - before - after
            if (curvature <= 0) continue
            val correctedBin = bin * 64 + (after - before) * 32 / curvature
            val hz = correctedBin * (16000.0 / 2 / 1024 / 64)
            val band = when {
                hz < 250 -> continue
                hz < 520 -> 0
                hz < 1450 -> 1
                hz < 3500 -> 2
                hz <= 5500 -> 3
                else -> continue
            }
            bands[band] += ShazamSignature.Peak(spreadWritten - 46, magnitude.toInt(), correctedBin.toInt())
        }
    }

    private fun magnitude(power: Double) = ln(max(1.0 / 64, power)) * 1477.3 + 6144

    companion object {
        const val WINDOW = 2048
        const val BINS = WINDOW / 2 + 1
        const val CHUNK = 128
        const val HISTORY = 256
        val SPREAD_BACK = intArrayOf(1, 3, 6)
        val FREQUENCY_NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        val TIME_NEIGHBOURS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

        /** numpy.hanning(2050)[1:-1]. */
        val HANNING = DoubleArray(WINDOW) { i -> 0.5 - 0.5 * cos(2 * PI * (i + 1) / (WINDOW + 1)) }
    }
}

/** In-place radix-2 complex FFT for the fixed 2048-point window. */
private object Fft {
    private const val N = Generator.WINDOW
    private val cosTable = DoubleArray(N / 2) { cos(2 * PI * it / N) }
    private val sinTable = DoubleArray(N / 2) { sin(2 * PI * it / N) }
    private val reversed = IntArray(N) { i -> Integer.reverse(i) ushr (32 - 11) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until N) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var start = 0
            while (start < N) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }
}

private fun ByteArrayOutputStream.writeLe16(v: Int) { write(v and 0xFF); write((v ushr 8) and 0xFF) }
private fun ByteArrayOutputStream.writeLe32(v: Int) { writeLe16(v and 0xFFFF); writeLe16((v ushr 16) and 0xFFFF) }
