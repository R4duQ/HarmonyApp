package com.harmony.core.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Runs the processor against the real native engine. Needs a host build of
 * libharmonyeq on java.library.path (core/eq-engine/src/test/cpp builds it);
 * skipped where there is none.
 */
class CustomEqAudioProcessorTest {
    private lateinit var processor: CustomEqAudioProcessor

    @Before
    fun setUp() {
        assumeTrue("host libharmonyeq not built", nativeAvailable)
        processor = CustomEqAudioProcessor()
    }

    @After
    fun tearDown() {
        if (::processor.isInitialized) processor.release()
    }

    private fun pcm16Sine(frames: Int, channels: Int, rate: Int, hz: Double, amplitude: Double): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * hz * i / rate) * 32767).roundToInt().toShort()
            repeat(channels) { buffer.putShort(v) }
        }
        buffer.flip()
        return buffer
    }

    private fun pcm24Sine(frames: Int, rate: Int, hz: Double, amplitude: Double): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 3).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * hz * i / rate) * 8_388_607).roundToInt()
            repeat(2) {
                buffer.put((v and 0xFF).toByte())
                buffer.put(((v shr 8) and 0xFF).toByte())
                buffer.put(((v shr 16) and 0xFF).toByte())
            }
        }
        buffer.flip()
        return buffer
    }

    /** Pushes [input] through in [chunkBytes] pieces, then ends the stream; returns every output float. */
    private fun runThrough(input: ByteBuffer, chunkBytes: Int): FloatArray {
        val out = ArrayList<Float>()
        fun collect() {
            while (true) {
                val output = processor.output
                if (!output.hasRemaining()) return
                val floats = output.order(ByteOrder.nativeOrder()).asFloatBuffer()
                while (floats.hasRemaining()) out += floats.get()
                output.position(output.limit())
            }
        }
        while (input.hasRemaining()) {
            val slice = input.duplicate().order(ByteOrder.nativeOrder())
            slice.limit(minOf(input.limit(), input.position() + chunkBytes))
            processor.queueInput(slice)
            input.position(slice.position())
            collect()
        }
        processor.queueEndOfStream()
        collect()
        assertTrue(processor.isEnded)
        return out.toFloatArray()
    }

    @Test
    fun `16-bit in, float out, every frame accounted for, under the ceiling`() {
        val format = processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        assertEquals(AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT), format)
        processor.flush()
        processor.setBandGains(FloatArray(10) { 12f })
        val frames = 44_100
        val out = runThrough(pcm16Sine(frames, 2, 44_100, 1000.0, 0.99), chunkBytes = 4096)
        assertEquals(frames * 2, out.size)
        val ceiling = 10f.pow(-0.15f / 20f)
        assertTrue("peaked at ${out.maxOf { abs(it) }}", out.all { abs(it) <= ceiling + 1e-6f })
        assertTrue(processor.currentPreampDb() < -7f)
    }

    @Test
    fun `flat EQ keeps the signal where it was`() {
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.setOutputDeviceType(OutputDeviceType.USB_DAC_HIRES)
        val frames = 9_600
        val input = pcm16Sine(frames, 2, 48_000, 1000.0, 0.25)
        val reference = FloatArray(frames) { input.getShort(it * 4) / 32768f }
        val out = runThrough(input, chunkBytes = 1000)
        for (i in frames / 2 until frames) {
            assertEquals("frame $i", reference[i], out[i * 2], 5e-3f)
        }
    }

    @Test
    fun `hi-res 24-bit at 96 kHz, then 192 kHz, from heap or direct buffers`() {
        processor.configure(AudioFormat(96_000, 2, C.ENCODING_PCM_24BIT))
        processor.flush()
        val direct = runThrough(pcm24Sine(9_600, 96_000, 2_000.0, 0.5), chunkBytes = 3 * 2 * 333)
        assertEquals(9_600 * 2, direct.size)

        assertEquals(
            AudioFormat(192_000, 2, C.ENCODING_PCM_FLOAT),
            processor.configure(AudioFormat(192_000, 2, C.ENCODING_PCM_24BIT)),
        )
        processor.flush()
        val source = pcm24Sine(19_200, 192_000, 2_000.0, 0.5)
        val heap = ByteBuffer.allocate(source.remaining()).order(ByteOrder.nativeOrder())
        heap.put(source).flip()
        val fromHeap = runThrough(heap, chunkBytes = 6 * 1000)
        assertEquals(19_200 * 2, fromHeap.size)
        assertTrue(fromHeap.drop(1000).maxOf { abs(it) } > 0.45f)
    }

    @Test
    fun `the spectrum polls into caller arrays`() {
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.setSpectrumBarCount(64)
        val bars = FloatArray(CustomEqAudioProcessor.MAX_BARS)
        val peaks = FloatArray(CustomEqAudioProcessor.MAX_BARS)
        runThrough(pcm16Sine(4_800, 2, 48_000, 440.0, 0.5), chunkBytes = 4096)
        assertEquals(64, processor.pollSpectrum(bars, peaks))
        assertTrue(bars.all { it in 0f..1f } && peaks.all { it in 0f..1f })
    }

    @Test
    fun `Auto calibrates live and the telemetry reports it in one call`() {
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.setOutputDeviceType(OutputDeviceType.WIRED_HEADPHONES)
        processor.setAutoMode(true)
        processor.setBandGains(FloatArray(10).also { it[5] = 2f })
        // Three seconds of a muddy 250 Hz hum: Auto hears too much of it and takes it down.
        runThrough(pcm16Sine(144_000, 2, 48_000, 250.0, 0.3), chunkBytes = 4096)
        val offsets = FloatArray(10)
        processor.liveAutoOffsets(offsets)
        assertTrue("250 Hz cut: ${offsets.toList()}", offsets[3] < -1f)
        assertTrue("within ±4.5: ${offsets.toList()}", offsets.all { it in -4.5f..4.5f })
        assertEquals("the middle holds", 0f, offsets[5])

        val effective = FloatArray(10)
        val bars = FloatArray(32)
        val peaks = FloatArray(32)
        val preamp = processor.pollDspTelemetry(effective, bars, peaks)
        assertTrue("preamp $preamp", preamp.isFinite() && preamp <= 0f)
        assertEquals("slider plus offset", 2f + offsets[5], effective[5], 0.3f)
        assertEquals("slider plus offset", offsets[3], effective[3], 0.3f)
        assertTrue(bars.all { it in 0f..1f } && peaks.all { it in 0f..1f })
        assertFalse("music is playing", processor.tonalFrozen())
        // Null arrays are fine: just the preamp.
        assertEquals(preamp, processor.pollDspTelemetry(null, null, null), 0.5f)
    }

    @Test
    fun `bit-perfect mode leaves the audio alone`() {
        processor.bitPerfectPassthrough = true
        processor.configure(AudioFormat(96_000, 2, C.ENCODING_PCM_24BIT))
        processor.flush()
        assertFalse(processor.isActive)
    }

    private companion object {
        val nativeAvailable: Boolean = runCatching { System.loadLibrary("harmonyeq") }.isSuccess
    }
}
