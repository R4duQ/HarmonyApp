package com.harmony.feature.discover.provider

import android.content.Context
import android.content.SharedPreferences
import com.harmony.feature.discover.model.DiscoveryPreferences
import com.harmony.feature.discover.model.SwipeRound
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Album bookmarks are separate from playlists and survive app updates. */
@Singleton
class DiscoveryPreferencesStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("harmony_discover_albums", Context.MODE_PRIVATE)
    private val mutationLock = Mutex()

    val states = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun toggleSaved(id: String) = change(SAVED, id, null)
    suspend fun toggleFamiliar(id: String) = change(FAMILIAR, id, null)
    suspend fun setListened(id: String, listened: Boolean) = change(LISTENED, id, listened)

    suspend fun rateSong(id: String, liked: Boolean?) = updateTaste { SwipeRoundEngine.rate(it, id, liked) }
    suspend fun nextRound() = updateTaste(SwipeRoundEngine::next)
    suspend fun finishRemaining() = updateTaste(SwipeRoundEngine::finishRemaining)

    private suspend fun updateTaste(change: (DiscoveryPreferences) -> DiscoveryPreferences) = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            val old = read()
            val next = change(old)
            if (next == old) return@withLock old
            val round = JSONObject().apply {
                put("number", next.round.number)
                put("songs", JSONArray(next.round.songIds))
                put("completed", next.round.completed)
                put("album", next.round.albumId)
            }.toString()
            check(preferences.edit().putStringSet(LIKES, next.likedSongs).putStringSet(DISLIKES, next.dislikedSongs)
                .putString(ROUND, round).putStringSet(RECOMMENDED, next.recommendedAlbums).commit()) {
                "Your song choice could not be saved."
            }
            next
        }
    }

    private suspend fun change(key: String, id: String, selected: Boolean?) = withContext(Dispatchers.IO) {
        require(id in ShflAlbumCatalog.ids)
        mutationLock.withLock {
            val ids = readIds(key).toMutableSet()
            if (selected ?: (id !in ids)) ids.add(id) else ids.remove(id)
            check(preferences.edit().putStringSet(key, ids).commit()) { "Album preference could not be saved." }
        }
    }

    private fun read(): DiscoveryPreferences {
        val likes = readSongs(LIKES)
        val dislikes = readSongs(DISLIKES) - likes
        val round = runCatching {
            val value = JSONObject(preferences.getString(ROUND, "{}") ?: "{}")
            val songs = value.optJSONArray("songs") ?: JSONArray()
            SwipeRound(number = value.optInt("number", 1).coerceAtLeast(1),
                songIds = (0 until minOf(songs.length(), SwipeRound.SIZE)).map { songs.getString(it) }
                    .mapNotNull(SongTasteEngine::canonicalSongId)
                    .filter { it in likes || it in dislikes }.distinct(),
                completed = value.optBoolean("completed"),
                albumId = value.optString("album").takeIf { it in ShflAlbumCatalog.ids })
        }.getOrDefault(SwipeRound())
        return DiscoveryPreferences(readIds(SAVED), readIds(FAMILIAR), readIds(LISTENED),
            likes, dislikes, round, readIds(RECOMMENDED))
    }
    private fun readSongs(key: String) = preferences.getStringSet(key, emptySet()).orEmpty()
        .mapNotNullTo(mutableSetOf(), SongTasteEngine::canonicalSongId)
    private fun readIds(key: String): Set<String> = preferences.getStringSet(key, emptySet()).orEmpty()
        .filterTo(mutableSetOf()) { it in ShflAlbumCatalog.ids }

    private companion object {
        const val SAVED = "saved_album_ids"
        const val FAMILIAR = "familiar_album_ids"
        const val LISTENED = "listened_album_ids"
        const val LIKES = "liked_song_ids"
        const val DISLIKES = "disliked_song_ids"
        const val ROUND = "swipe_round_v1"
        const val RECOMMENDED = "recommended_album_ids"
    }
}
