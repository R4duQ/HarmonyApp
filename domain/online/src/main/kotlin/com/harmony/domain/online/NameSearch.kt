package com.harmony.domain.online

import com.harmony.domain.library.discovery.TrackIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import kotlin.math.abs

/** A recording a catalog lists: who by, its name, its length when known. */
data class NamedRecording(val artist: String, val title: String, val durationMs: Long?)

/**
 * Free guesses from a file's name: the cleaned name is searched as free
 * text in a catalog with a forgiving search (Deezer, YouTube Music), which
 * copes with words in another order, a missing or misspelled artist and
 * extra words around the title. Of what comes back, only a recording of the
 * same length whose title words are all in the file's names is a guess,
 * and the online mix still has the catalog confirm it (artist, title,
 * length), as for any guess. No account, no key, nothing paid.
 */
object NameSearch {
    /** Lengths within this agree (a rip may have a second of silence more or less). */
    const val LENGTH_TOLERANCE_MS = 3_000L

    private val noise = setOf("the", "a", "an", "and", "of", "feat", "ft", "featuring", "with", "x", "vs")

    /**
     * The free-text search for [seed]: its cleaned title, with the artist
     * when the tags name one. Null when too little is left to search for.
     */
    fun query(seed: SeedTrack): String? {
        val title = SeedCleaner.cleanTitle(seed.title)
        val artist = SeedCleaner.artistOf(seed).takeUnless { it.isNullOrBlank() }
        val text = listOfNotNull(artist?.takeIf { !title.contains(it, ignoreCase = true) }, title).joinToString(" ").trim()
        return text.takeIf { words(text).count { it.length > 1 } >= 1 && text.length >= 3 }?.take(120)
    }

    /**
     * The recordings of [results] that [seed] can be, best first, at most
     * [SongGuessPrompt.MAX_GUESSES]: every word of the title is in the file's
     * names, and the length agrees (when both are known; when the file's
     * isn't, the artist's words must be in its names too).
     */
    fun pick(seed: SeedTrack, results: List<NamedRecording>): List<SongGuess> {
        val names = words(listOf(seed.title, seed.artist, seed.album.orEmpty()).joinToString(" ")) +
            words(SeedCleaner.cleanTitle(seed.title))
        val seedMs = seed.knownDurationMs
        return results.asSequence()
            .filter { it.artist.isNotBlank() && it.title.isNotBlank() }
            .mapNotNull { r ->
                val title = words(TrackIdentity.parseTitle(r.title).first).filter { it !in noise }
                if (title.isEmpty() || !names.containsAll(title)) return@mapNotNull null
                val artist = words(TrackIdentity.primaryArtist(r.artist)).filter { it !in noise }
                val artistShare = if (artist.isEmpty()) 0.0 else artist.count { it in names }.toDouble() / artist.size
                val ms = r.durationMs?.takeIf { it > 0 }
                val lengthAgrees = when {
                    seedMs != null && ms != null -> abs(seedMs - ms) <= LENGTH_TOLERANCE_MS
                    else -> artistShare >= 1.0
                }
                if (!lengthAgrees) return@mapNotNull null
                // The more of the title and the artist the file names, and the closer the length, the better.
                val closeness = if (seedMs != null && ms != null) 1.0 - abs(seedMs - ms).toDouble() / LENGTH_TOLERANCE_MS else 0.0
                SongGuess(r.artist.trim(), r.title.trim()) to (title.size * 1.0 + artistShare * 2 + closeness)
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { TrackIdentity.normalize(it.artist) + "|" + TrackIdentity.normalize(it.title) }
            .take(SongGuessPrompt.MAX_GUESSES)
            .toList()
    }

    private fun words(text: String): Set<String> =
        TrackIdentity.normalize(text).split(' ').filter { it.isNotEmpty() }.toSet()

    /** [SongGuesser] on [search]: the query, then [pick]. A failed search is no guess. */
    fun guesser(search: suspend (String) -> List<NamedRecording>) = SongGuesser { seed ->
        val q = query(seed) ?: return@SongGuesser emptyList()
        pick(seed, search(q))
    }

    /** YouTube Music's own song search, through [catalog]. */
    fun catalog(catalog: OnlineCatalog): SongGuesser = guesser { q ->
        when (val r = catalog.searchSongs(q, 20)) {
            is CatalogResult.Ok -> r.value.filter { it.kind == CatalogKind.SONG && it.playable != false }
                .map { NamedRecording(it.artists.firstOrNull().orEmpty(), it.title, it.durationMs) }
            is CatalogResult.Failed -> emptyList()
        }
    }

    /** Deezer's public track search (no account, no key). */
    fun deezer(transport: HttpTransport): SongGuesser = guesser { q ->
        val url = "https://api.deezer.com/search?limit=25&q=" + URLEncoder.encode(q, "UTF-8")
        val reply = transport.execute(HttpRequest("GET", url, mapOf("accept" to "application/json"), timeoutMs = 8_000))
        if (reply.status != 200) emptyList() else parseDeezer(reply.body)
    }

    /** Deezer's search answer: {"data": [{"title", "duration" (s), "artist": {"name"}}]}. Anything else is nothing. */
    fun parseDeezer(body: String): List<NamedRecording> = runCatching {
        val rows = Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
        rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artist = (o["artist"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val seconds = o["duration"]?.jsonPrimitive?.longOrNull
            NamedRecording(artist, title, seconds?.takeIf { it > 0 }?.times(1000))
        }
    }.getOrDefault(emptyList())
}
