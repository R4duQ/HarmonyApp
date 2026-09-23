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
