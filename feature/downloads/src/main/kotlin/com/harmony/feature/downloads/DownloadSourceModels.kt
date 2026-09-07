package com.harmony.feature.downloads

/** Engines that can produce a file for the Harmony library. */
enum class DownloadSource(val displayName: String) {
    SPOTIFLAC("SpotiFLAC"),
    SOULSEEK("Soulseek"),
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
