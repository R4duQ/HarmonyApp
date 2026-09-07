package com.harmony.feature.downloads

import com.harmony.core.model.Song
import com.harmony.domain.library.repository.AlbumJourney
import com.harmony.domain.library.repository.AlbumJourneyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

data class AlbumEdition(val id: String, val title: String, val artist: String, val cover: String)

object AlbumTrackMatcher {
    fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun albumKey(value: String): String {
        var title = value.trim()
        repeat(3) { title = editionSuffix.replace(title, "").trim() }
        return key(title)
    }
    private val editionSuffix = Regex("(?i)(?:\\s+[-–—]\\s*|\\s*[\\[(])(?:\\d{4}\\s+)?" +
        "(?:remaster(?:ed)?(?:\\s+\\d{4})?|deluxe(?:\\s+edition)?|expanded(?:\\s+edition)?|" +
        "(?:\\d+(?:st|nd|rd|th)?\\s+)?anniversary(?:\\s+edition)?)[\\])]?$" )
    fun match(track: AlbumJourneyTrack, albumTitle: String, songs: List<Song>): Song? = songs.asSequence()
        .filter { key(it.title) == key(track.title) && key(it.artist) == key(track.artist) }
        .filter { albumKey(it.album) == albumKey(albumTitle) }
        .filter { it.durationMs > 0 && kotlin.math.abs(it.durationMs - track.durationMs) <= 4_000 }
        .maxByOrNull { it.bitrateKbps ?: 0 }
}

/** A shared title alone is not evidence that two records are editions of the same album. */
object AlbumEditionMatcher {
    fun matching(editions: List<AlbumEdition>, title: String, artist: String, aliases: Set<String> = emptySet()): List<AlbumEdition> {
        val expectedTitle = AlbumTrackMatcher.albumKey(title)
        val artists = (aliases + artist).map(AlbumTrackMatcher::key).filter { it.isNotBlank() }.toSet()
        if (expectedTitle.isBlank() || artists.isEmpty()) return emptyList()
        return editions.filter {
            AlbumTrackMatcher.albumKey(it.title) == expectedTitle && AlbumTrackMatcher.key(it.artist) in artists
        }.distinctBy { it.id }.sortedByDescending { AlbumTrackMatcher.key(it.artist) == AlbumTrackMatcher.key(artist) }.take(8)
    }
}

/** Public album metadata only. Audio always goes through the explicitly selected engine. */
class AlbumMetadataClient @Inject constructor() {
    suspend fun search(title: String, artist: String, artistAliases: Set<String> = emptySet()): List<AlbumEdition> = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("$artist $title", "UTF-8")
        val data = get("https://api.deezer.com/search/album?q=$query&limit=25").getJSONArray("data")
        val editions = (0 until data.length()).map { data.getJSONObject(it) }.map { a ->
            AlbumEdition(a.getLong("id").toString(), a.optString("title"),
                a.optJSONObject("artist")?.optString("name").orEmpty(), a.optString("cover_big"))
        }
        AlbumEditionMatcher.matching(editions, title, artist, artistAliases)
    }

    suspend fun load(shflId: String, edition: AlbumEdition): AlbumJourney = withContext(Dispatchers.IO) {
        require(edition.id.matches(Regex("[0-9]+")))
        val album = get("https://api.deezer.com/album/${edition.id}")
        check(album.getString("title") == edition.title) { "Album metadata changed. Search again." }
        check(AlbumTrackMatcher.key(album.getJSONObject("artist").getString("name")) == AlbumTrackMatcher.key(edition.artist)) {
            "The album artist changed. Search again."
        }
        val total = album.getInt("nb_tracks")
        check(total in 1..250) { "This edition has an unsupported track count." }
        val rows = mutableListOf<AlbumJourneyTrack>()
        var page = get("https://api.deezer.com/album/${edition.id}/tracks?limit=100")
        val visited = mutableSetOf<String>()
        while (true) {
            val tracks = page.getJSONArray("data")
            for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                val title = t.getString("title") // Preserve live/remix/version suffixes.
                val duration = t.getLong("duration") * 1_000
                check(title.isNotBlank() && duration > 0) { "The album has incomplete track metadata." }
                rows += AlbumJourneyTrack(t.getLong("id").toString(), title,
                    t.getJSONObject("artist").getString("name"), duration,
                    t.optInt("disk_number", 1).coerceAtLeast(1), t.optInt("track_position", rows.size + 1))
            }
            val next = page.optString("next")
            if (next.isBlank() || next == "null") break
            val url = URL(next.replaceFirst("http://api.deezer.com/", "https://api.deezer.com/"))
            check(url.protocol == "https" && url.host == "api.deezer.com" && url.userInfo == null && url.port == -1 &&
                url.path == "/album/${edition.id}/tracks" && visited.add(url.toString()) && visited.size <= 10) {
                "Invalid album pagination response."
            }
            page = get(url.toString())
        }
        check(rows.size == total && rows.map { it.id }.distinct().size == total) { "The complete album tracklist is unavailable. Retry later." }
        AlbumJourney(shflId, edition.title, edition.artist, edition.cover, edition.id, rows)
    }

    private fun get(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Harmony/1.0.0 AlbumMetadata")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Album metadata unavailable (HTTP $code). Try again later.")
            val bytes = connection.inputStream.use { it.readBytesLimited(2_000_000) }
            val data = JSONObject(String(bytes, Charsets.UTF_8))
            check(!data.has("error")) { "The metadata provider could not return this album. Try another edition." }
            return data
        } finally { connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            check(out.size() + count <= limit) { "Album metadata response is too large." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
