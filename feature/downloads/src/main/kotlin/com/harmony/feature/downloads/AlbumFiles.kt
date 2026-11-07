package com.harmony.feature.downloads

import com.harmony.domain.library.repository.AlbumJourneyTrack

/**
 * Where an album's songs go and what they are called:
 * `<download folder>/<Album artist>/<Album>/01 - Title.flac` (on a
 * multi-disc album `1-01 - Title.flac`, so both discs sort in order).
 *
 * Names come from metadata, so they are made safe here: no folder
 * separators or characters Android's storage refuses, no "." or ".."
 * segments, nothing that starts or ends in a dot or a space, and a bounded
 * length (the whole path must fit Android's limits).
 */
object AlbumFiles {
    private const val MAX_SEGMENT = 80
    private const val MAX_NAME = 150
    private val forbidden = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** [names] as safe folder names; blank ones dropped. */
    fun folders(names: List<String>): List<String> = names.mapNotNull { segment(it, MAX_SEGMENT) }

    /** The album's folder: artist, then album. */
    fun albumFolders(albumArtist: String, album: String): List<String> =
        folders(listOf(albumArtist.ifBlank { "Unknown artist" }, album.ifBlank { "Unknown album" }))

    /** MediaStore's RELATIVE_PATH for [base] plus [subfolders], with the trailing slash it expects. */
    fun relativePath(base: String, subfolders: List<String>): String =
        (listOf(base.trimEnd('/')) + folders(subfolders)).joinToString("/") + "/"

    /** "01 - Title.flac", or "2-01 - Title.flac" on a multi-disc album. */
    fun trackFileName(track: AlbumJourneyTrack, multiDisc: Boolean, extension: String): String {
        val number = track.number.coerceAtLeast(0).toString().padStart(2, '0')
        val prefix = if (multiDisc) "${track.disc.coerceAtLeast(1)}-$number" else number
        val title = segment(track.title, MAX_NAME) ?: "Track"
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "flac" }
        return "$prefix - $title.$ext"
    }

    private fun segment(value: String, max: Int): String? {
        val cleaned = value.replace(forbidden, "_")
            .replace(Regex("\\s{2,}"), " ")
            .trim().trim('.').trim()
            .take(max).trim().trim('.')
        return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }
}
