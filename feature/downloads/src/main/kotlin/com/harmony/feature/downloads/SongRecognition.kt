package com.harmony.feature.downloads

import org.json.JSONArray
import org.json.JSONObject

/** A song Shazam recognised from the microphone. */
data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String = "",
    val releaseDate: String = "",
    val label: String = "",
    /** Shazam's page for the song, which links it on the streaming services. */
    val songLink: String = "",
    val coverUrl: String = "",
    val isrc: String = "",
    val recognizedAt: Long = 0L,
) {
    /** "Artist - Title", the form Harmony's SpotiFLAC search expects. */
    val searchQuery: String get() = listOf(artist, title).filter(String::isNotBlank).joinToString(" - ")
    val year: String get() = releaseDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
}

sealed interface RecognitionOutcome {
    data class Match(val song: RecognizedSong) : RecognitionOutcome
    data object NoMatch : RecognitionOutcome
    /** [retryable]: a network or server hiccup; a later attempt in the same listen may work. */
    data class Failed(val message: String, val retryable: Boolean = false) : RecognitionOutcome
}

/**
 * Shazam's song lookup, as the Shazam app itself calls it: one JSON POST with
 * the fingerprint ([ShazamSignature]) of a few seconds of sound, answered
 * with the song or an empty `matches` list. No account or key is involved.
 *
 * This is not a public API. Shazam can change or block it at any time, and
 * it answers 429 when one connection asks too often.
 */
internal object ShazamApi {
    fun url(uuid1: String, uuid2: String): String =
        "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$uuid1/$uuid2" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true" +
            "&hubv5minorversion=v5.1&hidelb=true&video=v3"

    fun body(signature: ShazamSignature, timestampMs: Long, timezone: String): String = JSONObject()
        .put("timezone", timezone)
        .put("signature", JSONObject().put("uri", signature.dataUri()).put("samplems", signature.durationMs))
        .put("timestamp", timestampMs)
        .put("context", JSONObject())
        .put("geolocation", JSONObject())
        .toString()

    fun parse(httpCode: Int, body: String, now: Long): RecognitionOutcome {
        if (httpCode == 429) {
            return RecognitionOutcome.Failed("Shazam is getting too many requests from this connection. Wait a minute and try again.")
        }
        if (httpCode !in 200..299) {
            return RecognitionOutcome.Failed("Shazam didn't answer (HTTP $httpCode). Try again in a moment.", retryable = true)
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return RecognitionOutcome.Failed("Shazam sent an unreadable answer. Try again.", retryable = true)
        val track = root.optJSONObject("track")
        if ((root.optJSONArray("matches")?.length() ?: 0) == 0 || track == null) return RecognitionOutcome.NoMatch
        val title = track.optString("title").trim()
        val artist = track.optString("subtitle").trim()
        if (title.isEmpty() && artist.isEmpty()) return RecognitionOutcome.NoMatch

        // The song section lists Album, Label and Released as title/text pairs.
        val details = HashMap<String, String>()
        val sections = track.optJSONArray("sections")
        for (i in 0 until (sections?.length() ?: 0)) {
            val metadata = sections?.optJSONObject(i)?.optJSONArray("metadata") ?: continue
            for (j in 0 until metadata.length()) {
                val entry = metadata.optJSONObject(j) ?: continue
                val text = entry.optString("text").trim()
                if (text.isNotEmpty()) details.putIfAbsent(entry.optString("title"), text)
            }
        }
        val images = track.optJSONObject("images")
        return RecognitionOutcome.Match(
            RecognizedSong(
                title = title,
                artist = artist,
                album = details["Album"].orEmpty(),
                releaseDate = details["Released"].orEmpty(),
                label = details["Label"].orEmpty(),
                songLink = track.optString("url").ifBlank { track.optJSONObject("share")?.optString("href").orEmpty() }.trim(),
                coverUrl = (images?.optString("coverarthq")?.takeIf(String::isNotBlank) ?: images?.optString("coverart")).orEmpty().trim(),
                isrc = track.optString("isrc").trim(),
                recognizedAt = now,
            ),
        )
    }
}

/** 16-bit mono PCM helpers for the microphone recording. */
internal object Pcm {
    /** Loudness of the first [count] samples, 0..1. */
    fun level(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) { val s = samples[i] / 32768.0; sum += s * s }
        return kotlin.math.sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Brings a recording to the level of an ordinary close-up one: removes
     * the DC offset and amplifies so the loudest 0.1 % of samples reach
     * half of full scale, at most [MAX_GAIN] times.
     *
     * Harmony records without the phone's automatic gain, so a song across
     * the room arrives far quieter than in the Shazam app. Gain doesn't move
     * the fingerprint's peaks, but it keeps their loudness values in the
     * usual range instead of tens of decibels below it.
     */
    fun normalize(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val mean = samples.sumOf { it.toLong() }.toDouble() / samples.size
        val magnitudes = IntArray(samples.size) { kotlin.math.abs(samples[it] - mean).toInt() }.apply { sort() }
        val loud = magnitudes[((magnitudes.size - 1) * 0.999).toInt()].coerceAtLeast(1)
        val gain = (TARGET_PEAK / loud).coerceIn(1.0, MAX_GAIN)
        return ShortArray(samples.size) { ((samples[it] - mean) * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** A loudness (0..1 RMS) as a meter position: -65 dBFS and below is 0, -20 dBFS and above is 1. */
    fun meter(level: Float): Float {
        if (level <= 0f) return 0f
        val db = 20 * kotlin.math.log10(level.toDouble())
        return ((db + 65) / 45).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Converts the first [count] samples from [from] Hz to [to] Hz with a
     * windowed-sinc low-pass, so nothing above the new Nyquist folds back
     * into the bands the fingerprint uses. Only needed on phones that can't
     * record at 16 kHz directly.
     */
    fun resample(samples: ShortArray, count: Int, from: Int, to: Int): ShortArray {
        if (from == to) return samples.copyOf(count)
        val ratio = from.toDouble() / to
        val cutoff = 0.45 * minOf(1.0, to.toDouble() / from) // cycles per input sample
        val out = ShortArray((count / ratio).toInt())
        for (i in out.indices) {
            val centre = i * ratio
            val first = maxOf(0, kotlin.math.ceil(centre - TAPS).toInt())
            val last = minOf(count - 1, kotlin.math.floor(centre + TAPS).toInt())
            var acc = 0.0
            var weight = 0.0
            for (k in first..last) {
                val x = k - centre
                val sinc = if (x == 0.0) 1.0 else kotlin.math.sin(2 * kotlin.math.PI * cutoff * x) / (2 * kotlin.math.PI * cutoff * x)
                val window = 0.5 + 0.5 * kotlin.math.cos(kotlin.math.PI * x / (TAPS + 1))
                val w = sinc * window
                acc += samples[k] * w
                weight += w
            }
            out[i] = (if (weight == 0.0) 0.0 else acc / weight).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private const val TAPS = 24
    private const val TARGET_PEAK = 16_384.0
    const val MAX_GAIN = 64.0
}

/** The last recognitions, newest first, as stored in preferences. */
internal object RecognitionHistoryCodec {
    const val MAX = 30

    /** Adds [song] at the top; the same song recognised again moves up instead of repeating. */
    fun add(history: List<RecognizedSong>, song: RecognizedSong): List<RecognizedSong> =
        (listOf(song) + history.filterNot { it.sameSongAs(song) }).take(MAX)

    fun encode(history: List<RecognizedSong>): String = JSONArray().apply {
        history.forEach { s ->
            put(JSONObject().apply {
                put("title", s.title); put("artist", s.artist); put("album", s.album)
                put("release_date", s.releaseDate); put("label", s.label); put("song_link", s.songLink)
                put("cover", s.coverUrl); put("isrc", s.isrc); put("at", s.recognizedAt)
            })
        }
    }.toString()

    fun decode(raw: String?): List<RecognizedSong> {
        val array = runCatching { JSONArray(raw.orEmpty()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            RecognizedSong(
                title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album"),
                releaseDate = o.optString("release_date"), label = o.optString("label"),
                songLink = o.optString("song_link"), coverUrl = o.optString("cover"), isrc = o.optString("isrc"),
                recognizedAt = o.optLong("at"),
            ).takeIf { it.title.isNotBlank() || it.artist.isNotBlank() }
        }
    }

    private fun RecognizedSong.sameSongAs(other: RecognizedSong): Boolean =
        (isrc.isNotBlank() && isrc.equals(other.isrc, ignoreCase = true)) ||
            (title.equals(other.title, ignoreCase = true) && artist.equals(other.artist, ignoreCase = true))
}
