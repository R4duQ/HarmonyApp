package com.harmony.domain.library.discovery

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Versions that make a recording a different track, even with the same name. */
enum class TrackVersion { LIVE, REMIX, ACOUSTIC, COVER, REMASTER, INSTRUMENTAL, DEMO, EDIT, EXTENDED, KARAOKE, ALTERED }

/**
 * Who recorded what, normalized so that catalog entries from different
 * sources (Deezer, Apple, a local file's tags) can be compared.
 *
 * Order of evidence: ISRC when both sides carry one, then primary artist,
 * base title, version and duration. "Song (feat. X)" and "Song" are the same
 * recording; "Song (Live)", "Song - 2011 Remaster" and "Song (X Remix)" are
 * not, so an original is never swapped for a variant or the other way round.
 */
data class TrackIdentity(
    val artist: String,
    val title: String,
    val versions: Set<TrackVersion>,
    val isrc: String?,
    val durationMs: Long,
) {
    /** Grouping key: equal for every pair [same] can match on metadata. */
    val key: String get() = "$artist|$title|" + versions.sorted().joinToString(",")

    companion object {
        private val marks = Regex("\\p{M}+")
        private val separators = Regex("[^\\p{L}\\p{N}]+")
        private val brackets = Regex("[(\\[{]([^)\\]}]*)[)\\]}]")
        private val featuring = Regex("^(feat|ft|featuring|with)\\b.*")
        // Not "/" (AC/DC) and not "and" (Florence and the Machine): both sides of a
        // comparison split the same way, so only real credit separators matter.
        private val artistSplit = Regex("\\s+(feat\\.?|ft\\.?|featuring|with|x|vs\\.?)\\s+|\\s*[,&;]\\s*", RegexOption.IGNORE_CASE)

        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(marks, "").lowercase(Locale.ROOT).replace(separators, " ").trim()

        /** "Dua Lipa feat. DaBaby" → "dua lipa". Credits are ordered, the lead comes first. */
        fun primaryArtist(artist: String): String =
            normalize(artist.split(artistSplit).firstOrNull { it.isNotBlank() } ?: artist)

        fun of(title: String, artist: String, durationMs: Long = 0, isrc: String? = null): TrackIdentity {
            val (base, versions) = parseTitle(title)
            return TrackIdentity(primaryArtist(artist), base, versions,
                isrc?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length >= 10 }, durationMs.coerceAtLeast(0))
        }

        fun same(a: TrackIdentity, b: TrackIdentity): Boolean {
            if (a.isrc != null && a.isrc == b.isrc) return true
            if (a.artist.isBlank() || a.title.isBlank()) return false
            if (a.artist != b.artist || a.title != b.title || a.versions != b.versions) return false
            // Two different ISRCs are usually two recordings; only a near-identical
            // length lets them count as one release of the same take.
            val bothCoded = a.isrc != null && b.isrc != null
            if (a.durationMs <= 0 || b.durationMs <= 0) return !bothCoded
            return abs(a.durationMs - b.durationMs) <= if (bothCoded) 2_000 else 4_000
        }

        /** Base title and the versions named in brackets or after " - ". */
        fun parseTitle(title: String): Pair<String, Set<TrackVersion>> {
            val versions = mutableSetOf<TrackVersion>()
            val kept = StringBuilder()
            var head = title
            // Dash suffixes: "Song - Live at Wembley", "Song - 2011 Remaster".
            val dash = head.split(" - ")
            head = dash.first()
            for (segment in dash.drop(1)) {
                val found = versionsIn(segment)
                if (found == null) kept.append(' ').append(segment) else versions += found
            }
            val base = brackets.replace(head) { match ->
                val found = versionsIn(match.groupValues[1])
                if (found == null) " ${match.groupValues[1]} " else { versions += found; " " }
            }
            return normalize(base + kept) to versions
        }

        /** Null = not a version marker (e.g. "Part 2"), so it stays part of the title. */
        private fun versionsIn(segment: String): Set<TrackVersion>? {
            val text = normalize(segment)
            if (text.isBlank()) return emptySet()
            if (featuring.matches(text)) return emptySet()
            val words = text.split(' ').toSet()
            val found = mutableSetOf<TrackVersion>()
            if ("live" in words) found += TrackVersion.LIVE
            if ("remix" in words || "rmx" in words || "bootleg" in words ||
                (text.endsWith(" mix") && !text.endsWith("original mix"))) found += TrackVersion.REMIX
            if ("acoustic" in words || "unplugged" in words || "stripped" in words) found += TrackVersion.ACOUSTIC
            if ("cover" in words || "tribute" in words || text.contains("originally performed") ||
                text.contains("in the style of")) found += TrackVersion.COVER
            if ("karaoke" in words) found += TrackVersion.KARAOKE
            if (words.any { it.startsWith("remaster") }) found += TrackVersion.REMASTER
            if ("instrumental" in words) found += TrackVersion.INSTRUMENTAL
            if ("demo" in words) found += TrackVersion.DEMO
            if ("edit" in words) found += TrackVersion.EDIT
            if ("extended" in words) found += TrackVersion.EXTENDED
            if (text.contains("sped up") || "slowed" in words || "nightcore" in words ||
                text.contains("reverb")) found += TrackVersion.ALTERED
            if (found.isNotEmpty()) return found
            // Labels that name the original: dropped, they don't change the recording.
            val neutral = setOf("original", "original mix", "album version", "single version", "radio version", "mono", "stereo", "explicit", "clean")
            return if (text in neutral || text.matches(Regex("(19|20)\\d\\d"))) emptySet() else null
        }
    }
}
