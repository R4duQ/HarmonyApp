package com.harmony.data.library

import android.content.Context
import com.harmony.domain.library.discovery.*
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
class SongDiscoveryRepositoryImpl @Inject constructor(@ApplicationContext context: Context) : SongDiscoveryRepository {
    private val prefs = context.getSharedPreferences("harmony_song_discovery_v1", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    // Preserve unreadable data: do not silently replace a damaged session with an empty one.
    private val decoded = runCatching { DiscoveryStateJson.decode(prefs.getString("state", "{}") ?: "{}") }
    private val mutable = MutableStateFlow(decoded.getOrDefault(SongDiscoveryState()))
    override val state = mutable.asStateFlow()
    override suspend fun update(change: (SongDiscoveryState) -> SongDiscoveryState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(decoded.isSuccess) { "Discovery progress could not be read. Back up app data before resetting it." }
            val next = change(mutable.value)
            if (next != mutable.value) {
                check(prefs.edit().putString("state", DiscoveryStateJson.encode(next)).commit()) { "Could not save discovery progress. Free storage and retry." }
                mutable.value = next
            }
        }
    }
}

internal object DiscoveryStateJson {
    private fun song(s: DiscoverySong) = JSONObject().apply {
        put("key", s.key); put("title", s.title); put("artist", s.artist); put("album", s.album)
        put("duration", s.durationMs); put("art", s.artwork); put("preview", s.preview); put("link", s.link)
        put("provider", s.provider); put("genres", JSONArray(s.genres.toList())); put("vibe", s.vibe)
        put("market", s.market); put("uri", s.localUri); put("isrc", s.isrc); put("artistCountry", s.artistCountry)
    }
    private fun JSONObject.optional(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun songs(a: JSONArray) = (0 until a.length()).map { readSong(a.getJSONObject(it)) }
    private fun readSong(s: JSONObject) = DiscoverySong(s.getString("key"), s.getString("title"), s.getString("artist"),
        s.optString("album"), s.optLong("duration"), s.optString("art"), s.optString("preview"), s.optString("link"),
        s.optString("provider"), (s.optJSONArray("genres") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
        s.optString("vibe"), s.optString("market"), s.optional("uri"), s.optional("isrc"), s.optString("artistCountry"))
    private fun readMap(s: JSONObject?) = s?.keys()?.asSequence()?.associateWith { s.getString(it) }.orEmpty()
    fun encode(s: SongDiscoveryState): String = JSONObject().apply {
        put("filter", JSONObject().apply { put("country", s.filter.country); put("genre", s.filter.genre)
            put("genreId", s.filter.genreId); put("vibe", s.filter.vibe); put("artistOnly", s.filter.artistCountryOnly) })
        put("likes", JSONArray(s.likes.map(::song))); put("reveal", s.revealedBatch); put("undo", s.undoKey)
        put("votes", JSONArray(s.votes.map { JSONObject().put("song", song(it.song)).put("liked", it.liked) }))
        put("batches", JSONArray(s.batches.map { b -> JSONObject().apply {
            put("id", b.id); put("name", b.name); put("songs", JSONArray(b.songs.map(::song)))
            put("uris", JSONObject(b.uris)); put("errors", JSONObject(b.errors)); put("playlist", b.playlistId)
            put("status", b.status); put("source", b.source); put("format", b.format); put("verificationProvider", b.verificationProvider)
            put("placed", JSONArray(b.placedKeys.toList())); put("reasons", JSONObject(b.reasons)); put("active", b.activeKey)
            put("deleted", b.playlistDeleted); put("locked", b.locked)
        } }))
        s.draft?.let { put("draft", draft(it)) }
        put("recommended", JSONArray(s.recommended.map { JSONObject().put("id", it.identity).put("at", it.at) }))
        put("declined", JSONArray(s.declined.toList()))
        put("feedback", JSONArray(s.feedback.map { f -> JSONObject().apply {
            put("kind", f.kind.name); put("key", f.songKey); put("title", f.title); put("artist", f.artist)
            put("genres", JSONArray(f.genres.toList())); put("at", f.at)
        } }))
    }.toString()

    private fun draft(d: DiscoveryDraft) = JSONObject().apply {
        put("id", d.id); put("step", d.step.name); put("level", d.level.name); put("size", d.size)
        put("artists", JSONArray(d.pickedArtists)); put("genres", JSONArray(d.pickedGenres)); put("name", d.name)
        put("generatedAt", d.generatedAt); put("online", d.generatedOnline); put("sources", JSONArray(d.sources)); put("batch", d.batchId)
        put("shown", JSONArray(d.shown.toList().takeLast(2_000)))
        put("items", JSONArray(d.items.map { i -> JSONObject().put("song", song(i.song)).put("kind", i.kind.name)
            .put("reasonKind", i.reason.kind.name).put("reason", i.reason.text).put("kept", i.kept) }))
    }

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun readDraft(o: JSONObject) = DiscoveryDraft(
        id = o.getString("id"),
        step = enumOr(o.optString("step"), DraftStep.PREFERENCES),
        level = enumOr(o.optString("level"), ExplorationLevel.BALANCED),
        size = o.optInt("size", RecommendationMixer.DEFAULT_SIZE).coerceIn(RecommendationMixer.MIN_SIZE, RecommendationMixer.MAX_SIZE),
        pickedArtists = strings(o.optJSONArray("artists")),
        pickedGenres = strings(o.optJSONArray("genres")),
        items = (o.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { i ->
            DraftItem(readSong(i.getJSONObject("song")), enumOr(i.optString("kind"), CandidateKind.CLOSE),
                Reason(enumOr(i.optString("reasonKind"), ReasonKind.LISTENED), i.optString("reason")), i.optBoolean("kept"))
        } },
        shown = strings(o.optJSONArray("shown")).toSet(),
        name = o.optString("name"),
        generatedAt = o.optLong("generatedAt"),
        generatedOnline = o.optBoolean("online"),
        sources = strings(o.optJSONArray("sources")),
        batchId = o.optional("batch"),
    )
    fun decode(raw: String): SongDiscoveryState {
        val s = JSONObject(raw); val f = s.optJSONObject("filter") ?: JSONObject()
        val votes = s.optJSONArray("votes") ?: JSONArray(); val batches = s.optJSONArray("batches") ?: JSONArray()
        return SongDiscoveryState(DiscoveryFilter(f.optString("country"), f.optString("genre"), f.optInt("genreId"),
            f.optString("vibe"), f.optBoolean("artistOnly")), songs(s.optJSONArray("likes") ?: JSONArray()),
            (0 until votes.length()).map { votes.getJSONObject(it).let { v -> DiscoveryVote(readSong(v.getJSONObject("song")), v.getBoolean("liked")) } },
            (0 until batches.length()).map { batches.getJSONObject(it).let { b ->
                val tracks = songs(b.getJSONArray("songs"))
                // Any size the flow can make (the swipe deck always made 50); never empty, never a repeated key.
                require(tracks.size in 1..RecommendationMixer.MAX_SIZE && tracks.distinctBy { it.key }.size == tracks.size)
                DiscoveryBatch(b.getString("id"), b.getString("name"), tracks, readMap(b.optJSONObject("uris")), readMap(b.optJSONObject("errors")),
                    b.optional("playlist")?.toLong(), b.optString("status", "Ready to download"), b.optString("source", "SPOTIFLAC"), b.optString("format", "FLAC_LOSSLESS"), b.optional("verificationProvider"),
                    placedKeys = strings(b.optJSONArray("placed")).toSet(), reasons = readMap(b.optJSONObject("reasons")),
                    activeKey = b.optional("active"), playlistDeleted = b.optBoolean("deleted"), locked = b.optBoolean("locked"))
            } }, s.optional("reveal"), s.optional("undo"),
            draft = s.optJSONObject("draft")?.let(::readDraft),
            recommended = (s.optJSONArray("recommended") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                .map { RecommendationStamp(it.getString("id"), it.getLong("at")) } },
            declined = strings(s.optJSONArray("declined")).toSet(),
            feedback = (s.optJSONArray("feedback") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { f ->
                TasteFeedback(enumOr(f.optString("kind"), FeedbackKind.MORE_LIKE_THIS), f.optString("key"), f.optString("title"),
                    f.optString("artist"), strings(f.optJSONArray("genres")).toSet(), f.optLong("at"))
            } })
    }
}
