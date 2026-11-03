package com.harmony.feature.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class AmazonDownloadFinalizerTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = "00112233445566778899aabbccddeeff"
    private fun response(strategy: String = "ffmpeg.mov_key", suppliedKey: String = key) = JSONObject().put(
        "decryption", JSONObject().put("strategy", strategy).put("key", suppliedKey).put("input_format", "mov"),
    )
    private fun source() = File(temp.root, "encrypted.flac").apply { writeText("encrypted provider result") }
    private fun mp4(file: File) = file.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray())
    private fun codec(name: String) = "  Stream #0:0[0x1](und): Audio: $name, 48000 Hz, stereo\n  Stream #0:0: Audio: pcm_s16le, 48000 Hz, stereo"

    @Test fun amazonRemuxesBeforeCheckingTheActualCodec() = runBlocking {
        val original = source()
        val calls = mutableListOf<List<String>>()
        val result = AmazonDownloadFinalizer.finish("amazon", response(), original, temp.root) { args ->
            calls += args
            if (calls.size == 1) mp4(File(args.last()))
            AmazonDownloadFinalizer.Execution(true, if (calls.size == 2) codec("flac") else "")
        }
        assertEquals("flac", result.codec)
        assertTrue(result.file.isFile)
        assertEquals("encrypted provider result", original.readText())
        assertEquals(2, calls.size)
        val remux = calls.first()
        assertTrue(remux.indexOf("-decryption_key") < remux.indexOf("-i"))
        assertEquals("mov", remux[remux.indexOf("-f") + 1])
        assertEquals("copy", remux[remux.indexOf("-c:a") + 1])
        assertFalse(calls.last().contains("-decryption_key"))
        assertFalse(calls.last().contains("-t")) // check the whole audio, not a short prefix
    }

    @Test fun acceptsAlacAndIgnoresProviderCodecClaims() = runBlocking {
        val r = response().put("audio_codec", "flac")
        var calls = 0
        val result = AmazonDownloadFinalizer.finish("amazon", r, source(), temp.root) { args ->
            if (++calls == 1) mp4(File(args.last()))
            AmazonDownloadFinalizer.Execution(true, codec("alac"))
        }
        assertEquals("alac", result.codec)
    }

    @Test fun doesNotTreatAacAsLosslessEvenWhenProviderClaimsFlac() = runBlocking {
        var calls = 0
        val failure = failure {
            AmazonDownloadFinalizer.finish("amazon", response().put("audio_codec", "flac"), source(), temp.root) { args ->
                if (++calls == 1) mp4(File(args.last()))
                AmazonDownloadFinalizer.Execution(true, codec("aac"))
            }
        }
        assertEquals("codec", failure.stage)
        assertEquals(0, temp.root.listFiles()!!.count { it.name.startsWith("harmony-amazon-") })
    }

    @Test fun neverRunsForOtherProviders() = runBlocking {
        for (provider in listOf("tidal-web", "qobuz-web", "deezer")) {
            val f = failure { AmazonDownloadFinalizer.finish(provider, response(), source(), temp.root) { error("must not execute") } }
            assertEquals("provider", f.stage)
        }
    }

    @Test fun validatesStrategyFormatAndKeyWithoutEchoingThem() = runBlocking {
        val inputs = listOf(response("unknown"), response(suppliedKey = "private-invalid-key"), response().apply {
            getJSONObject("decryption").put("input_format", "http")
        })
        for (r in inputs) {
            val f = failure { AmazonDownloadFinalizer.finish("amazon", r, source(), temp.root) { error("must not execute") } }
            assertEquals("descriptor", f.stage)
            assertFalse(f.stackTraceToString().contains("private-invalid-key"))
        }
    }

    @Test fun acceptsLegacyKeyAndUpstreamStrategyAliases() = runBlocking {
        val inputs = listOf(
            JSONObject().put("decryption_key", key), response("ffmpeg_mov_key"), response("mov_decryption_key"),
            response("mp4_decryption_key"), response("ffmpeg.mp4_decryption_key"), response(""),
        )
        for (r in inputs) {
            var calls = 0
            assertEquals("flac", AmazonDownloadFinalizer.finish("amazon", r, source(), temp.root) { args ->
                if (++calls == 1) mp4(File(args.last()))
                AmazonDownloadFinalizer.Execution(true, codec("flac"))
            }.codec)
        }
    }

    @Test fun normalizesHexAndBase64KeysBeforePassingArguments() = runBlocking {
        val bytes = ByteArray(16) { (it * 17).toByte() }
        for (raw in listOf("0x" + key.uppercase(), key.chunked(2).joinToString(":"), Base64.getEncoder().encodeToString(bytes))) {
            var calls = 0
            AmazonDownloadFinalizer.finish("amazon", response(suppliedKey = raw), source(), temp.root) { args ->
                if (++calls == 1) {
                    assertEquals(key, args[args.indexOf("-decryption_key") + 1])
                    mp4(File(args.last()))
                }
                AmazonDownloadFinalizer.Execution(true, codec("flac"))
            }
        }
    }

    @Test fun failedRemuxNeverPublishesEvenIfItLeftBytes() = runBlocking {
        val original = source()
        val f = failure { AmazonDownloadFinalizer.finish("amazon", response(), original, temp.root) { args ->
            mp4(File(args.last()))
            AmazonDownloadFinalizer.Execution(false, "failure with key $key")
        } }
        assertEquals("remux", f.stage)
        assertTrue(original.isFile)
        assertFalse(f.stackTraceToString().contains(key))
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test fun rejectsEmptyOrNonContainerOutput() = runBlocking {
        val f = failure { AmazonDownloadFinalizer.finish("amazon", response(), source(), temp.root) { args ->
            File(args.last()).writeText("not an audio container")
            AmazonDownloadFinalizer.Execution(true, "")
        } }
        assertEquals("remux", f.stage)
    }

    @Test fun decoderFailureCleansOutputAndRedactsDiagnostics() = runBlocking {
        var calls = 0
        val f = failure { AmazonDownloadFinalizer.finish("amazon", response(), source(), temp.root) { args ->
            if (++calls == 1) mp4(File(args.last()))
            AmazonDownloadFinalizer.Execution(calls == 1, "invalid key $key")
        } }
        assertEquals("decode", f.stage)
        assertFalse(f.stackTraceToString().contains(key))
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test fun exceptionsAndCancellationDoNotKeepPartialOutput() = runBlocking {
        val original = source()
        val f = failure { AmazonDownloadFinalizer.finish("amazon", response(), original, temp.root) { args ->
            mp4(File(args.last()))
            throw IllegalStateException("argument: $key")
        } }
        assertNull(f.cause)
        assertFalse(f.stackTraceToString().contains(key))
        try {
            AmazonDownloadFinalizer.finish("amazon", response(), original, temp.root) { args ->
                mp4(File(args.last()))
                throw CancellationException("cancelled")
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    private suspend fun failure(block: suspend () -> Any?): AmazonDownloadFinalizer.Failure {
        try { block() } catch (f: AmazonDownloadFinalizer.Failure) { return f }
        throw AssertionError("expected Amazon finalization failure")
    }
}

