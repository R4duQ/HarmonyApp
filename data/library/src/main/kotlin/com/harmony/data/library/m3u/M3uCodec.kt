package com.harmony.data.library.m3u

import com.harmony.core.model.Song

/**
 * Minimal Extended-M3U reader/writer. Pure Kotlin, fully unit-testable.
 *
 * Export writes #EXTM3U with #EXTINF lines (duration + "Artist - Title") and
 * the song's URI. Other players on the same device can open content URIs;
 * for portability to desktop players the settings UI (Phase 8) will offer
 * a "use file paths" toggle once SAF paths are resolvable.
 *
 * Import is lenient by necessity: playlists in the wild contain absolute
 * paths from other devices, Windows separators, and stray blank lines.
 * We normalise separators and match on decreasing path-suffix specificity —
 * the same strategy foobar2000/Poweramp use, because it's the only thing
 * that works across path schemes.
 */
object M3uCodec {

    fun export(songs: List<Song>): String = buildString {
        appendLine("#EXTM3U")
        songs.forEach { song ->
            appendLine("#EXTINF:${song.durationMs / 1000},${song.artist} - ${song.title}")
            appendLine(song.uri)
        }
    }

    /** @return library song ids in playlist order for entries that matched. */
    fun matchImport(m3uContent: String, library: List<Song>): List<Long> {
        val entries = m3uContent.removePrefix("\uFEFF").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.replace('\\', '/') }
            .toList()
        if (entries.isEmpty()) return emptyList()

        // Index library by decoded, normalised URI path tails.
        val decoded = library.map { it to normalise(it.uri) }

        return entries.mapNotNull { entry ->
            // Preserve content-provider authority and literal '+' in exact URI matches.
            library.firstOrNull { it.uri == entry }?.let { return@mapNotNull it.id }
            val target = normalise(entry)
            // exact URI match first, then longest suffix match
            decoded.firstOrNull { (_, path) -> path == target }?.first?.id
                ?: bestSuffixMatch(target, decoded)
        }
    }

    private fun bestSuffixMatch(target: String, decoded: List<Pair<Song, String>>): Long? {
        val targetSegments = target.split('/').filter { it.isNotEmpty() }
        // Try matching the last 3, then 2, then 1 path segments.
        for (depth in minOf(3, targetSegments.size) downTo 1) {
            val suffix = "/" + targetSegments.takeLast(depth).joinToString("/")
            val matches = decoded.filter { (_, path) -> path.endsWith(suffix, ignoreCase = true) }
            if (matches.size == 1) return matches.first().first.id
            // A basename shared by two albums is ambiguous; never import an arbitrary song.
        }
        return null
    }

    private fun normalise(uriOrPath: String): String {
        // URLDecoder is a form decoder: preserve '+', and only decode complete
        // percent-encoded runs so literal filenames such as "100% Love.flac" are valid.
        val decoded = Regex("(?:%[0-9a-fA-F]{2})+").replace(uriOrPath) { match ->
            java.net.URLDecoder.decode(match.value, "UTF-8")
        }
        return "/" + decoded.replace('\\', '/').substringAfter("://").trimStart('/')
    }
}
