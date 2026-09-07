package com.harmony.core.model

/**
 * Domain model for a single track. This is the model the whole app speaks in;
 * Room entities and MediaStore rows are mapped into this at the data layer
 * and never leak upward.
 */
data class Song(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String?,
    val composer: String?,
    val year: Int?,
    val genre: String?,
    val discNumber: Int?,
    val trackNumber: Int?,
    val durationMs: Long,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val artworkUri: String?,
    val embeddedLyrics: String?,
    /** ReplayGain track gain in dB, parsed from tags at scan time. Null = no tag. */
    val replayGainTrackDb: Float?,
    /** ReplayGain album gain in dB. */
    val replayGainAlbumDb: Float?,
)
