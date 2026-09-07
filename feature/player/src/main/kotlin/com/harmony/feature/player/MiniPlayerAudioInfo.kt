package com.harmony.feature.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.harmony.core.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Audio facts shown by the compact player without extending the Room schema. */
internal data class MiniPlayerAudioInfo(
    val format: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val isLossless: Boolean,
) {
    val summary: String
        get() = buildList {
            add(format ?: "AUDIO")
            bitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
            sampleRateHz?.takeIf { it > 0 }?.let { add(formatSampleRate(it)) }
        }.joinToString("  ·  ")

    companion object {
        fun initial(song: Song): MiniPlayerAudioInfo {
            val extension = extensionFromName(Uri.parse(song.uri).lastPathSegment)
            return fromResolvedTrack(
                mimeType = null,
                extension = extension,
                bitrateKbps = song.bitrateKbps,
                sampleRateHz = song.sampleRateHz,
            )
        }
    }
}

/**
 * Resolves codec/container details off the main thread. MediaExtractor is used
 * before the filename because `.m4a` may contain either lossless ALAC or lossy
 * AAC; the extension alone cannot make that distinction.
 */
internal suspend fun resolveMiniPlayerAudioInfo(
    context: Context,
    song: Song,
): MiniPlayerAudioInfo = withContext(Dispatchers.IO) {
    val uri = Uri.parse(song.uri)
    var trackMime: String? = null
    var bitrateKbps = song.bitrateKbps?.takeIf { it > 0 }
    var sampleRateHz = song.sampleRateHz?.takeIf { it > 0 }

    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        for (index in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(index)
            val mime = trackFormat.stringOrNull(MediaFormat.KEY_MIME)
            if (!mime.orEmpty().startsWith("audio/", ignoreCase = true)) continue

            trackMime = mime
            if (bitrateKbps == null) {
                bitrateKbps = trackFormat.intOrNull(MediaFormat.KEY_BIT_RATE)
                    ?.takeIf { it > 0 }
                    ?.div(1_000)
            }
            if (sampleRateHz == null) {
                sampleRateHz = trackFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                    ?.takeIf { it > 0 }
            }
            break
        }
    } catch (_: Exception) {
        // Metadata already stored on Song remains the fallback for codecs an
        // OEM MediaExtractor does not understand (APE/WavPack, for example).
    } finally {
        runCatching { extractor.release() }
    }

    val displayName = queryDisplayName(context, uri)
    val extension = extensionFromName(displayName)
        ?: extensionFromName(uri.lastPathSegment)

    fromResolvedTrack(
        mimeType = trackMime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull(),
        extension = extension,
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
    )
}

internal fun fromResolvedTrack(
    mimeType: String?,
    extension: String?,
    bitrateKbps: Int?,
    sampleRateHz: Int?,
): MiniPlayerAudioInfo {
    val normalizedMime = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    val normalizedExtension = extension?.trim()?.lowercase(Locale.ROOT).orEmpty()

    return MiniPlayerAudioInfo(
        format = audioFormatLabel(normalizedMime, normalizedExtension),
        bitrateKbps = bitrateKbps?.takeIf { it > 0 },
        sampleRateHz = sampleRateHz?.takeIf { it > 0 },
        isLossless = isKnownLosslessAudio(normalizedMime, normalizedExtension),
    )
}

internal fun audioFormatLabel(mimeType: String, extension: String): String? = when {
    "flac" in mimeType -> "FLAC"
    "alac" in mimeType -> "ALAC"
    "wavpack" in mimeType -> "WAVPACK"
    mimeType.contains("monkeys-audio") || mimeType.endsWith("/ape") || mimeType.endsWith("/x-ape") -> "APE"
    "dsd" in mimeType || "dsf" in mimeType || "dff" in mimeType -> when (extension) {
        "dff" -> "DFF"
        else -> "DSF"
    }
    // Several Android/OEM extractors expose the decoder output as generic
    // PCM (`audio/raw`) instead of the source codec. In that case the file
    // extension is the only container identity we still have. Never turn a
    // `.flac` into WAV merely because its decoded samples are PCM.
    mimeType == "audio/raw" || "pcm" in mimeType ->
        extension.takeIf { it.isNotBlank() }?.let(::extensionLabel) ?: "PCM"
    // Some MediaStore implementations also return a generic WAV MIME for
    // other lossless containers. Prefer a known filename extension there too.
    mimeType.contains("wav") ->
        extension.takeIf { it.isNotBlank() }?.let(::extensionLabel) ?: "WAV"
    mimeType == "audio/mpeg" || mimeType == "audio/mp3" -> "MP3"
    "mp4a" in mimeType || "aac" in mimeType -> "AAC"
    "opus" in mimeType -> "OPUS"
    "vorbis" in mimeType -> "OGG VORBIS"
    mimeType == "audio/ogg" -> "OGG"
    "eac3" in mimeType -> "E-AC-3"
    "ac3" in mimeType -> "AC-3"
    extension.isNotBlank() -> extensionLabel(extension)
    else -> null
}

internal fun isKnownLosslessAudio(mimeType: String, extension: String): Boolean {
    // `audio/raw` describes decoded PCM on affected phones, not necessarily a
    // WAV source. Use the source extension when available so an MP3/AAC is not
    // given a false LOSSLESS badge and a FLAC remains correctly identified.
    if (mimeType == "audio/raw" || "pcm" in mimeType || "wav" in mimeType) {
        return when {
            extension in LOSSLESS_EXTENSIONS -> true
            extension in LOSSY_OR_AMBIGUOUS_EXTENSIONS -> false
            extension.isBlank() -> true
            else -> false
        }
    }
    if (mimeType in LOSSLESS_MIME_TYPES || LOSSLESS_MIME_MARKERS.any { mimeType.contains(it) }) {
        return true
    }
    if (LOSSY_MIME_MARKERS.any { mimeType.contains(it) }) return false
    return extension in LOSSLESS_EXTENSIONS
}

private fun extensionLabel(extension: String): String = when (extension) {
    "aif", "aiff" -> "AIFF"
    "m4a" -> "M4A"
    "pcm", "raw" -> "PCM"
    "wav", "wave" -> "WAV"
    "wv" -> "WAVPACK"
    else -> extension.uppercase(Locale.ROOT)
}

private fun extensionFromName(name: String?): String? = name
    ?.substringAfterLast('.', missingDelimiterValue = "")
    ?.substringBefore('?')
    ?.substringBefore('#')
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf { value -> value.length in 2..8 && value.all { it.isLetterOrDigit() } }

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) cursor.getString(index) else null
    }
}.getOrNull()

private fun MediaFormat.stringOrNull(key: String): String? =
    runCatching { if (containsKey(key)) getString(key) else null }.getOrNull()

private fun MediaFormat.intOrNull(key: String): Int? =
    runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()

private fun formatSampleRate(sampleRateHz: Int): String {
    val wholeKhz = sampleRateHz / 1_000
    return if (sampleRateHz % 1_000 == 0) {
        "$wholeKhz kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", sampleRateHz / 1_000.0)
    }
}

private val LOSSLESS_MIME_TYPES = setOf(
    "audio/flac",
    "audio/x-flac",
    "audio/alac",
    "audio/x-alac",
    "audio/raw",
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
    "audio/aiff",
    "audio/x-aiff",
    "audio/ape",
    "audio/x-ape",
    "audio/wavpack",
    "audio/x-wavpack",
    "audio/dsf",
    "audio/x-dsf",
    "audio/dff",
    "audio/x-dff",
)

private val LOSSLESS_MIME_MARKERS = setOf(
    "flac",
    "alac",
    "wavpack",
    "monkeys-audio",
    "lossless",
    "dsd",
    "pcm",
)

private val LOSSY_MIME_MARKERS = setOf(
    "mpeg",
    "mp3",
    "mp4a",
    "aac",
    "opus",
    "vorbis",
    "ac3",
    "eac3",
    "amr",
)

private val LOSSLESS_EXTENSIONS = setOf(
    "flac",
    "alac",
    "wav",
    "wave",
    "aif",
    "aiff",
    "ape",
    "wv",
    "dsf",
    "dff",
    "pcm",
    "raw",
)

private val LOSSY_OR_AMBIGUOUS_EXTENSIONS = setOf(
    "mp3",
    "aac",
    "m4a",
    "ogg",
    "oga",
    "opus",
    "ac3",
    "eac3",
    "amr",
)
