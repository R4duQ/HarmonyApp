package com.harmony.feature.downloads

/** Engines that can produce a file for the Harmony library. */
enum class DownloadSource(val displayName: String) {
    SPOTIFLAC("SpotiFLAC"),
    SOULSEEK("Soulseek"),

    /**
     * yt-dlp + FFmpeg, converting a YouTube URL to FLAC or MP3 locally.
     *
     * The engine behind this predates the enum entry: MusicDownloadRepository
     * has had identify/downloadYouTubeAsFlac/publishStaged* for a while, and
     * DownloadsViewModel has had a public downloadYouTubeAsFlac() driving
     * them. It simply was never selectable as a source, so the whole path
     * was only reachable incidentally. This exposes it.
     *
     * It differs from the other two in one way that shapes every `when`
     * below: it takes a URL, not a search query. There is no peer list and
     * no provider resolution — you paste a link, Harmony identifies it via
     * oEmbed, and converts.
     */
    YTCONVERTER("YT Converter"),
}

data class DownloadHistoryItem(
    val title: String,
    val artist: String,
    val source: DownloadSource,
    val provider: String?,
    val format: String?,
    val bitDepth: Int?,
    val sampleRateHz: Int?,
    val fileSizeBytes: Long,
    val downloadedAt: Long,
    val soulseekUsername: String?,
)
