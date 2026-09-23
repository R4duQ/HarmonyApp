package com.harmony.feature.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** One HTTP answer. The network edge is passed in so the search can be tested without it. */
internal data class SearchResponse(val code: Int, val body: String)

/**
 * The SpotiFLAC search box: free text in, verified track candidates out.
 *
 * First it asks SpotiFLAC's own metadata providers (Tidal first, then the
 * other installed ones): what they list is what the same engine can then
 * download, and they return an ISRC. Deezer's public search and Apple
 * Music follow as fallbacks.
 *
 * Deezer's search engine only matches the words as typed, so one query is
 * not enough. "oscar frati-miu vinde droguri" can miss a track that
 * "oscar frati miu vinde droguri", `artist:"oscar" track:"frati-miu vinde
 * droguri"` or the title alone would find. So the search tries a short,
 * ordered list of phrasings and stops as soon as it has strong matches. If
 * Deezer has nothing (or is unavailable), it asks the iTunes Search API.
 * Every result still goes through the same strict title/artist check, so
 * trying more phrasings never lets an unrelated song through.
 */
internal class SpotiFlacTrackSearch(
    private val fetch: suspend (String) -> SearchResponse,
    private val country: String = Locale.getDefault().country,
    /** SpotiFLAC's engine search (query, limit) → its JSON track array; null when unavailable. */
    private val providers: (suspend (String, Int) -> String)? = null,
    private val providerTimeoutMs: Long = 30_000,
) {
    suspend fun search(query: String): List<SpotiFlacSearchCandidate> {
        val expected = SearchScoring.parse(query)
        val found = LinkedHashMap<String, SpotiFlacSearchCandidate>()
        var problem: Exception? = null

        if (providers != null) {
            for (phrasing in listOf(query, SearchScoring.spaced(query)).distinct()) {
                try {
                    val json = withTimeout(providerTimeoutMs) { providers.invoke(phrasing, LIMIT) }
                    fromProviders(json, expected).forEach { add(found, it) }
                } catch (e: TimeoutCancellationException) {
                    problem = IllegalStateException("SpotiFLAC's providers took too long to answer.")
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem = providerProblem(e)
                    break // the engine's own error (verification, offline) won't change with another phrasing
                }
                if (enough(found) || exact(found)) break
            }
        }

        // An exact match from the engine that will download it needs no second opinion.
        if (!exact(found) && !enough(found)) for (phrasing in phrasings(query, expected)) {
            val url = "https://api.deezer.com/search/track?q=${encode(phrasing)}&limit=$LIMIT"
            try {
                deezer(fetch(url), expected).forEach { add(found, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
                if (e is RateLimited || e is Unavailable) break // more phrasings would only fail the same way
            }
            if (enough(found)) break
        }

        if (found.isEmpty()) {
            // Deezer lacks the track or can't be reached: try Apple's catalog in the phone's
            // country first (regional artists are often only listed there), then the US store.
            for (store in listOf(country, "US").filter { it.matches(Regex("[A-Za-z]{2}")) }.map { it.uppercase() }.distinct()) {
                val term = SearchScoring.spaced(query)
                val url = "https://itunes.apple.com/search?term=${encode(term)}&entity=song&limit=$LIMIT&country=$store"
                try {
                    apple(fetch(url), expected).forEach { add(found, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem = problem ?: e
                }
                if (found.isNotEmpty()) break
            }
        }

        // Nothing found because every service failed: say so, rather than "no match".
        if (found.isEmpty()) problem?.let { throw it }
        return found.values
            .sortedWith(compareByDescending<SpotiFlacSearchCandidate> { it.matchScore }.thenByDescending { it.rank })
            .take(VISIBLE)
    }

    private fun exact(found: Map<String, SpotiFlacSearchCandidate>) = found.values.any { it.matchScore >= EXACT && it.isrc != null }

    private fun enough(found: Map<String, SpotiFlacSearchCandidate>) = found.values.count { it.matchScore >= STRONG } >= ENOUGH_STRONG

    private fun add(found: MutableMap<String, SpotiFlacSearchCandidate>, candidate: SpotiFlacSearchCandidate) {
        val key = "${SearchScoring.normalize(candidate.artist)}|${SearchScoring.normalize(candidate.title)}"
        val existing = found[key]
        // The same song from two sources: keep the one the download engine can pin down best
        // (an ISRC, then a Deezer id), then the better score.
        fun rank(c: SpotiFlacSearchCandidate) = (if (c.isrc != null) 2 else 0) + (if (c.metadataId.all(Char::isDigit)) 1 else 0)
        if (existing == null || rank(candidate) > rank(existing) ||
            (rank(candidate) == rank(existing) && candidate.matchScore > existing.matchScore)) found[key] = candidate
    }

    /** The engine's track array (see SpotiFLAC's ExtTrackMetadata). */
    private fun fromProviders(json: String, expected: SearchScoring.Expected): List<SpotiFlacSearchCandidate> {
        val tracks = JSONArray(json.ifBlank { "[]" })
        return (0 until tracks.length()).mapNotNull { index ->
            val t = tracks.optJSONObject(index) ?: return@mapNotNull null
            val artist = t.optString("artists").ifBlank { t.optString("album_artist") }.trim()
            val title = t.optString("name").trim()
            val id = t.optString("id").trim()
            val provider = t.optString("provider_id").trim()
            if (artist.isBlank() || title.isBlank() || id.isBlank()) return@mapNotNull null
            if (t.optString("item_type").let { it.isNotBlank() && it != "track" }) return@mapNotNull null
            val score = SearchScoring.score(expected, artist, title)
            if (score < expected.minimumScore) return@mapNotNull null
            val deezerId = t.optString("deezer_id").trim().takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                ?: id.takeIf { provider == "deezer" && it.all(Char::isDigit) }
            SpotiFlacSearchCandidate(
                metadataId = deezerId ?: "$PROVIDER_PREFIX$provider:$id",
                artist = artist,
                title = title,
                album = t.optString("album_name").trim(),
                durationMs = t.optLong("duration_ms").coerceAtLeast(0L),
                thumbnailUrl = t.optString("cover_url").takeIf { it.startsWith("https://") }
                    ?: t.optString("images").takeIf { it.startsWith("https://") },
                matchScore = score,
                source = providerName(provider),
                isrc = t.optString("isrc").trim().uppercase(Locale.ROOT).takeIf { it.length >= 10 },
            )
        }
    }

    private fun providerProblem(e: Exception): Exception {
        val message = e.message.orEmpty()
        return when {
            message.contains("verification_required", ignoreCase = true) ->
                IllegalStateException("Tidal (SpotiFLAC) needs verification before it can search. Use Provider verification in Downloads.")
            else -> IllegalStateException("SpotiFLAC's provider search failed: ${message.take(160).ifBlank { e.javaClass.simpleName }}")
        }
    }

    private fun deezer(response: SearchResponse, expected: SearchScoring.Expected): List<SpotiFlacSearchCandidate> {
        if (response.code == 429) throw RateLimited("Deezer")
        if (response.code >= 500) throw Unavailable("Deezer")
        if (response.code !in 200..299) throw IllegalStateException("The metadata search service could not be reached (HTTP ${response.code}).")
        val root = JSONObject(response.body)
        root.optJSONObject("error")?.let { error ->
            when (error.optInt("code")) {
                800 -> return emptyList() // "no data": an empty result, not a failure
                4 -> throw RateLimited("Deezer")
                700 -> throw Unavailable("Deezer")
                else -> throw IllegalStateException(error.optString("message").ifBlank { "Unknown metadata search error." })
            }
        }
        val data = root.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val artist = item.optJSONObject("artist")?.optString("name").orEmpty().trim()
            val title = item.optString("title_short").ifBlank { item.optString("title") }.trim()
            val id = item.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            if (artist.isBlank() || title.isBlank()) return@mapNotNull null
            val score = SearchScoring.score(expected, artist, title)
            if (score < expected.minimumScore) return@mapNotNull null
            val album = item.optJSONObject("album")
            SpotiFlacSearchCandidate(
                metadataId = id.toString(),
                artist = artist,
                title = title,
                album = album?.optString("title").orEmpty().trim(),
                durationMs = item.optLong("duration").coerceAtLeast(0L) * 1000L,
                thumbnailUrl = listOf("cover_xl", "cover_big", "cover_medium")
                    .firstNotNullOfOrNull { album?.optString(it)?.takeIf(String::isNotBlank) },
                matchScore = score,
                rank = item.optLong("rank").coerceAtLeast(0L),
                source = "Deezer",
            )
        }
    }

    private fun apple(response: SearchResponse, expected: SearchScoring.Expected): List<SpotiFlacSearchCandidate> {
        // The iTunes Search API answers 403 when it is rate limiting.
        if (response.code == 403 || response.code == 429) throw RateLimited("Apple Music")
        if (response.code !in 200..299) throw Unavailable("Apple Music")
        val results = JSONObject(response.body).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("trackId").takeIf { it > 0 } ?: return@mapNotNull null
            val artist = item.optString("artistName").trim()
            val title = item.optString("trackName").trim()
            if (artist.isBlank() || title.isBlank()) return@mapNotNull null
            val score = SearchScoring.score(expected, artist, title)
            if (score < expected.minimumScore) return@mapNotNull null
            SpotiFlacSearchCandidate(
                metadataId = "$APPLE_PREFIX$id",
                artist = artist,
                title = title,
                album = item.optString("collectionName").trim(),
                durationMs = item.optLong("trackTimeMillis").coerceAtLeast(0L),
                thumbnailUrl = item.optString("artworkUrl100").takeIf { it.startsWith("https://") }?.replace("100x100", "600x600"),
                matchScore = score,
                source = "Apple Music",
            )
        }
    }

    private class RateLimited(service: String) :
        IllegalStateException("$service is rate-limiting searches. Wait a few seconds and try again.")
    private class Unavailable(service: String) :
        IllegalStateException("$service search is unavailable right now. Try again later.")

    companion object {
        const val APPLE_PREFIX = "apple:"
        const val PROVIDER_PREFIX = "sf:"

        fun providerName(id: String) = when (id.lowercase(Locale.ROOT)) {
            "tidal-web", "tidal" -> "Tidal"
            "qobuz-web", "qobuz" -> "Qobuz"
            "deezer" -> "Deezer"
            "amazon" -> "Amazon Music"
            else -> id.ifBlank { "SpotiFLAC" }
        }
        private const val LIMIT = 25
        private const val VISIBLE = 8
        private const val STRONG = 85
        private const val EXACT = 97
        private const val ENOUGH_STRONG = 3
        private const val MAX_PHRASINGS = 6

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        /**
         * The phrasings to try, most literal first, without repeats:
         * as typed · hyphens as spaces · hyphenated words joined · field search with
         * the first one or two words as the artist · the title alone.
         */
        fun phrasings(query: String, expected: SearchScoring.Expected = SearchScoring.parse(query)): List<String> {
            val out = LinkedHashSet<String>()
            out += query
            out += SearchScoring.spaced(query)
            out += query.replace(Regex("(?<=\\p{L})[-‐‑–](?=\\p{L})"), "")
            if (expected.artist != null && expected.title != null) {
                out += field(expected.artist, expected.title)
                out += expected.title
            } else {
                val words = query.split(' ').filter(String::isNotBlank)
                for (k in 1..minOf(2, words.size - 2)) {
                    out += field(words.take(k).joinToString(" "), words.drop(k).joinToString(" "))
                }
                if (words.size >= 3) out += words.drop(1).joinToString(" ")
            }
            return out.filter { it.isNotBlank() }.take(MAX_PHRASINGS)
        }

        private fun field(artist: String, title: String) =
            "artist:\"${artist.replace("\"", "")}\" track:\"${title.replace("\"", "")}\""
    }
}

/** Title/artist similarity, shared by every phrasing and source. */
internal object SearchScoring {
    data class Expected(val raw: String, val artist: String?, val title: String?, val minimumScore: Int)

    private val dash = Regex("\\s+[-–—]\\s+")
    private val marks = Regex("\\p{Mn}+")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
    private val featuring = Regex("\\b(feat(?:uring)?|ft)\\.?\\b", RegexOption.IGNORE_CASE)
    private val stopWords = setOf("the", "and", "feat", "featuring", "ft", "official", "audio", "video")
    private val versionMarkers = listOf("live", "remix", "remaster", "acoustic", "instrumental", "karaoke", "sped up", "slowed", "cover")

    /** "Artist - Title" (spaced dash) is an explicit split; anything else is free text. */
    fun parse(query: String): Expected {
        val split = dash.split(query.trim(), limit = 2)
        val artist = split.getOrNull(0)?.trim().orEmpty()
        val title = split.getOrNull(1)?.trim().orEmpty()
        return if (split.size == 2 && artist.length >= 2 && title.length >= 2) Expected(query, artist, title, 70)
        else Expected(query, null, null, 56)
    }

    /** Punctuation as spaces: "frati-miu" → "frati miu". Diacritics are kept for the service to match. */
    fun spaced(value: String) = value.replace(Regex("[-‐‑–—_/.,;:!?'’\"()\\[\\]]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(marks, "").lowercase(Locale.ROOT)
        .replace(featuring, " ").replace(nonAlphanumeric, " ").replace(Regex("\\s+"), " ").trim()

    private fun compact(value: String) = normalize(value).replace(" ", "")

    fun score(expected: Expected, candidateArtist: String, candidateTitle: String): Int {
        val penalty = versionPenalty(expected.raw, candidateTitle)
        val score = if (expected.artist != null && expected.title != null) {
            // An explicit "artist - title" must match both sides: no famous but unrelated song.
            split(expected.artist, expected.title, candidateArtist, candidateTitle)
                // People type the two sides either way round.
                .coerceAtLeast(split(expected.title, expected.artist, candidateArtist, candidateTitle))
        } else {
            val words = expected.raw.split(' ').filter(String::isNotBlank)
            // The query names none of this result's artists: then only a title that covers
            // (nearly) the whole query counts. Stops "Droguri" by someone else, or a cover
            // band, from riding in on two shared words.
            if (words.size >= 2 && coverage(candidateArtist, expected.raw) == 0.0 &&
                (coverage(expected.raw, candidateTitle) < 0.9 || fuzzy(expected.raw, candidateTitle) < 85)) return 0
            val full = "$candidateArtist $candidateTitle"
            val loose = maxOf(fuzzy(expected.raw, full).toDouble(), fuzzy(expected.raw, candidateTitle) * 0.92) * 0.62 +
                coverage(expected.raw, full) * 100.0 * 0.38
            // Free text is usually "artist title" or "title artist": try each split point.
            val bySplit = (1 until words.size).maxOfOrNull { k ->
                val head = words.take(k).joinToString(" "); val tail = words.drop(k).joinToString(" ")
                maxOf(split(head, tail, candidateArtist, candidateTitle), split(tail, head, candidateArtist, candidateTitle))
            } ?: 0.0
            maxOf(loose, bySplit)
        }
        return (score - penalty).toInt().coerceIn(0, 100)
    }

    private fun split(artist: String, title: String, candidateArtist: String, candidateTitle: String): Double {
        val titleScore = fuzzy(title, candidateTitle)
        val artistScore = fuzzy(artist, candidateArtist)
        if (titleScore < 62 || artistScore < 48) return 0.0
        return titleScore * 0.68 + artistScore * 0.32
    }

    fun fuzzy(expected: String, actual: String): Int {
        val left = normalize(expected); val right = normalize(actual)
        if (left.isBlank() || right.isBlank()) return 0
        if (left == right) return 100
        // "fratimiu" vs "frati-miu" vs "frati miu": the same words, spaced differently.
        val l = compact(expected); val r = compact(actual)
        if (l == r) return 98
        val containment = when {
            right.contains(left) || (l.length >= 4 && r.contains(l)) -> 0.92
            left.contains(right) || (r.length >= 4 && l.contains(r)) -> 0.86
            else -> 0.0
        }
        val best = maxOf(edit(left, right), coverage(left, right) * 0.96, containment)
        return (best * 100.0).toInt().coerceIn(0, 100)
    }

    private fun edit(left: String, right: String): Double {
        val a = left.take(120); val b = right.take(120)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            for (j in previous.indices) previous[j] = current[j]
        }
        return (1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)).coerceIn(0.0, 1.0)
    }

    private fun tokens(value: String) = normalize(value).split(' ').filter { it.length >= 2 && it !in stopWords }.toSet()

    private fun coverage(expected: String, actual: String): Double {
        val want = tokens(expected); val have = tokens(actual)
        if (want.isEmpty() || have.isEmpty()) return 0.0
        val haveCompact = compact(actual)
        val matched = want.count { t -> have.any { o -> t == o || (t.length >= 4 && o.startsWith(t)) } || (t.length >= 4 && haveCompact.contains(t)) }
        return matched.toDouble() / want.size
    }

    private fun versionPenalty(query: String, candidateTitle: String): Double {
        val q = normalize(query); val c = normalize(candidateTitle)
        return versionMarkers.sumOf { if (c.contains(it) && !q.contains(it)) 7.0 else 0.0 }.coerceAtMost(18.0)
    }
}
