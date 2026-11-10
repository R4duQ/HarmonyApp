package com.harmony.feature.downloads

/**
 * A real, small FLAC file for tests: 16-bit stereo at 44.1 kHz in VERBATIM
 * subframes (the samples as they are, no prediction), with STREAMINFO's MD5
 * of the audio, plus the matching decoder. Enough of the format to prove what
 * a metadata rewrite must never change: the decoded PCM.
 */
internal object TestFlac {
    private const val BLOCK = 4096

    /** [frames] stereo samples that are different for each [seed]. */
    fun pcm(frames: Int, seed: Int): ShortArray {
        val random = java.util.Random(seed.toLong())
        return ShortArray(frames * 2) { (random.nextInt(65536) - 32768).toShort() }
    }

    /** The PCM as FLAC hashes it: little-endian, interleaved. */
    fun pcmBytes(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s -> out[2 * i] = (s.toInt() and 0xff).toByte(); out[2 * i + 1] = (s.toInt() shr 8 and 0xff).toByte() }
    }

    /** A FLAC stream of [samples] (interleaved stereo), with [comments] in a VORBIS_COMMENT block when given. */
    fun encode(samples: ShortArray, comments: List<String> = emptyList(), vendor: String = "provider encoder"): ByteArray {
        val frames = samples.size / 2
        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        val info = java.io.ByteArrayOutputStream()
        fun be(target: java.io.ByteArrayOutputStream, value: Long, bytes: Int) { for (i in bytes - 1 downTo 0) target.write((value shr (8 * i) and 0xff).toInt()) }
        be(info, BLOCK.toLong(), 2); be(info, BLOCK.toLong(), 2); be(info, 0, 3); be(info, 0, 3)
        // 20 bits sample rate, 3 bits channels-1, 5 bits bps-1, 36 bits total samples.
        val packed = (44_100L shl 44) or (1L shl 41) or (15L shl 36) or frames.toLong()
        be(info, packed, 8)
        info.write(java.security.MessageDigest.getInstance("MD5").digest(pcmBytes(samples)))
        val hasComments = comments.isNotEmpty()
        out.write(if (hasComments) 0 else 0x80); be(out, info.size().toLong(), 3); out.write(info.toByteArray())
        if (hasComments) {
            val block = FlacMetadataWriter.buildVorbisCommentBlock(vendor, comments)
            out.write(0x80 or 4); be(out, block.size.toLong(), 3); out.write(block)
        }
        var frameNumber = 0
        var position = 0
        while (position < frames) {
            val count = minOf(BLOCK, frames - position)
            val frame = java.io.ByteArrayOutputStream()
            frame.write(0xFF); frame.write(0xF8)                      // sync, fixed block size
            frame.write(((if (count == BLOCK) 0xC else 0x7) shl 4) or 0x9) // block size code, 44.1 kHz
            frame.write((0x1 shl 4) or (0x4 shl 1))                   // 2 independent channels, 16 bits
            utf8(frame, frameNumber)
            if (count != BLOCK) be(frame, (count - 1).toLong(), 2)
            frame.write(crc8(frame.toByteArray()))
            for (channel in 0..1) {
                frame.write(0x02)                                      // subframe: VERBATIM
                for (i in 0 until count) be(frame, (samples[2 * (position + i) + channel].toInt() and 0xffff).toLong(), 2)
            }
            be(frame, crc16(frame.toByteArray()).toLong(), 2)
            out.write(frame.toByteArray())
            position += count
            frameNumber++
        }
        return out.toByteArray()
    }

    class Decoded(val samples: ShortArray, val comments: List<String>, val vendor: String, val pictures: List<ByteArray>,
                  val streamInfo: ByteArray, val audioFrames: ByteArray) {
        /** STREAMINFO's MD5 of the audio, as hex. */
        val declaredMd5: String get() = streamInfo.copyOfRange(18, 34).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val decodedMd5: String get() = java.security.MessageDigest.getInstance("MD5").digest(pcmBytes(samples))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun comment(key: String): List<String> = comments.filter { it.substringBefore('=').equals(key, ignoreCase = true) }.map { it.substringAfter('=') }
    }

    /** Decodes a file made by [encode] (and rewritten by anything): metadata blocks, then every frame, CRCs checked. */
    fun decode(bytes: ByteArray): Decoded {
        check(String(bytes, 0, 4) == "fLaC") { "not FLAC" }
        var at = 4
        var streamInfo = ByteArray(0)
        var comments = emptyList<String>()
        var vendor = ""
        val pictures = mutableListOf<ByteArray>()
        while (true) {
            val header = bytes[at].toInt() and 0xff
            val length = ((bytes[at + 1].toInt() and 0xff) shl 16) or ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
            val payload = bytes.copyOfRange(at + 4, at + 4 + length)
            when (header and 0x7f) {
                0 -> streamInfo = payload
                4 -> FlacMetadataWriter.parseVorbisCommentBlock(payload)!!.let { vendor = it.first; comments = it.second }
                6 -> pictures += payload
            }
            at += 4 + length
            if (header and 0x80 != 0) break
        }
        val audioStart = at
        val out = java.io.ByteArrayOutputStream()
        val samples = ArrayList<Short>()
        while (at < bytes.size) {
            val start = at
            check((bytes[at].toInt() and 0xff) == 0xFF && (bytes[at + 1].toInt() and 0xff) == 0xF8) { "lost frame sync at $at" }
            val sizeCode = (bytes[at + 2].toInt() and 0xff) shr 4
            at += 4
            // The frame number: one UTF-8 style coded value.
            val lead = bytes[at].toInt() and 0xff
            at += when { lead < 0x80 -> 1; lead < 0xE0 -> 2; lead < 0xF0 -> 3; else -> 4 }
            val count = if (sizeCode == 0x7) { val v = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff); at += 2; v + 1 } else BLOCK
            check(crc8(bytes.copyOfRange(start, at)) == (bytes[at].toInt() and 0xff)) { "frame header CRC" }
            at += 1
            val channels = Array(2) { ShortArray(count) }
            for (channel in 0..1) {
                check((bytes[at].toInt() and 0xff) == 0x02) { "not a VERBATIM subframe" }
                at += 1
                for (i in 0 until count) { channels[channel][i] = (((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)).toShort(); at += 2 }
            }
            check(crc16(bytes.copyOfRange(start, at)) == (((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff))) { "frame CRC" }
            at += 2
            for (i in 0 until count) { samples += channels[0][i]; samples += channels[1][i] }
        }
        out.write(bytes, audioStart, bytes.size - audioStart)
        return Decoded(samples.toShortArray(), comments, vendor, pictures, streamInfo, out.toByteArray())
    }

    private fun utf8(out: java.io.ByteArrayOutputStream, value: Int) {
        when {
            value < 0x80 -> out.write(value)
            value < 0x800 -> { out.write(0xC0 or (value shr 6)); out.write(0x80 or (value and 0x3F)) }
            else -> { out.write(0xE0 or (value shr 12)); out.write(0x80 or (value shr 6 and 0x3F)); out.write(0x80 or (value and 0x3F)) }
        }
    }

    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xff)
            repeat(8) { crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1; crc = crc and 0xff }
        }
        return crc
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x8005 else crc shl 1; crc = crc and 0xffff }
        }
        return crc
    }
}
