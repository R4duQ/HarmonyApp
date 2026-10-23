package com.harmony.core.eq

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class EqSinkStageTest {

    /** Stands in for the EQ: 16-bit in, float out, counting flushes. */
    private class ToFloat : BaseAudioProcessor() {
        var flushes = 0
        override fun onConfigure(inputAudioFormat: AudioFormat) =
            AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)

        override fun queueInput(inputBuffer: ByteBuffer) {
            val samples = inputBuffer.remaining() / 2
            val out = replaceOutputBuffer(samples * 4)
            repeat(samples) { out.putFloat(inputBuffer.short.toFloat()) }
            out.flip()
        }

        override fun onFlush() {
            flushes++
        }
    }

    /** A sink that takes random-sized bites and enforces AudioSink's contract. */
    private class FakeSink(private val random: Random) : EqSinkStage.Downstream {
        val events = mutableListOf<String>()
        val samples = mutableListOf<Int>()
        val timestamps = mutableListOf<Pair<Long, Int>>()  // first-seen time, samples before it
        var format: Format? = null
        private var held: ByteBuffer? = null

        override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            check(held == null) { "reconfigured with a buffer half-written" }
            this.format = format
            events += "configure ${format.pcmEncoding} ${format.sampleRate} delay=${format.encoderDelay}"
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            held?.let { assertSame("a new buffer before the last was taken", it, buffer) }
            if (held == null) timestamps += presentationTimeUs to samples.size
            val float = format?.pcmEncoding == C.ENCODING_PCM_FLOAT
            val step = if (float) 4 else 2
            val bite = minOf(buffer.remaining(), random.nextInt(0, 3) * 64 * step)
            val end = buffer.position() + bite
            while (buffer.position() < end) samples += if (float) buffer.float.toInt() else buffer.short.toInt()
            held = if (buffer.hasRemaining()) buffer else null
            if (held == null) events += "data"
            return held == null
        }
    }

    private fun pcm16(rate: Int, delay: Int = 0, padding: Int = 0): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(rate)
        .setChannelCount(1)
        .setEncoderDelay(delay)
        .setEncoderPadding(padding)
        .build()

    private fun buffer(from: Int, count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count) buffer.putShort((from + i).toShort())
        buffer.flip()
        return buffer
    }

    /** Feeds like a renderer: offer each buffer until the stage takes it. */
    private fun play(stage: EqSinkStage, from: Int, count: Int, startUs: Long, rate: Int) {
        var next = from
        while (next < from + count) {
            val chunk = minOf(500, from + count - next)
            val input = buffer(next, chunk)
            val timeUs = startUs + (next - from) * C.MICROS_PER_SECOND / rate
            var guard = 0
            while (!stage.handleBuffer(input, timeUs, 1)) check(guard++ < 10_000) { "stuck" }
            next += chunk
        }
    }

    private fun finish(stage: EqSinkStage) {
        var guard = 0
        while (!stage.drainForEndOfStream()) check(guard++ < 10_000) { "stuck draining" }
    }

    private fun stage(eq: ToFloat, sink: FakeSink, passthrough: Boolean = false) =
        EqSinkStage(eq, supports = { true }, passthrough = { passthrough }, downstream = sink)

    @Test
    fun `gapless items of one format run through without a flush or a gap`() {
        val eq = ToFloat()
        val sink = FakeSink(Random(1))
        val stage = stage(eq, sink)
        stage.configure(pcm16(48_000, delay = 100, padding = 200), 0, null)
        play(stage, 0, 4800, 0, 48_000)
        val flushesBefore = eq.flushes
        stage.configure(pcm16(48_000, delay = 50, padding = 30), 0, null)
        play(stage, 10_000, 4800, 100_000, 48_000)
        finish(stage)

        assertEquals((100 until 4600).toList() + (10_050 until 14_800).toList(), sink.samples)
        assertEquals("no flush at the boundary", flushesBefore, eq.flushes)
        val configures = sink.events.filter { it.startsWith("configure") }
        assertEquals(listOf("configure 4 48000 delay=0", "configure 4 48000 delay=0"), configures)
        assertTrue(stage.isIdle)
    }

    @Test
    fun `timestamps follow the frames handed over`() {
        val sink = FakeSink(Random(2))
        val stage = stage(ToFloat(), sink)
        stage.configure(pcm16(48_000, delay = 480, padding = 0), 0, null)
        play(stage, 0, 48_000, 5_000_000, 48_000)
        finish(stage)
        for ((timeUs, samplesBefore) in sink.timestamps) {
            assertEquals(5_000_000 + samplesBefore * C.MICROS_PER_SECOND / 48_000, timeUs)
        }
    }

    @Test
    fun `a new format drains the old one first, dropping its padding`() {
        val sink = FakeSink(Random(3))
        val stage = stage(ToFloat(), sink)
        stage.configure(pcm16(48_000, delay = 0, padding = 100), 0, null)
        play(stage, 0, 1000, 0, 48_000)
        stage.configure(pcm16(96_000), 0, null)
        play(stage, 20_000, 1000, 1_000_000, 96_000)
        finish(stage)

        assertEquals((0 until 900).toList() + (20_000 until 21_000).toList(), sink.samples)
        val switch = sink.events.indexOf("configure 4 96000 delay=0")
        val lastOldData = sink.events.subList(0, switch).lastIndexOf("data")
        assertTrue("all 48 kHz data before the 96 kHz configuration", lastOldData in 0 until switch)
    }

    @Test
    fun `bit-perfect and compressed formats pass straight through`() {
        val sink = FakeSink(Random(4))
        val stage = stage(ToFloat(), sink, passthrough = true)
        stage.configure(pcm16(48_000, delay = 10), 0, null)
        play(stage, 0, 600, 0, 48_000)
        assertEquals(C.ENCODING_PCM_16BIT, sink.format?.pcmEncoding)
        assertEquals(10, sink.format?.encoderDelay)  // the sink trims it itself
        assertEquals((0 until 600).toList(), sink.samples)

        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setSampleRate(48_000).setChannelCount(6).build()
        val other = FakeSink(Random(5))
        val passStage = stage(ToFloat(), other)
        passStage.configure(ac3, 0, null)
        assertEquals(MimeTypes.AUDIO_AC3, other.format?.sampleMimeType)
        assertTrue(!passStage.processing)
    }

    @Test
    fun `a seek starts timing afresh and keeps the format`() {
        val sink = FakeSink(Random(6))
        val stage = stage(ToFloat(), sink)
        stage.configure(pcm16(48_000), 0, null)
        play(stage, 0, 1000, 0, 48_000)
        stage.flush()
        sink.timestamps.clear()
        val before = sink.samples.size
        play(stage, 7000, 1000, 9_000_000, 48_000)
        finish(stage)
        assertEquals(9_000_000L, sink.timestamps.first().first)
        assertEquals((7000 until 8000).toList(), sink.samples.subList(before, sink.samples.size))
    }
}
