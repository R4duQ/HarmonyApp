package com.harmony.feature.discover.provider

import com.harmony.core.model.Song
import com.harmony.feature.discover.model.*
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/** Local, deterministic matching. No listening history is uploaded. */
object AlbumRecommendationEngine {
    fun matchLibrary(albums: List<DiscoverAlbum>, songs: List<Song>): Map<String, AlbumLibraryMatch> {
        val byArtist = mutableMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            artistKeys(song).forEach { key -> byArtist.getOrPut(key) { mutableListOf() }.add(song) }
        }
        return albums.associate { album ->
            val candidates = (album.artistAliases + album.artist).flatMap { byArtist[key(it)].orEmpty() }
                .distinctBy { it.id }
            val albumKey = recordingKey(album.title)
            val albumSongs = candidates.filter {
                recordingKey(it.album) == albumKey && !hasAlternativeRecording(it.title, album.liveRecording)
            }.sortedWith(compareBy<Song> { it.discNumber?.takeIf { n -> n > 0 } ?: 1 }
                .thenBy { it.trackNumber?.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
                .thenBy { it.title }.thenBy { it.id })
                // Keep the higher-bitrate duplicate without queuing two copies.
                .groupBy { recordingKey(it.title) }.values.map { copies -> copies.maxBy { it.bitrateKbps ?: 0 } }
            val entrySong = album.entryTracks.firstNotNullOfOrNull { entry ->
                (if (album.liveRecording) albumSongs else candidates).filter {
                    (if (album.liveRecording) liveRecordingKey(it.title) else recordingKey(it.title)) == recordingKey(entry)
                }
                    .maxByOrNull { it.bitrateKbps ?: 0 }
            }
            album.id to AlbumLibraryMatch(albumSongs, entrySong, candidates.isNotEmpty())
        }
    }

    fun suggestions(
        albums: List<DiscoverAlbum>, matches: Map<String, AlbumLibraryMatch>,
        preferences: DiscoveryPreferences, shelf: AlbumShelf, genre: AlbumGenre?, seed: Long,
    ): List<AlbumSuggestion> {
        val eligible = albums.filter { album ->
            (genre == null || genre in album.genres) && when (shelf) {
                AlbumShelf.FOR_YOU -> album.id !in preferences.listened
                AlbumShelf.SAVED -> album.id in preferences.saved
                AlbumShelf.ALL -> true
            }
        }.map { album ->
            AlbumSuggestion(album, matches[album.id] ?: AlbumLibraryMatch(),
                album.id in preferences.saved, album.id in preferences.familiar, album.id in preferences.listened)
        }
        // A save/recompose/rotation never generates a new random order.
        val shuffled = eligible.shuffled(Random(seed))
        val taste = SongTasteEngine.scores(albums, preferences)
        return if (shelf == AlbumShelf.FOR_YOU) shuffled.sortedByDescending {
            priority(it) + (taste[it.album.id] ?: 0)
        } else shuffled
    }

    private fun priority(item: AlbumSuggestion): Int = when {
        item.match.entrySong != null && item.match.albumSongs.size < 5 -> 50
        item.match.albumSongs.size in 1..4 -> 45
        item.familiar -> 40
        item.match.artistInLibrary && item.match.albumSongs.isEmpty() -> 25
        item.match.albumSongs.size >= 5 -> 10
        else -> 20
    }

    private fun artistKeys(song: Song): Set<String> = buildSet {
        listOfNotNull(song.artist, song.albumArtist).forEach { value ->
            add(key(value))
            value.split(artistSeparator).forEach { add(key(it)) }
        }
        remove(""); remove("unknown"); remove("variousartists")
    }

    internal fun recordingKey(value: String): String {
        return key(withoutEditionSuffix(value))
    }

    internal fun liveRecordingKey(value: String): String = key(liveSuffix.replace(withoutEditionSuffix(value), "").trim())

    internal fun hasAlternativeRecording(value: String, allowLive: Boolean = false): Boolean =
        (if (allowLive) nonLiveAlternative else alternativeRecording).containsMatchIn(value)

    private fun withoutEditionSuffix(value: String): String {
        var cleaned = value.trim()
        repeat(3) { cleaned = editionSuffix.replace(cleaned, "").trim() }
        return cleaned
    }

    private fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private val artistSeparator = Regex("(?i)\\s*(?:;|,|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+)\\s*")
    private val editionSuffix = Regex(
        "(?i)(?:\\s+[-–—]\\s*|\\s*[\\[(])(?:\\d{4}\\s+)?" +
            "(?:remaster(?:ed)?(?:\\s+\\d{4})?|deluxe(?:\\s+edition)?|expanded(?:\\s+edition)?)" +
            "[\\])]?$",
    )
    private val qualifierStart = "(?:\\s+[-–—]\\s+|[\\[(])[^\\])]*\\b"
    private val alternativeRecording = Regex("(?i)${qualifierStart}(live|remix|karaoke|instrumental|sped[- ]up|slowed)\\b")
    private val nonLiveAlternative = Regex("(?i)${qualifierStart}(remix|karaoke|instrumental|sped[- ]up|slowed)\\b")
    private val liveSuffix = Regex("(?i)(?:\\s+[-–—]\\s*|\\s*[\\[(])live(?:\\s+[^\\])]+)?[\\])]?$" )
}
