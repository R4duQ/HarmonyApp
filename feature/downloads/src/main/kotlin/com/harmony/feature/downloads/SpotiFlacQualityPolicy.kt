package com.harmony.feature.downloads

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** Values read from the audio's STREAMINFO, never from provider quality labels. */
internal data class FlacStreamSpec(
    val bitDepth: Int,
    val sampleRateHz: Int,
    val channels: Int,
    val totalSamples: Long,
) {
    val label: String get() = "$bitDepth-bit / ${sampleRateHz / 1000.0} kHz"
}

internal data class FlacQualityTarget(val bitDepth: Int, val sampleRateHz: Int) {
    fun matches(source: FlacStreamSpec): Boolean =
        source.bitDepth == bitDepth && source.sampleRateHz == sampleRateHz

    fun encoderArguments(): List<String> {
        val sampleFormat = if (bitDepth <= 16) "s16" else "s32"
        return listOf(
            "-c:a", "flac", "-compression_level", "8",
            "-af", "aresample=osr=$sampleRateHz:osf=$sampleFormat:output_sample_bits=$bitDepth:dither_method=triangular",
            "-ar", sampleRateHz.toString(),
            "-sample_fmt", sampleFormat,
            "-bits_per_raw_sample", bitDepth.toString(),
        )
    }
}

/** A ceiling, not an instruction to upscale each song to the same resolution. */
internal object SpotiFlacQualityPolicy {
    fun target(source: FlacStreamSpec, format: SpotiFlacOutputFormat): FlacQualityTarget {
        require(format.isLosslessOutput)
        val maxDepth = if (format == SpotiFlacOutputFormat.FLAC_HI_RES_96) 24 else 16
        val maxRate = if (format == SpotiFlacOutputFormat.FLAC_HI_RES_96) 96_000 else 44_100
        return FlacQualityTarget(minOf(source.bitDepth, maxDepth), minOf(source.sampleRateHz, maxRate))
    }

    fun read(file: File): FlacStreamSpec = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 42) { "Missing FLAC STREAMINFO." }
        require(input.readInt() == 0x664c6143) { "Not a native FLAC file." }
        require((input.readUnsignedByte() and 0x7f) == 0) { "Missing first STREAMINFO block." }
        val length = (input.readUnsignedByte() shl 16) or
            (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        require(length == 34) { "Invalid FLAC STREAMINFO length." }
        input.seek(18) // STREAMINFO begins at byte 8; packed audio properties at +10.
        val packed = input.readLong()
        val sampleRate = ((packed ushr 44) and 0xfffff).toInt()
        val channels = ((packed ushr 41) and 7).toInt() + 1
        val depth = ((packed ushr 36) and 31).toInt() + 1
        require(sampleRate in 1..655_350 && depth in 4..32) { "Invalid FLAC audio resolution." }
        FlacStreamSpec(depth, sampleRate, channels, packed and 0xfffffffffL)
    }

    fun validateConversion(source: FlacStreamSpec, output: FlacStreamSpec, target: FlacQualityTarget) {
        require(target.matches(output)) { "Converted FLAC does not match the selected resolution." }
        require(output.channels == source.channels) { "Conversion changed the channel count." }
        if (source.totalSamples > 0) {
            require(output.totalSamples > 0) { "Converted FLAC has no sample count." }
            val expectedSamples = source.totalSamples.toDouble() * output.sampleRateHz / source.sampleRateHz
            require(abs(output.totalSamples - expectedSamples) <= 2.0) { "Conversion changed the audio duration." }
        }
    }
}
