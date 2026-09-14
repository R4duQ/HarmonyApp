package com.harmony.feature.downloads

import java.io.File

/** Identifies which workflow owns a provider-verification resume. */
enum class SpotiFlacRequestOwner {
    DOWNLOADS,
    PLAYLIST_TRANSFER,
    ALBUM_DOWNLOAD,
}


enum class SpotiFlacOutputFormat(
    val label: String,
    val summary: String,
    val extension: String,
    val historyLabel: String,
    val isLosslessOutput: Boolean,
) {
    FLAC_LOSSLESS(
        label = "FLAC CD",
        summary = "FLAC up to 16-bit / 44.1 kHz, with album tags and cover artwork. Lower native resolutions are preserved.",
        extension = "flac",
        historyLabel = "FLAC",
        isLosslessOutput = true,
    ),
    FLAC_HI_RES_96(
        label = "FLAC max 24/96",
        summary = "Best available lossless source, up to 24-bit / 96 kHz per track. Lower resolutions stay native; higher ones are reduced to the limit.",
        extension = "flac",
        historyLabel = "FLAC",
        isLosslessOutput = true,
    ),
    MP3_320(
        label = "MP3 320 kbps",
        summary = "Downloads a verified lossless source first, then encodes a local 320 kbps MP3.",
        extension = "mp3",
        historyLabel = "MP3 320 kbps",
        isLosslessOutput = false,
    );

    companion object {
        fun fromName(value: String?): SpotiFlacOutputFormat =
            entries.firstOrNull { it.name == value } ?: FLAC_LOSSLESS
    }
}

enum class SpotiFlacStage(val label: String) {
    PREPARING("Preparing SpotiFLAC…"),
    PROVIDERS("Preparing lossless providers…"),
    RESOLVING("Resolving track…"),
    DOWNLOADING("Downloading…"),
    FINALIZING("Finalizing file…"),
    VALIDATING("Validating audio…"),
    IMPORTING("Importing into Harmony…"),
    COMPLETED("Complete"),
}

data class SpotiFlacVerificationChallenge(
    val providerId: String,
    val authenticated: Boolean = false,
    val pending: Boolean = false,
    val verificationUrl: String? = null,
    val callbackUrl: String? = null,
    val instructions: String? = null,
    /** Redacted JSON only. Never place provider credentials/cookies/tokens here. */
    val safeDetails: String? = null,
)



data class SpotiFlacVerificationCallbackResult(
    val accepted: Boolean,
    val providerId: String,
    val credentialKind: String? = null,
    val message: String,
)

data class SpotiFlacTransferProgress(
    val stage: SpotiFlacStage,
    val fraction: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val provider: String? = null,
    val detail: String? = null,
)

data class SpotiFlacDownloadedFile(
    val tempFile: File,
    val suggestedFileName: String,
    val provider: String?,
    val outputFormat: SpotiFlacOutputFormat,
    val bitrateKbps: Int? = null,
    val bitDepth: Int?,
    val sampleRateHz: Int?,
    val codec: String?,
    val isrc: String?,
    val originalTrackId: String?,
)

data class StagedAudioInfo(
    val codec: String,
    val bitDepth: Int?,
    val sampleRateHz: Int?,
    val durationMs: Long,
)

class SpotiFlacException(
    message: String,
    val errorType: String? = null,
    cause: Throwable? = null,
    val technicalDetails: String? = null,
    val provider: String? = null,
    val verificationUrl: String? = null,
    val verificationChallenge: SpotiFlacVerificationChallenge? = null,
) : Exception(message, cause)
