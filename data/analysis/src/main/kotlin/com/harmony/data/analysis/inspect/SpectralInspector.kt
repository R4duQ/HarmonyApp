package com.harmony.data.analysis.inspect

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.harmony.data.analysis.decode.AudioDecoder
import com.harmony.domain.analysis.model.SpectralReport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Inspects one file's spectrum to judge whether a lossless container really
 * holds lossless audio.
 *
 * How the detection works. Every lossy encoder throws away everything above a
 * cutoff — roughly 16 kHz for 128 kbps MP3, 19 kHz for 320, 20 kHz for AAC.
 * Re-encoding that to FLAC preserves the silence above the cutoff perfectly,
 * so the file is bit-for-bit lossless with respect to an already-damaged
 * source. Real lossless audio from a CD carries content up to the Nyquist
 * limit of about 22 kHz, and rolls off gently rather than stopping dead.
 *
 * So there are two signals, and both matter:
 *   - WHERE the energy stops (a cutoff well below Nyquist is suspicious), and
 *   - HOW SHARPLY it stops (an encoder's brick wall drops tens of dB in about
 *     a kilohertz; natural rolloff is gradual).
 *
 * Using both is what keeps a quiet acoustic recording with little top end
 * from being called a transcode. That's also why the verdict is worded as
 * likelihood and always accompanied by its reasoning: a track can genuinely
 * lack treble, and no spectrum can prove provenance.
 *
 * Deliberately NOT part of the native analyzer. harmony_dsp.h's feature
 * layout is versioned, and adding to it forces re-analysis of the entire
 * library — a heavy price for a diagnostic run on one song at a time. The FFT
 * here is plain Kotlin: a few hundred frames per file is nothing, and it
 * keeps the native contract untouched.
 */
@Singleton
class SpectralInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val decoder: AudioDecoder,
) {

    /** Power of two. 4096 at 44.1 kHz gives ~10.8 Hz bins — ample to place a cutoff. */
    private val fftSize = 4096
    private val bins = fftSize / 2

    /**
     * Cap on audio inspected. The spectral signature is stable across a
     * track, so 90 seconds is plenty and keeps the check responsive.
     */
    private val maxDurationUs = 90_000_000L

    suspend fun inspect(
        uriString: String,
        fileName: String,
        fileSizeBytes: Long,
        durationMs: Long = 0L,
    ): SpectralReport {
        val container = readContainer(uriString, fileName)
        // Prefer the container's own duration; the caller may not know it
        // (files picked through SAF have no library row).
        val duration = if (container.durationUs > 0) {
            container.durationUs / 1000
        } else {
            durationMs
        }

        val acc = DoubleArray(bins)
        var frames = 0
        var sampleRate = 44_100

        val window = FloatArray(fftSize) { i ->
            // Hann: low spectral leakage, which matters because we're looking
            // for a shelf. A rectangular window smears energy across bins and
            // would blur exactly the edge we need to locate.
            0.5f - 0.5f * cos(2.0 * Math.PI * i / (fftSize - 1)).toFloat()
        }

        val buf = FloatArray(fftSize)
        var filled = 0
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)

        decoder.decode(
            uriString = uriString,
            maxDurationUs = maxDurationUs,
            onStart = { rate -> sampleRate = rate },
            onPcm = { samples, count ->
                var offset = 0
                while (offset < count) {
                    val take = min(fftSize - filled, count - offset)
                    System.arraycopy(samples, offset, buf, filled, take)
                    filled += take
                    offset += take
                    if (filled == fftSize) {
                        for (i in 0 until fftSize) {
                            re[i] = buf[i] * window[i]
                            im[i] = 0f
                        }
                        fft(re, im)
                        for (k in 0 until bins) {
                            val mag = kotlin.math.sqrt(
                                (re[k] * re[k] + im[k] * im[k]).toDouble()
                            )
                            acc[k] += mag
                        }
                        frames++
                        // 50% overlap: more frames from the same audio, and a
                        // steadier average with the Hann window's tapered ends.
                        System.arraycopy(buf, fftSize / 2, buf, 0, fftSize / 2)
                        filled = fftSize / 2
                    }
                }
            },
        )

        if (frames == 0) {
            return inconclusive(fileName, container, fileSizeBytes, duration, sampleRate)
        }

        // Average, then to dB relative to the loudest bin.
        val avg = FloatArray(bins) { (acc[it] / frames).toFloat() }
        val peak = avg.max().coerceAtLeast(1e-12f)
        val db = FloatArray(bins) { 20f * log10((avg[it] / peak).coerceAtLeast(1e-7f)) }

        val nyquist = sampleRate / 2
        val hzPerBin = nyquist.toFloat() / bins

        // The cutoff is the highest bin still above the noise floor. -75 dB
        // sits below any musical content but above dither and codec noise.
        val floorDb = -75f
        var cutoffBin = bins - 1
        while (cutoffBin > 0 && db[cutoffBin] < floorDb) cutoffBin--
        val cutoffHz = (cutoffBin * hzPerBin).toInt()

        // Sharpness: drop across the ~1 kHz below the cutoff. A brick wall
        // shows tens of dB here; a natural rolloff only a few.
        val kHzInBins = max(1, (1000f / hzPerBin).toInt())
        val below = max(0, cutoffBin - kHzInBins)
        val sharpness = db[below] - db[cutoffBin]

        val verdict = judge(
            claimsLossless = container.claimsLossless,
            cutoffHz = cutoffHz,
            nyquist = nyquist,
            sharpness = sharpness,
            frames = frames,
        )

        return SpectralReport(
            fileName = fileName,
            mimeType = container.mime,
            sampleRate = sampleRate,
            channelCount = container.channels,
            bitDepth = container.bitDepth,
            bitrateKbps = bitrateKbps(container, fileSizeBytes, duration),
            durationMs = duration,
            fileSizeBytes = fileSizeBytes,
            claimsLossless = container.claimsLossless,
            spectrum = db,
            cutoffHz = cutoffHz,
            cutoffSharpnessDb = sharpness,
            verdict = verdict,
            explanation = explain(verdict, cutoffHz, nyquist, sharpness, container),
        )
    }

    // ---------------------------------------------------------------- verdict

    private fun judge(
        claimsLossless: Boolean,
        cutoffHz: Int,
        nyquist: Int,
        sharpness: Float,
        frames: Int,
    ): SpectralReport.Verdict {
        // Under ~10 seconds of analysed audio the average is too noisy to
        // place an edge with confidence.
        if (frames < 100) return SpectralReport.Verdict.INCONCLUSIVE

        // Within 4% of Nyquist there's no headroom left to show a cutoff, so
        // absence of one proves nothing either way.
        val nearNyquist = cutoffHz > nyquist * 0.96f
        // A brick wall: steep AND well below Nyquist.
        val brickWall = sharpness > 25f && cutoffHz < nyquist * 0.92f

        return when {
            !claimsLossless -> SpectralReport.Verdict.LOSSY_AS_LABELLED
            nearNyquist -> SpectralReport.Verdict.GENUINE_LOSSLESS
            brickWall -> SpectralReport.Verdict.LIKELY_TRANSCODE
            // Low cutoff without a steep edge is ambiguous: it's what a
            // muffled or sparse recording looks like too.
            cutoffHz < nyquist * 0.85f -> SpectralReport.Verdict.INCONCLUSIVE
            else -> SpectralReport.Verdict.GENUINE_LOSSLESS
        }
    }

    private fun explain(
        verdict: SpectralReport.Verdict,
        cutoffHz: Int,
        nyquist: Int,
        sharpness: Float,
        container: Container,
    ): String {
        val cut = "%.1f kHz".format(cutoffHz / 1000f)
        val nyq = "%.1f kHz".format(nyquist / 1000f)
        return when (verdict) {
            SpectralReport.Verdict.GENUINE_LOSSLESS ->
                "Content reaches $cut, close to this file's $nyq ceiling, with no " +
                    "encoder cutoff. Consistent with genuine lossless audio."

            SpectralReport.Verdict.LIKELY_TRANSCODE ->
                "Energy stops dead at $cut — ${sharpness.toInt()} dB in about a " +
                    "kilohertz — well below the $nyq ceiling. That sharp edge is the " +
                    "signature of a lossy encoder, so this was probably an MP3 or AAC " +
                    "re-encoded to ${container.codecLabel}. It won't sound better than " +
                    "the lossy file it came from."

            SpectralReport.Verdict.LOSSY_AS_LABELLED ->
                "${container.codecLabel} is a lossy format, and the cutoff at $cut is " +
                    "normal for it. Nothing wrong — just not lossless."

            SpectralReport.Verdict.INCONCLUSIVE ->
                "Content stops around $cut, below the $nyq ceiling, but without the " +
                    "sharp edge an encoder leaves. That's also what quiet or muffled " +
                    "recordings look like, so this isn't conclusive either way."
        }
    }

    // -------------------------------------------------------------- container

    private data class Container(
        val mime: String?,
        val extension: String,
        val channels: Int,
        val bitDepth: Int,
        val declaredBitrate: Int,
        val claimsLossless: Boolean,
        val durationUs: Long = 0L,
    ) {
        val codecLabel: String get() = when {
            mime == null -> extensionCodecLabel(extension) ?: "this file"
            mime.contains("flac") -> "FLAC"
            mime.contains("alac") -> "ALAC"
            mime.contains("mpeg") -> "MP3"
            mime.contains("mp4a") || mime.contains("aac") -> "AAC"
            mime.contains("opus") -> "Opus"
            mime.contains("vorbis") -> "Vorbis"
            mime.contains("raw") || mime.contains("pcm") || mime.contains("wav") ->
                extensionCodecLabel(extension) ?: if (mime.contains("wav")) "WAV" else "PCM"
            extension.isNotBlank() -> extensionCodecLabel(extension) ?: extension.uppercase(Locale.ROOT)
            else -> mime.substringAfter('/').uppercase()
        }
    }

    private fun readContainer(uriString: String, fileName: String): Container {
        val extension = fileName
            .substringAfterLast('.', missingDelimiterValue = "")
            .trim()
            .lowercase(Locale.ROOT)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(uriString), null)
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return Container(
                mime = null,
                extension = extension,
                channels = 0,
                bitDepth = 0,
                declaredBitrate = 0,
                claimsLossless = extension in LOSSLESS_EXTENSIONS,
            )

            val f = extractor.getTrackFormat(index)
            val mime = f.getString(MediaFormat.KEY_MIME)
            Container(
                mime = mime,
                extension = extension,
                channels = f.optInt(MediaFormat.KEY_CHANNEL_COUNT),
                bitDepth = f.optInt("bits-per-sample"),
                declaredBitrate = f.optInt(MediaFormat.KEY_BIT_RATE),
                claimsLossless = containerClaimsLossless(mime, extension),
                durationUs = runCatching {
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        f.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                }.getOrDefault(0L),
            )
        } catch (_: Exception) {
            Container(
                mime = null,
                extension = extension,
                channels = 0,
                bitDepth = 0,
                declaredBitrate = 0,
                claimsLossless = extension in LOSSLESS_EXTENSIONS,
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.optInt(key: String): Int =
        runCatching { if (containsKey(key)) getInteger(key) else 0 }.getOrDefault(0)

    /**
     * Prefer the container's own figure; fall back to size over duration.
     * For a variable-bitrate file the computed average is the honest number
     * anyway, and many FLAC files declare nothing.
     */
    private fun bitrateKbps(c: Container, sizeBytes: Long, durationMs: Long): Int = when {
        c.declaredBitrate > 0 -> c.declaredBitrate / 1000
        durationMs > 0 -> ((sizeBytes * 8.0) / durationMs).toInt()
        else -> 0
    }

    private fun inconclusive(
        fileName: String,
        c: Container,
        sizeBytes: Long,
        durationMs: Long,
        sampleRate: Int,
    ) = SpectralReport(
        fileName = fileName,
        mimeType = c.mime,
        sampleRate = sampleRate,
        channelCount = c.channels,
        bitDepth = c.bitDepth,
        bitrateKbps = bitrateKbps(c, sizeBytes, durationMs),
        durationMs = durationMs,
        fileSizeBytes = sizeBytes,
        claimsLossless = c.claimsLossless,
        spectrum = FloatArray(0),
        cutoffHz = 0,
        cutoffSharpnessDb = 0f,
        verdict = SpectralReport.Verdict.INCONCLUSIVE,
        explanation = "Couldn't decode enough audio from this file to analyse it.",
    )

    // -------------------------------------------------------------------- FFT

    /**
     * In-place iterative radix-2 Cooley-Tukey. [re] and [im] must be a power
     * of two long. Plain Kotlin on purpose — see the class doc.
     */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private companion object {
        val LOSSLESS_MIMES = listOf("flac", "alac", "raw", "wav", "pcm")
        val LOSSLESS_EXTENSIONS = setOf(
            "flac", "alac", "wav", "wave", "aif", "aiff", "ape", "wv",
            "dsf", "dff", "pcm", "raw",
        )
        val LOSSY_OR_AMBIGUOUS_EXTENSIONS = setOf(
            "mp3", "aac", "m4a", "ogg", "oga", "opus", "ac3", "eac3", "amr",
        )

        fun extensionCodecLabel(extension: String): String? = when (extension) {
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "wav", "wave" -> "WAV"
            "aif", "aiff" -> "AIFF"
            "ape" -> "APE"
            "wv" -> "WavPack"
            "dsf" -> "DSF"
            "dff" -> "DFF"
            "pcm", "raw" -> "PCM"
            "mp3" -> "MP3"
            "aac" -> "AAC"
            "m4a" -> "M4A"
            "ogg", "oga" -> "OGG"
            "opus" -> "Opus"
            else -> null
        }

        fun containerClaimsLossless(mime: String?, extension: String): Boolean {
            val normalizedMime = mime?.lowercase(Locale.ROOT).orEmpty()
            val isGenericPcm = normalizedMime.contains("raw") ||
                normalizedMime.contains("pcm") || normalizedMime.contains("wav")
            return when {
                isGenericPcm && extension in LOSSLESS_EXTENSIONS -> true
                isGenericPcm && extension in LOSSY_OR_AMBIGUOUS_EXTENSIONS -> false
                isGenericPcm && extension.isBlank() -> true
                isGenericPcm -> false
                LOSSLESS_MIMES.any { normalizedMime.contains(it) } -> true
                else -> extension in LOSSLESS_EXTENSIONS
            }
        }
    }
}
