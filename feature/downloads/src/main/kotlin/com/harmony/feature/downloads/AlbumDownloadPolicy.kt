package com.harmony.feature.downloads

import com.harmony.domain.library.repository.AlbumJourney
import com.harmony.domain.library.repository.AlbumJourneyTrack

internal object AlbumDownloadPolicy {
    fun source(value: String): DownloadSource = DownloadSource.entries.firstOrNull {
        it.name == value && it != DownloadSource.YTCONVERTER
    } ?: throw IllegalArgumentException("Choose SpotiFLAC or Soulseek for the album.")

    fun formatFor(source: DownloadSource, format: SpotiFlacOutputFormat): SpotiFlacOutputFormat {
        require(source != DownloadSource.YTCONVERTER) { "Album downloads need SpotiFLAC or Soulseek." }
        return if (source == DownloadSource.SOULSEEK && format == SpotiFlacOutputFormat.FLAC_HI_RES_96) {
            SpotiFlacOutputFormat.FLAC_LOSSLESS
        } else format
    }

    /** Worker input is a snapshot: editing preferences must never add songs to it. */
    fun tracksFor(album: AlbumJourney, requestedIds: Set<String>?): List<AlbumJourneyTrack> {
        val ids = requestedIds ?: album.tracks.filter { it.selectedForDownload }.map { it.id }.toSet()
        require(ids.isNotEmpty()) { "Select at least one track." }
        require(album.tracks.map { it.id }.toSet().containsAll(ids)) { "The album selection is no longer valid. Select tracks again." }
        return album.tracks.filter { it.id in ids }.sortedWith(compareBy<AlbumJourneyTrack> { it.disc }.thenBy { it.number })
    }

    /** How far a downloaded file's length may be from the edition's track and still be that recording. */
    const val RECORDING_TOLERANCE_MS = 5_000L

    /**
     * Whether a downloaded file is the recording this edition lists: its
     * length is known and within [RECORDING_TOLERANCE_MS] of the track's. A
     * live take, an extended mix or a radio edit of the same title is another
     * recording, and a file whose length cannot be read proves nothing.
     */
    fun isSameRecording(expectedMs: Long, actualMs: Long): Boolean =
        actualMs > 0 && kotlin.math.abs(actualMs - expectedMs) <= RECORDING_TOLERANCE_MS

    fun downloadLabel(album: AlbumJourney): String = when {
        album.selectedMissing.isEmpty() -> "Select tracks to download"
        album.availableCount == 0 && album.selectedMissing.size == album.tracks.size -> "Download full album (${album.tracks.size})"
        album.selectedMissing.size == album.tracks.size - album.availableCount -> "Download all missing (${album.selectedMissing.size})"
        else -> "Download selected (${album.selectedMissing.size})"
    }

    fun selectedIds(album: AlbumJourney, trackId: String?, selected: Boolean): Set<String> = album.tracks.filter {
        if (it.uri == null && (trackId == null || it.id == trackId)) selected else it.selectedForDownload
    }.map { it.id }.toSet()
}
