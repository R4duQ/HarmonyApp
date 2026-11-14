package com.harmony.domain.online

import com.harmony.domain.library.discovery.TrackIdentity

/**
 * Other readings of a seed's tags, for when the tags as they are find
 * nothing in the catalog. Files carry their history in their names:
 *
 *  - no tags at all: the library names the song after its file
 *    (`01 - Linkin Park - In the End (Official Video) [320kbps]`) and its
 *    artist `<unknown>`, which no catalog result can match;
 *  - a rip's title still says where it came from: "(Official Audio)",
 *    "[HD]", "| Lyrics", a site's name in front;
 *  - the artist repeated in the title ("Adele - Hello"), or a channel name
 *    for the artist ("Adele - Topic", "AdeleVEVO").
 *
 * Only words that never name a recording are taken away: a version
 * ("Live", "Remix", "Acoustic") always stays, so a cleaned reading never
 * finds another take than the file's. Each reading is still checked by
 * [SongMatcher] with the same rules as the tags, so a wrong guess finds
 * nothing rather than the wrong song. No network, no model: plain rules.
 */
object SeedCleaner {
    /** At most this many readings are searched for (each one is a catalog request). */
    const val MAX_READINGS = 2

    private val unknownArtists = setOf("", "unknown", "unknown artist", "unknown artists", "artist", "various", "various artists", "va", "none", "null")

    /** The artist tag says nothing about who it is: blank, `<unknown>`, "Unknown Artist", "Various Artists". */
    fun isUnknownArtist(artist: String): Boolean = TrackIdentity.normalize(artist) in unknownArtists

    /**
     * The readings of [seed] worth a search, the most likely first, at most
     * [MAX_READINGS]; never the seed itself, and none when there is nothing
     * to clean. A reading has an artist: a title alone can't be matched.
     */
    fun readings(seed: SeedTrack): List<SeedTrack> {
        val artistKnown = !isUnknownArtist(seed.artist)
        val artist = if (artistKnown) cleanArtist(seed.artist) else ""
        val title = cleanTitle(seed.title)
        val album = seed.album?.takeUnless { isUnknownArtist(it) }
        val out = mutableListOf<Pair<String, String>>()
        val halves = splitOnDash(title)
        if (artistKnown) {
            val credits = SongMatcher.credits(artist).toSet()
            fun isArtist(s: String) = TrackIdentity.normalize(s).let { it.isNotEmpty() && (it == TrackIdentity.normalize(artist) || it in credits) }
            when {
                // "Adele - Hello" by Adele: the title is the second half.
                halves != null && isArtist(halves.first) -> out += artist to halves.second
                // "Hello - Adele" by Adele.
                halves != null && isArtist(halves.second) -> out += artist to halves.first
                else -> out += artist to title
            }
        } else if (halves != null) {
            // "Artist - Title" is how files are named; "Title - Artist" is the rarer way round.
            out += halves.first to halves.second
            out += halves.second to halves.first
        }
        val seen = mutableSetOf(identity(seed.artist, seed.title))
        return out.asSequence()
            .map { (a, t) -> cleanArtist(a) to t.trim() }
            .filter { (a, t) -> a.isNotBlank() && t.isNotBlank() && !isUnknownArtist(a) }
            .filter { (a, t) -> seen.add(identity(a, t)) }
            .map { (a, t) -> seed.copy(title = t, artist = a, album = album, uri = null, videoId = null) }
            .take(MAX_READINGS)
            .toList()
    }

    /** Who the seed is by, as well as the tags or the file's name say: null when nothing does. */
    fun artistOf(seed: SeedTrack): String? =
        if (!isUnknownArtist(seed.artist)) cleanArtist(seed.artist) else readings(seed).firstOrNull()?.artist

    private fun identity(artist: String, title: String) = TrackIdentity.normalize(artist) + "|" + TrackIdentity.normalize(title)

    // -- the rules -------------------------------------------------------------------------------

    /** "Adele - Topic", "AdeleVEVO", "Adele (Official)" → "Adele". */
    fun cleanArtist(artist: String): String {
        var a = artist.trim()
        a = a.replace(Regex("\\s+-\\s+Topic$", RegexOption.IGNORE_CASE), "")
        a = a.replace(Regex("(?<=\\p{Ll})VEVO$"), "").replace(Regex("\\s+VEVO$", RegexOption.IGNORE_CASE), "")
        a = a.replace(Regex("\\s*[(\\[](official|oficial|officiel)[)\\]]$", RegexOption.IGNORE_CASE), "")
        return a.trim()
    }

    /**
     * A title without what only a file's name or a rip carries: a site's
     * name, a track number, underscores, bracketed notes that are not a
     * version, and anything after a " | ". Versions are kept.
     */
    fun cleanTitle(title: String): String {
        var t = title.trim()
        // An extension left in the title ("song.mp3").
        t = t.replace(Regex("\\.(mp3|flac|m4a|aac|ogg|opus|wav|wma|alac|aiff?)$", RegexOption.IGNORE_CASE), "")
        // Underscores for spaces ("Linkin_Park_-_In_the_End").
        if (t.count { it == '_' } >= 2 || (t.contains('_') && !t.contains(' '))) t = t.replace('_', ' ')
        // A site in front ("y2mate.com - ", "[www.site.net] ") or at the end.
        t = t.replace(Regex("^\\s*[\\[(]?\\s*(www\\.)?[a-z0-9-]+\\.(com|net|org|io|cc|me|to|ru|info|biz|co|xyz)\\b\\s*[\\])]?\\s*[-–—|:]?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*[-–—|]?\\s*[\\[(]?\\s*(www\\.)?[a-z0-9-]+\\.(com|net|org|io|cc|me|to|ru|info|biz|co|xyz)\\b\\s*[\\])]?\\s*$", RegexOption.IGNORE_CASE), "")
        // A track number: "01 - ", "01. ", "1-05 ", "A1. ", "(03) ", "07 Song" (a leading zero only:
        // "7 Years" and "99 Problems" are titles).
        t = t.replace(Regex("^\\s*(\\d{1,2}[-.]\\d{1,3}|[A-Da-d]?\\d{1,3})\\s*[.)\\]]\\s+"), "")
        t = t.replace(Regex("^\\s*[(\\[]\\d{1,3}[)\\]]\\s*"), "")
        t = t.replace(Regex("^\\s*\\d{1,3}\\s+[-–—]\\s+"), "")
        t = t.replace(Regex("^\\s*0\\d\\s+(?=\\S)"), "")
        t = t.replace(Regex("^\\s*\\d-\\d{2}\\s+"), "")
        // Everything after " | " or " // ": a channel's or a site's notes.
        t = t.split(Regex("\\s+(\\||//|¦)\\s+")).first()
        // Bracketed notes made only of noise words ("(Official Music Video)", "[HD]", "[320 kbps]").
        t = bracket.replace(t) { m -> if (isNoise(m.groupValues[1])) " " else m.value }
        // The same notes without brackets at the end ("Song Official Video", "Song - Lyrics").
        t = t.replace(trailingNoise, "")
        // A dangling separator after the noise went, and doubled spaces.
        t = t.replace(Regex("\\s*[-–—:|]\\s*$"), "").replace(Regex("\\s{2,}"), " ")
        return t.trim()
    }

    /**
     * "Artist - Title" → ("Artist", "Title") at the first spaced dash outside
     * brackets; null without one. "Song - Live" splits too: which half is
     * the artist is for [SongMatcher] to confirm, a wrong split only finds nothing.
     */
    fun splitOnDash(title: String): Pair<String, String>? {
        var depth = 0
        for (i in title.indices) {
            when (title[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                '-', '–', '—' -> if (depth == 0 && i > 0 && i < title.length - 1 && title[i - 1] == ' ' && title[i + 1] == ' ') {
                    val left = title.substring(0, i).trim()
                    val right = title.substring(i + 1).trim()
                    return if (left.isNotEmpty() && right.isNotEmpty()) left to right else null
                }
            }
        }
        return null
    }

    private val bracket = Regex("[(\\[{]([^)\\]}]*)[)\\]}]")

    private val noiseWords = setOf(
        "official", "officiel", "officielle", "oficial", "offizielles", "ufficiale",
        "video", "videoclip", "clip", "vidéo", "videoclipe", "music", "musik", "musica", "audio",
        "lyric", "lyrics", "letra", "letras", "versuri", "paroles", "testo", "visualizer", "visualiser",
        "hd", "hq", "uhd", "4k", "8k", "full", "song", "mv", "m", "v", "new", "free", "download",
        "out", "now", "premiere", "high", "quality", "kbps", "mp3", "flac", "wav", "with", "subtitles",
        "sub", "subs", "español", "english", "cc", "the", "and", "official's", "explicit", "clean", "dirty",
        "color", "coded", "version", "exclusive", "1080p", "720p", "480p", "360p", "2160p",
    )

    /** Every word of [segment] is noise (and it has at least one): never a version, a featured artist or a part. */
    private fun isNoise(segment: String): Boolean {
        val words = TrackIdentity.normalize(segment).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        // "Version" alone is neutral, and "Best Version" says nothing; but "Radio Version" or
        // "Live Version" name a take, so a word outside the list keeps the whole note.
        return words.all { it in noiseWords || it.matches(Regex("\\d{2,4}(kbps|k|p)?")) } &&
            words.any { it != "the" && it != "and" && it != "with" && it != "version" && !it.matches(Regex("\\d+")) }
    }

    // Only forms no title ends with: after a dash, or with "Official", or "Lyric Video" ("Radio
    // Video" stays, "Song - Video" and "Song Official Audio" lose their tail).
    private val trailingNoise = Regex(
        "(\\s+[-–—]\\s+(official\\s+)?(music\\s+|lyric\\s+)?(video|audio|lyrics?|visuali[sz]er)|" +
            "\\s+official\\s+(music\\s+|lyric\\s+)?(video|audio)|\\s+lyrics?\\s+video)(\\s+(hd|hq|4k))?\\s*$|" +
            "\\s+(hd|hq|4k|\\d{3}\\s?kbps)\\s*$",
        RegexOption.IGNORE_CASE,
    )
}
