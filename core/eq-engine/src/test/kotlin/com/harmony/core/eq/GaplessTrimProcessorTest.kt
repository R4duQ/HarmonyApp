package com.harmony.core.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GaplessTrimProcessorTest {
    private val mono16 = AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)

    private fun frames(from: Int, count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count) buffer.putShort((from + i).toShort())
        buffer.flip()
        return buffer
    }

    private fun drainTo(processor: GaplessTrimProcessor, out: MutableList<Int>) {
        while (true) {
            val output = processor.output
            if (!output.hasRemaining()) break
            while (output.hasRemaining()) out += output.short.toInt()
        }
    }

    private fun feed(processor: GaplessTrimProcessor, from: Int, count: Int, out: MutableList<Int>) {
        var next = from
        while (next < from + count) {
            val chunk = minOf(97, from + count - next)
            processor.queueInput(frames(next, chunk))
            drainTo(processor, out)
            next += chunk
        }
    }

    @Test
    fun `trims delay and padding, and keeps the padding at a plain end of stream`() {
        val trimmer = GaplessTrimProcessor()
        trimmer.setTrimFrameCount(10, 20)
        trimmer.configure(mono16)
        trimmer.flush()
        val out = mutableListOf<Int>()
        feed(trimmer, 0, 1000, out)
        assertEquals(1000 - 10 - 20, out.size)
        assertEquals(10, out.first())
        trimmer.queueEndOfStream()
        drainTo(trimmer, out)
        // As Media3 does, the held frames are output when the stream just ends.
        assertEquals(990, out.size)
        assertEquals(999, out.last())
    }

    @Test
    fun `drops the padding when another item follows`() {
        val trimmer = GaplessTrimProcessor()
        trimmer.setTrimFrameCount(10, 20)
        trimmer.configure(mono16)
        trimmer.flush()
        val out = mutableListOf<Int>()
        feed(trimmer, 0, 1000, out)
        trimmer.setDropPaddingAtEnd(true)
        trimmer.queueEndOfStream()
        drainTo(trimmer, out)
        assertEquals(970, out.size)
        assertEquals(979, out.last())
    }

    @Test
    fun `moves to the next gapless item without a flush`() {
        val trimmer = GaplessTrimProcessor()
        trimmer.setTrimFrameCount(10, 20)
        trimmer.configure(mono16)
        trimmer.flush()
        val out = mutableListOf<Int>()
        feed(trimmer, 0, 1000, out)
        trimmer.startNextItem(5, 7)
        feed(trimmer, 2000, 500, out)
        trimmer.setDropPaddingAtEnd(true)
        trimmer.queueEndOfStream()
        drainTo(trimmer, out)
        // Item 1 without its delay and padding, then item 2 without its own.
        assertEquals((10 until 980).toList() + (2005 until 2493).toList(), out)
    }

    @Test
    fun `a seek inside an item trims nothing`() {
        val trimmer = GaplessTrimProcessor()
        trimmer.setTrimFrameCount(10, 0)
        trimmer.configure(mono16)
        trimmer.flush()
        val out = mutableListOf<Int>()
        feed(trimmer, 0, 100, out)
        trimmer.flush()  // seek
        out.clear()
        feed(trimmer, 500, 100, out)
        assertEquals((500 until 600).toList(), out)
    }
}
