package com.harmony.feature.downloads

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Synthetic, locally generated audio only. CI provides HARMONY_TEST_FFMPEG. */
class AmazonDownloadFinalizerFfmpegTest {
    @get:Rule val temp = TemporaryFolder()
    private val binary = System.getenv("HARMONY_TEST_FFMPEG").orEmpty()
    private val key = "00112233445566778899aabbccddeeff"

    @Before fun requiresHostFfmpeg() { assumeTrue("Set HARMONY_TEST_FFMPEG to run real-container checks", binary.isNotBlank()) }

    private fun execute(args: List<String>): AmazonDownloadFinalizer.Execution {
        val log = File(temp.root, "test-process.log")
        val process = ProcessBuilder(listOf(binary) + args).redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        return AmazonDownloadFinalizer.Execution(finished && process.exitValue() == 0, log.readText())
    }

    private fun fixture(codec: String, encrypt: Boolean = true, bits: Int = 16, sampleRate: Int = 48000): Pair<File, File> {
        val original = File(temp.root, "reference-$codec.wav")
        assertTrue(execute(listOf("-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=$sampleRate",
            "-t", "0.35", "-c:a", "pcm_s${bits}le", original.absolutePath)).success)
        // Deliberately misleading extension, matching Amazon's descriptor-driven input.
        val encrypted = File(temp.root, "encrypted-$codec.flac")
        val encryption = if (encrypt) listOf("-encryption_scheme", "cenc-aes-ctr",
            "-encryption_key", key, "-encryption_kid", "ffeeddccbbaa99887766554433221100") else emptyList()
        assertTrue(execute(listOf("-v", "error", "-y", "-i", original.absolutePath,
            "-c:a", codec, "-strict", "-2") + encryption +
            listOf("-movflags", "+faststart", "-f", "mp4", encrypted.absolutePath)).success)
        return original to encrypted
    }

    private fun response(k: String = key) = JSONObject().put("audio_codec", "flac").put(
        "decryption", JSONObject().put("strategy", "ffmpeg.mov_key").put("key", k).put("input_format", "mov"),
    )

    private fun pcmHash(file: File): String {
        val result = execute(listOf("-v", "error", "-i", file.absolutePath, "-map", "0:a:0", "-c:a", "pcm_s32le", "-f", "hash", "-hash", "sha256", "-"))
        assertTrue(result.output, result.success)
        return result.output.trim()
    }

    @Test fun providerMovResultsKeepTheExactDecodedAudio() = runBlocking {
        for ((codec, bits, sampleRate) in listOf(Triple("flac", 16, 48000), Triple("flac", 24, 96000), Triple("alac", 16, 48000))) {
            // FFmpeg's CENC muxer does not preserve ALAC's configuration in a usable fixture.
            // Test encrypted FLAC and a valid clear ALAC descriptor result, without inventing
            // an encrypted ALAC fixture that is undecodable even with the correct key.
            val (original, encrypted) = fixture(codec, encrypt = codec == "flac", bits = bits, sampleRate = sampleRate)
            val result = AmazonDownloadFinalizer.finish("amazon", response(), encrypted, temp.root) { execute(it) }
            assertEquals(codec, result.codec)
            assertEquals(pcmHash(original), pcmHash(result.file))
            val flac = File(temp.root, "final-$codec.flac")
            assertTrue(execute(listOf("-v", "error", "-xerror", "-y", "-i", result.file.absolutePath,
                "-map", "0:a:0", "-c:a", if (codec == "flac") "copy" else "flac", "-f", "flac", flac.absolutePath)).success)
            assertEquals("fLaC", flac.inputStream().use { input -> ByteArray(4).also { input.read(it) } }.toString(Charsets.US_ASCII))
            assertEquals(pcmHash(original), pcmHash(flac))
        }
    }

    @Test fun wrongKeyNeverProducesAnAcceptedResult() = runBlocking {
        val (_, encrypted) = fixture("flac")
        try {
            AmazonDownloadFinalizer.finish("amazon", response("ffffffffffffffffffffffffffffffff"), encrypted, temp.root) { execute(it) }
            fail("wrong key accepted")
        } catch (_: AmazonDownloadFinalizer.Failure) { }
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("harmony-amazon-") })
    }

    @Test fun actualAacIsRejectedDespiteLosslessProviderMetadata() = runBlocking {
        val (_, encrypted) = fixture("aac")
        try {
            AmazonDownloadFinalizer.finish("amazon", response(), encrypted, temp.root) { execute(it) }
            fail("lossy audio accepted")
        } catch (f: AmazonDownloadFinalizer.Failure) { assertEquals("codec", f.stage) }
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("harmony-amazon-") })
    }
}

