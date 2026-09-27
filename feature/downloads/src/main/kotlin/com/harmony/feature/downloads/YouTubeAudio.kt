package com.harmony.feature.downloads

import java.io.File

/** Tags written into a converted YouTube FLAC. Blank values are left out. */
internal data class YouTubeAudioTags(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val year: String = "",
)

/** Pure pieces of the YouTube converter: tags, the FFmpeg command and error wording. */
internal object YouTubeAudio {

    /** yt-dlp errors that usually mean YouTube changed its protocol: update yt-dlp and retry once. */
    val RETRYABLE_MARKERS = listOf(
        "http error 403",
        "403 forbidden",
        "signature solving failed",
        "challenge solving failed",
        "error solving",
        "quickjs",
        "sabr",
        "unable to download video data",
        "player response",
    )

    /**
     * Picks tags from yt-dlp's info JSON fields. Music uploads carry track and
     * artist; ordinary videos only have a title and an uploader, whose
     * auto-generated " - Topic" channels are cleaned up.
     */
    fun tags(
        fallbackTitle: String,
        title: String = "",
        track: String = "",
        artist: String = "",
        creator: String = "",
        uploader: String = "",
        album: String = "",
        releaseYear: Int = 0,
        uploadDate: String = "",
    ): YouTubeAudioTags {
        val year = when {
            releaseYear in 1000..9999 -> releaseYear.toString()
            uploadDate.length >= 4 && uploadDate.take(4).all(Char::isDigit) -> uploadDate.take(4)
            else -> ""
        }
        return YouTubeAudioTags(
            title = track.trim().ifBlank { title.trim() }.ifBlank { fallbackTitle.trim() },
            artist = artist.trim().ifBlank { creator.trim() }.ifBlank { uploader.trim().removeSuffix(" - Topic").trim() },
            album = album.trim(),
            year = year,
        )
    }

    /** Audio only, re-encoded to FLAC. A lossy source stays lossy inside the FLAC. */
    fun ffmpegArguments(input: File, output: File, tags: YouTubeAudioTags): List<String> = buildList {
        addAll(listOf("-v", "error", "-nostdin", "-hide_banner", "-y", "-i", input.absolutePath))
        addAll(listOf("-map", "0:a:0", "-vn", "-map_metadata", "-1", "-c:a", "flac"))
        listOf("title" to tags.title, "artist" to tags.artist, "album" to tags.album, "date" to tags.year)
            .filter { (_, value) -> value.isNotBlank() }
            .forEach { (key, value) -> addAll(listOf("-metadata", "$key=$value")) }
        addAll(listOf("-f", "flac", output.absolutePath))
    }

    fun isOutOfSpace(text: String): Boolean {
        val lower = text.lowercase()
        return "enospc" in lower || "no space left" in lower || "disk full" in lower
    }

    /**
     * What to tell the user when yt-dlp fails, from its error text. Harmony never
     * signs in to YouTube, so account-gated videos are explained, not retried.
     */
    fun failureMessage(text: String): String {
        val lower = text.lowercase()
        return when {
            isOutOfSpace(lower) ->
                "Your phone ran out of storage during the download. Free some space and try again."
            "not a bot" in lower ->
                "YouTube wants this connection to prove it isn't a bot. Switch between Wi-Fi and mobile data or turn off a VPN, then try again. No YouTube account or Premium is needed."
            "age-restricted" in lower || "confirm your age" in lower ->
                "This video is age-restricted. Harmony doesn't sign in to YouTube, so it can't download it."
            "members-only" in lower || "join this channel" in lower ->
                "This video is only for the channel's members, so Harmony can't download it."
            "private video" in lower ->
                "This video is private, so Harmony can't download it."
            "available in your country" in lower || "geo restrict" in lower || "geo-restrict" in lower ->
                "This video isn't available in your country."
            "sign in" in lower || "login" in lower ->
                "YouTube only shows this video to signed-in users. Harmony doesn't sign in to YouTube, so it can't download it."
            RETRYABLE_MARKERS.any(lower::contains) ->
                "YouTube rejected this download after Harmony updated yt-dlp and retried. Try again later; YouTube may have changed its download protocol again."
            "network" in lower || "timed out" in lower || "unable to connect" in lower ->
                "Harmony couldn't reach YouTube. Check your connection and try again."
            else -> "The YouTube download failed. Tap Details for the technical log."
        }
    }
}
