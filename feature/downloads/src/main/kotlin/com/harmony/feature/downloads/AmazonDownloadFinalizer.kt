package com.harmony.feature.downloads

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Completes Amazon's provider-supplied MOV descriptor before the existing FLAC finalizer. */
internal object AmazonDownloadFinalizer {
    // Output is deliberately not a data class: neither commands nor key material belong in diagnostics.
    class Execution(val success: Boolean, val output: String)
    class Completed(val file: File, val codec: String)
    class Failure(val stage: String, message: String) : Exception(message)

    suspend fun finish(
        provider: String,
        response: JSONObject,
        source: File,
        jobDir: File,
        execute: suspend (List<String>) -> Execution,
    ): Completed {
        if (provider != "amazon") throw Failure("provider", "This finalizer only handles Amazon Music.")
        val descriptor = response.optJSONObject("decryption")?.takeIf { it.optString("key").isNotBlank() }
        val rawKey = descriptor?.optString("key") ?: response.optString("decryption_key")
        val strategy = descriptor?.optString("strategy").orEmpty().trim().lowercase(Locale.ROOT)
        if (strategy !in STRATEGIES) throw Failure("descriptor", "Amazon returned an unsupported audio finalization strategy.")
        val format = descriptor?.optString("input_format").orEmpty().trim().lowercase(Locale.ROOT)
        if (format !in INPUT_FORMATS) throw Failure("descriptor", "Amazon returned an unsupported audio container.")
        val key = normalizeKey(rawKey)
            ?: throw Failure("descriptor", "Amazon returned an invalid audio finalization key. Retry the download.")
        if (!source.isFile || source.length() == 0L ||
            !source.canonicalPath.startsWith(jobDir.canonicalPath + File.separator)) {
            throw Failure("source", "Amazon's downloaded audio file is missing from this download.")
        }
        val output = File(jobDir, "harmony-amazon-${UUID.randomUUID()}.m4a")
        var completed = false
        try {
            // Input format must precede -i: the provider may name encrypted MOV bytes .flac.
            // Copy the actual codec first; do not turn AAC/other lossy content into fake FLAC.
            val remux = execute(listOf(
                "-v", "error", "-xerror", "-nostdin", "-hide_banner", "-y",
                "-decryption_key", key, "-f", "mov", "-i", source.absolutePath,
                "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1",
                "-c:a", "copy", "-strict", "-2", "-movflags", "+faststart",
                "-f", "mp4", output.absolutePath,
            ))
            if (!remux.success || !isMp4(output)) {
                throw Failure("remux", "Harmony could not finish Amazon Music's audio on this phone. Retry the download.")
            }
            // Stream-copy can succeed with a wrong key. Decode the entire private result, and use
            // FFmpeg's actual input codec rather than trusting the provider's codec/extension.
            val validation = execute(listOf(
                "-v", "info", "-xerror", "-nostdin", "-hide_banner",
                "-i", output.absolutePath, "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-f", "null", "-",
            ))
            if (!validation.success) throw Failure("decode", "Amazon's audio could not be decoded completely. Retry the download.")
            val codec = INPUT_CODEC.find(validation.output)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            if (codec !in setOf("flac", "alac")) {
                throw Failure("codec", "Amazon did not return verified lossless FLAC or ALAC audio for this download.")
            }
            completed = true
            return Completed(output, checkNotNull(codec))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            // FFmpeg and ProcessBuilder errors may echo arguments. Never propagate raw exceptions,
            // output or a cause containing the key into the UI, crash reports or provider diagnostics.
            throw Failure("execution", "Amazon audio finalization could not complete on this phone.")
        } finally {
            if (!completed) output.delete()
        }
    }

    private fun isMp4(file: File): Boolean {
        if (!file.isFile || file.length() < 12L) return false
        val header = ByteArray(8)
        return file.inputStream().use { it.read(header) } == header.size &&
            header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))
    }

    private fun normalizeKey(raw: String): String? {
        val text = raw.trim().removePrefix("0x").removePrefix("0X")
        val hex = text.replace(Regex("[\\s:-]"), "")
        if (hex.matches(Regex("[0-9a-fA-F]{32}"))) return hex.lowercase(Locale.ROOT)
        val decoded = runCatching { Base64.getDecoder().decode(text.replace(Regex("\\s"), "")) }.getOrNull()
        return decoded?.takeIf { it.size == 16 }?.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private val STRATEGIES = setOf("", "ffmpeg.mov_key", "ffmpeg_mov_key", "mov_decryption_key", "mp4_decryption_key", "ffmpeg.mp4_decryption_key")
    private val INPUT_FORMATS = setOf("", "mov", "mp4", "m4a", "mov,mp4,m4a,3gp,3g2,mj2")
    private val INPUT_CODEC = Regex("Stream #0:[^\\r\\n]*?\\bAudio:\\s*([A-Za-z0-9_]+)")
}

