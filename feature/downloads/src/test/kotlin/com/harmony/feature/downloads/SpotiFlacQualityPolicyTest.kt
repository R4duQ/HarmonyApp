package com.harmony.feature.downloads

import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SpotiFlacQualityPolicyTest {
    private val hiRes = SpotiFlacOutputFormat.FLAC_HI_RES_96
    private fun spec(depth: Int, rate: Int) = FlacStreamSpec(depth, rate, 2, rate.toLong() * 10)

    @Test fun nativeResolutionsBelowTheCeilingNeedNoConversion() {
        for (source in listOf(spec(16, 44_100), spec(16, 48_000), spec(24, 44_100), spec(24, 48_000), spec(24, 88_200), spec(24, 96_000))) {
            assertTrue(source.label, SpotiFlacQualityPolicy.target(source, hiRes).matches(source))
        }
    }

    @Test fun higherSampleRatesAreCappedWithoutIncreasingBitDepth() {
        assertEquals(FlacQualityTarget(24, 96_000), SpotiFlacQualityPolicy.target(spec(24, 192_000), hiRes))
        assertEquals(FlacQualityTarget(24, 96_000), SpotiFlacQualityPolicy.target(spec(24, 176_400), hiRes))
        assertEquals(FlacQualityTarget(16, 96_000), SpotiFlacQualityPolicy.target(spec(16, 192_000), hiRes))
    }

    @Test fun higherBitDepthIsCappedWithoutIncreasingSampleRate() {
        assertEquals(FlacQualityTarget(24, 48_000), SpotiFlacQualityPolicy.target(spec(32, 48_000), hiRes))
        assertEquals(FlacQualityTarget(24, 96_000), SpotiFlacQualityPolicy.target(spec(32, 192_000), hiRes))
    }

    @Test fun cdSelectionHasItsOwnCeiling() {
        assertEquals(FlacQualityTarget(16, 44_100), SpotiFlacQualityPolicy.target(spec(24, 96_000), SpotiFlacOutputFormat.FLAC_LOSSLESS))
        val lower = spec(16, 32_000)
        assertTrue(SpotiFlacQualityPolicy.target(lower, SpotiFlacOutputFormat.FLAC_LOSSLESS).matches(lower))
    }

    @Test fun selectionRoundTripsThroughExistingPreferenceAndAlbumStorage() {
        for (format in SpotiFlacOutputFormat.entries) assertEquals(format, SpotiFlacOutputFormat.fromName(format.name))
        assertEquals(SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacOutputFormat.fromName(null))
        assertEquals(SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacOutputFormat.fromName("unknown-future-value"))
        assertEquals("flac", hiRes.extension)
        assertEquals("FLAC", hiRes.historyLabel)
    }

    @Test fun readsActualPackedStreamInfoIncludingLongSampleCounts() {
        for (depth in listOf(16, 24, 32)) {
            val source = FlacStreamSpec(depth, 192_000, 2, 0xabcdef123L)
            withHeader(header(source)) { assertEquals(source, SpotiFlacQualityPolicy.read(it)) }
        }
    }

    @Test fun rejectsFakeFlacAndMalformedStreamInfo() {
        val valid = header(spec(24, 96_000))
        val invalid = listOf(
            ByteArray(12),
            valid.copyOf().apply { this[0] = 0 },
            valid.copyOf().apply { this[4] = 4 },
            valid.copyOf().apply { this[7] = 33 },
            header(spec(24, 0)),
            header(spec(2, 96_000)),
        )
        invalid.forEach { bytes -> withHeader(bytes) { file ->
            assertThrows(IllegalArgumentException::class.java) { SpotiFlacQualityPolicy.read(file) }
        } }
    }

    @Test fun verifiesDurationAcrossResampling() {
        val source = spec(24, 192_000)
        val output = spec(24, 96_000)
        val target = SpotiFlacQualityPolicy.target(source, hiRes)
        SpotiFlacQualityPolicy.validateConversion(source, output, target)
        assertThrows(IllegalArgumentException::class.java) {
            SpotiFlacQualityPolicy.validateConversion(source, output.copy(totalSamples = 96_000), target)
        }
    }

    @Test fun rejectsIncorrectResolutionAndChannelChanges() {
        val source = spec(24, 192_000)
        val target = SpotiFlacQualityPolicy.target(source, hiRes)
        for (output in listOf(source, spec(16, 96_000), spec(24, 96_000).copy(channels = 1), spec(24, 96_000).copy(totalSamples = 0))) {
            assertThrows(IllegalArgumentException::class.java) {
                SpotiFlacQualityPolicy.validateConversion(source, output, target)
            }
        }
    }

    @Test fun allowsOneSampleRoundingWhenResampling() {
        val source = spec(24, 176_400).copy(totalSamples = 176_401)
        SpotiFlacQualityPolicy.validateConversion(source, spec(24, 96_000).copy(totalSamples = 96_001), SpotiFlacQualityPolicy.target(source, hiRes))
    }

    private fun header(spec: FlacStreamSpec): ByteArray = ByteArray(42).also { bytes ->
        ByteBuffer.wrap(bytes).apply {
            putInt(0x664c6143)
            putInt(0x80000022.toInt())
            position(18)
            putLong((spec.sampleRateHz.toLong() shl 44) or ((spec.channels - 1).toLong() shl 41) or
                ((spec.bitDepth - 1).toLong() shl 36) or spec.totalSamples)
        }
    }

    private fun withHeader(bytes: ByteArray, action: (java.io.File) -> Unit) {
        val file = Files.createTempFile("harmony-quality-", ".flac").toFile()
        try { file.writeBytes(bytes); action(file) } finally { file.delete() }
    }
}
