package com.harmony.feature.downloads

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A song AudD recognised from the microphone. */
data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String = "",
    val releaseDate: String = "",
    val label: String = "",
    /** AudD's lis.tn page, which links the song on every streaming service. */
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
    /** [needsToken]: the free requests are used up or the token is wrong; ask for one. */
    data class Failed(val message: String, val needsToken: Boolean = false) : RecognitionOutcome
}

/**
 * The AudD music recognition API (https://docs.audd.io): one multipart POST
 * with a short recording, answered with the song or `result: null`.
 *
 * A token is optional. Without one AudD answers a small number of requests a
 * day, which is enough to try the feature; past that it returns error 901
 * and Harmony asks for a token.
 */
internal object AudDApi {
    const val ENDPOINT = "https://api.audd.io/"

    /** Extra catalogues to attach: they carry the cover art and the ISRC. */
    private const val RETURN = "deezer,apple_music,spotify"

    fun multipartBody(boundary: String, token: String?, wav: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(wav.size + 1024)
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        token?.trim()?.takeIf(String::isNotEmpty)?.let { field("api_token", it) }
        field("return", RETURN)
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"harmony-listen.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray(),
        )
        out.write(wav)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    fun parse(body: String, now: Long): RecognitionOutcome {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return RecognitionOutcome.Failed("The recognition service sent an unreadable answer. Try again.")
        if (root.optString("status") != "success") {
            val error = root.optJSONObject("error")
            val code = error?.optInt("error_code") ?: 0
            return when (code) {
                900 -> RecognitionOutcome.Failed("The AudD token isn't valid. Check it and paste it again.", needsToken = true)
                901 -> RecognitionOutcome.Failed("The free recognitions for today are used up. Add your own AudD token to keep going.", needsToken = true)
                902 -> RecognitionOutcome.Failed("Your AudD token has no recognitions left. Top it up on audd.io or use another token.", needsToken = true)
                // Fingerprinting found nothing usable: silence, noise or speech.
                300 -> RecognitionOutcome.NoMatch
                else -> RecognitionOutcome.Failed(
                    error?.optString("error_message")?.takeIf(String::isNotBlank)
                        ?.let { "Recognition failed: $it" }
                        ?: "Recognition failed. Try again.",
                )
            }
        }
        val result = root.optJSONObject("result") ?: return RecognitionOutcome.NoMatch
        val title = result.optString("title").trim()
        val artist = result.optString("artist").trim()
        if (title.isEmpty() && artist.isEmpty()) return RecognitionOutcome.NoMatch

        val deezer = result.optJSONObject("deezer")
        val apple = result.optJSONObject("apple_music")
        val spotify = result.optJSONObject("spotify")
        val cover = deezer?.optJSONObject("album")?.optString("cover_xl")?.takeIf(String::isNotBlank)
            ?: apple?.optJSONObject("artwork")?.optString("url")?.takeIf(String::isNotBlank)
                ?.replace("{w}", "1000")?.replace("{h}", "1000")
            ?: spotify?.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
            ?: ""
        val isrc = deezer?.optString("isrc")?.takeIf(String::isNotBlank)
            ?: apple?.optString("isrc")?.takeIf(String::isNotBlank)
            ?: spotify?.optJSONObject("external_ids")?.optString("isrc")
            ?: ""
        return RecognitionOutcome.Match(
            RecognizedSong(
                title = title,
                artist = artist,
                album = result.optString("album").trim(),
                releaseDate = result.optString("release_date").trim(),
                label = result.optString("label").trim(),
                songLink = result.optString("song_link").trim(),
                coverUrl = cover,
                isrc = isrc.trim(),
                recognizedAt = now,
            ),
        )
    }
}

/** 16-bit mono PCM helpers for the microphone recording. */
internal object Pcm {
    /**
     * Halves the sample rate by averaging each pair of samples. Averaging is
     * a crude low-pass, enough to keep the fold-over out of the band a
     * fingerprint uses, and it halves the upload.
     */
    fun decimateBy2(samples: ShortArray): ShortArray =
        ShortArray(samples.size / 2) { i -> ((samples[2 * i].toInt() + samples[2 * i + 1].toInt()) / 2).toShort() }

    /** Loudness of a block, 0..1, for the listening animation. */
    fun level(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) { val s = samples[i] / 32768.0; sum += s * s }
        return kotlin.math.sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    }

    /** A canonical RIFF/WAVE file around [samples]. */
    fun wav(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }
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
