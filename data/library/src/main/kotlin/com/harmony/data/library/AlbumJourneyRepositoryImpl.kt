package com.harmony.data.library

import android.content.Context
import com.harmony.domain.library.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumJourneyRepositoryImpl @Inject constructor(@ApplicationContext context: Context) : AlbumJourneyRepository {
    private val prefs = context.getSharedPreferences("harmony_album_journeys", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val state = MutableStateFlow(decode(prefs.getString("albums", "[]") ?: "[]"))
    override val journeys = state.asStateFlow()

    override suspend fun save(journey: AlbumJourney) = mutate { old ->
        require(journey.tracks.isNotEmpty() && journey.tracks.map { it.id }.distinct().size == journey.tracks.size)
        old.filterNot { it.id == journey.id } + journey
    }
    override suspend fun updateTrack(albumId: String, trackId: String, change: (AlbumJourneyTrack) -> AlbumJourneyTrack) =
        mutate { all -> all.map { album ->
            if (album.id != albumId) album else album.copy(tracks = album.tracks.map {
                if (it.id == trackId) change(it) else it
            })
        } }
    override suspend fun recordListening(uri: String, ranges: List<ListenedRange>, durationMs: Long) = mutate { all ->
        all.map { album ->
            if (album.reviewed || album.tracks.none { it.uri == uri && !it.heard }) album
            else album.copy(tracks = album.tracks.map { track ->
                if (track.uri != uri || track.heard) track else track.copy(
                    coverage = ranges.fold(track.coverage) { merged, range -> ListeningCoverage.merge(merged, range, durationMs) },
                    playbackDurationMs = durationMs,
                )
            })
        }
    }
    override suspend fun selectDownloads(albumId: String, ids: Set<String>) = mutate { all ->
        all.map { album -> if (album.id != albumId) album else album.copy(
            tracks = album.tracks.map { it.copy(selectedForDownload = it.id in ids) }) }
    }
    override suspend fun review(albumId: String, done: Boolean, remindAfter: Long) = mutate { all ->
        all.map { if (it.id == albumId) it.copy(reviewed = done, remindAfter = remindAfter) else it }
    }
    override suspend fun filesRemoved(uris: Set<String>) = mutate { all -> all.map { album ->
        album.copy(tracks = album.tracks.map {
            if (it.uri in uris) it.copy(uri = null, downloaded = false, sizeBytes = 0, status = "Deleted", error = null) else it
        })
    } }

    private suspend fun mutate(change: (List<AlbumJourney>) -> List<AlbumJourney>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = change(state.value)
            if (updated != state.value) {
                check(prefs.edit().putString("albums", encode(updated)).commit()) { "Could not save album progress. Free some storage and retry." }
                state.value = updated
            }
        }
    }

    private fun encode(albums: List<AlbumJourney>) = JSONArray().apply {
        albums.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("title", a.title); put("artist", a.artist); put("cover", a.coverUrl)
            put("edition", a.editionId); put("source", a.source); put("format", a.format)
            put("reviewed", a.reviewed); put("remindAfter", a.remindAfter)
            put("tracks", JSONArray().apply { a.tracks.forEach { t -> put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("artist", t.artist); put("duration", t.durationMs)
                put("disc", t.disc); put("number", t.number); put("uri", t.uri)
                put("downloaded", t.downloaded); put("bytes", t.sizeBytes); put("status", t.status); put("error", t.error)
                put("playbackDuration", t.playbackDurationMs)
                put("selected", t.selectedForDownload)
                put("coverage", JSONArray().apply { t.coverage.forEach { r -> put(JSONArray().put(r.startMs).put(r.endMs)) } })
            }) } })
        }) }
    }.toString()

    private fun decode(raw: String): List<AlbumJourney> = runCatching {
        val data = JSONArray(raw)
        (0 until data.length()).map { i -> data.getJSONObject(i).let { a ->
            val tracks = a.getJSONArray("tracks")
            AlbumJourney(a.getString("id"), a.getString("title"), a.getString("artist"), a.optString("cover"),
                a.getString("edition"), (0 until tracks.length()).map { j -> tracks.getJSONObject(j).let { t ->
                    val ranges = t.optJSONArray("coverage") ?: JSONArray()
                    AlbumJourneyTrack(t.getString("id"), t.getString("title"), t.getString("artist"), t.getLong("duration"),
                        t.optInt("disc", 1), t.getInt("number"), t.optString("uri").takeIf { it.isNotBlank() && it != "null" },
                        t.optBoolean("downloaded"), t.optLong("bytes"), t.optString("status", "Missing"),
                        t.optString("error").takeIf { it.isNotBlank() && it != "null" },
                        (0 until ranges.length()).map { k -> ranges.getJSONArray(k).let { ListenedRange(it.getLong(0), it.getLong(1)) } },
                        t.optLong("playbackDuration"), t.optBoolean("selected", true))
                } }, a.optString("source", "SPOTIFLAC"), a.optString("format", "FLAC_LOSSLESS"),
                a.optBoolean("reviewed"), a.optLong("remindAfter"))
        } }
    }.getOrDefault(emptyList())
}
