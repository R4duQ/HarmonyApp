package com.harmony.feature.discover.provider

import com.harmony.feature.discover.model.TasteSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Fetches a provider-supplied preview only when Play is pressed. Never downloads full tracks. */
class SongPreviewClient {
    private val cache = linkedMapOf<String, String>()
    suspend fun find(song: TasteSong): String? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[song.id] }?.let { return@withContext it }
        val query = URLEncoder.encode("${song.album.artist} ${song.title}", "UTF-8")
        val connection = URL("https://api.deezer.com/search?q=$query&limit=25").openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000; connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode != 200) throw IOException("Preview service is unavailable. Try again later.")
            val raw = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 512_000) { "Preview response is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            ensureActive()
            val root = JSONObject(String(raw, Charsets.UTF_8))
            if (root.has("error")) throw IOException("Preview service is unavailable. Try again later.")
            val tracks = root.optJSONArray("data") ?: return@withContext null
            val found = (0 until tracks.length()).asSequence().map { tracks.getJSONObject(it) }.firstOrNull { track ->
                matchesRecording(song, track.optString("title"), track.optJSONObject("artist")?.optString("name").orEmpty(),
                    track.optString("title_version"), track.optJSONObject("album")?.optString("title").orEmpty()) &&
                    isPreviewUrl(track.optString("preview"))
            }?.optString("preview")
            if (found != null) {
                synchronized(cache) {
                    if (cache.size >= 48) cache.remove(cache.keys.first())
                    cache[song.id] = found
                }
            }
            found
        } finally { connection.disconnect() }
    }

    internal fun matchesRecording(song: TasteSong, title: String, artist: String, version: String, album: String): Boolean {
        val artists = (song.album.artistAliases + song.album.artist).map(AlbumRecommendationEngine::recordingKey).toSet()
        if (AlbumRecommendationEngine.recordingKey(artist) !in artists) return false
        val versionName = version.trim().trim('(', ')', '[', ']').trim().takeUnless { it == "null" }.orEmpty()
        val completeTitle = if (versionName.isEmpty() || title.trimEnd(')', ']').endsWith(versionName, ignoreCase = true)) title
            else "$title ($versionName)"
        val titleKey = if (song.album.liveRecording) AlbumRecommendationEngine.liveRecordingKey(completeTitle)
            else AlbumRecommendationEngine.recordingKey(completeTitle)
        if (titleKey != AlbumRecommendationEngine.recordingKey(song.title)) return false
        // A studio performance of the same composition cannot preview this specific live record.
        return !song.album.liveRecording || AlbumRecommendationEngine.recordingKey(album) == AlbumRecommendationEngine.recordingKey(song.album.title)
    }

    internal fun isPreviewUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host?.endsWith(".dzcdn.net") == true &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
}
