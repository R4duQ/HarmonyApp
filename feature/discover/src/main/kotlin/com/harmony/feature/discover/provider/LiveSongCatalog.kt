package com.harmony.feature.discover.provider

import com.harmony.domain.library.discovery.Genres
import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.library.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveryGenre(val id: Int, val name: String)
data class CatalogPage(val songs: List<DiscoverySong>, val note: String? = null)
data class CatalogArtist(val id: Long, val name: String, val picture: String = "", val fans: Int = 0)

/**
 * What the recommendation engine may ask of the online catalogs. Everything
 * here is public catalog data: search, top tracks, related artists, charts
 * and 30-second previews. None of it is a provider's personal recommendation.
 */
interface DiscoveryCatalog {
    val status: StateFlow<Map<String, SourceStatus>>
    suspend fun genres(): List<DiscoveryGenre>
    suspend fun genreFor(name: String): DiscoveryGenre?
    suspend fun findArtist(name: String): CatalogArtist?
    suspend fun searchArtists(query: String, limit: Int = 8): List<CatalogArtist>
    suspend fun artistTop(artist: CatalogArtist, limit: Int): List<DiscoverySong>
    suspend fun related(artist: CatalogArtist, limit: Int): List<CatalogArtist>
    suspend fun genreChart(genre: DiscoveryGenre, limit: Int): List<DiscoverySong>
    suspend fun appleArtistSongs(artist: String, limit: Int): List<DiscoverySong>
    suspend fun regionalChart(country: String): List<DiscoverySong>
    suspend fun page(filter: DiscoveryFilter, page: Int, likes: List<DiscoverySong>): CatalogPage
    suspend fun preview(song: DiscoverySong): String?
}

data class CatalogResponse(val code: Int, val body: String, val retryAfter: String? = null)

/** The network edge, replaceable in tests. Allowlisted hosts, HTTPS only, bounded reads. */
fun interface CatalogTransport { suspend fun fetch(url: String): CatalogResponse }

class HttpCatalogTransport : CatalogTransport {
    override suspend fun fetch(url: String): CatalogResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000; connection.readTimeout = 10_000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Harmony/1.0 (Android; song discovery)")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8_192)
                while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > 2_000_000) throw IOException("Catalog response too large"); output.write(buffer, 0, count) }
                output.toString("UTF-8")
            }.orEmpty()
            CatalogResponse(code, raw, connection.getHeaderField("Retry-After"))
        } finally { connection.disconnect() }
    }
}

/** Public metadata and provider previews only. No keys, private endpoints or full audio here. */
@Singleton
class LiveSongCatalog internal constructor(
    private val transport: CatalogTransport,
    private val now: () -> Long,
    private val retry: RetryPolicy,
    private val wait: suspend (Long) -> Unit,
) : DiscoveryCatalog {
    @Inject constructor() : this(HttpCatalogTransport(), System::currentTimeMillis, RetryPolicy(), { delay(it) })

    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Pair<Long, JSONObject>>()
    private val artists = object : LinkedHashMap<String, CatalogArtist?>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogArtist?>) = size > 400
    }
    private var lastBrainz = 0L
    private var lastApple = 0L
    private var availableGenres = emptyList<DiscoveryGenre>()
    private val downUntil = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SourceProblem>>()
    private val mutableStatus = MutableStateFlow<Map<String, SourceStatus>>(initialStatus)
    override val status: StateFlow<Map<String, SourceStatus>> = mutableStatus.asStateFlow()

    override suspend fun genres(): List<DiscoveryGenre> = withContext(Dispatchers.IO) {
        if (availableGenres.isNotEmpty()) return@withContext availableGenres
        rows(get("https://api.deezer.com/genre?lang=en")).map {
            DiscoveryGenre(it.optInt("id"), genreNames[it.optInt("id")] ?: it.optString("name"))
        }.filter { it.id > 0 && it.name.isNotBlank() }.also { availableGenres = it }
    }

    override suspend fun genreFor(name: String): DiscoveryGenre? {
        val family = Genres.family(name)
        val known = try { genres() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            .ifEmpty { fallbackGenres }
        return known.firstOrNull { Genres.family(it.name) == family }
    }

    override suspend fun findArtist(name: String): CatalogArtist? = withContext(Dispatchers.IO) {
        val key = TrackIdentity.primaryArtist(name)
        if (key.isBlank()) return@withContext null
        synchronized(artists) { if (artists.containsKey(key)) return@withContext artists[key] }
        // Same-name artists exist; the one most listeners follow is the likely match.
        val found = rows(get("https://api.deezer.com/search/artist?q=${encode(name)}&limit=10")).map(::artist)
            .filter { TrackIdentity.primaryArtist(it.name) == key || TrackIdentity.normalize(it.name) == TrackIdentity.normalize(name) }
            .maxByOrNull { it.fans }
        synchronized(artists) { artists[key] = found }
        found
    }

    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        rows(get("https://api.deezer.com/search/artist?q=${encode(query.take(60))}&limit=${limit.coerceIn(1, 25)}")).map(::artist)
            .filter { it.id > 0 && it.name.isNotBlank() }
    }

    override suspend fun artistTop(artist: CatalogArtist, limit: Int): List<DiscoverySong> = withContext(Dispatchers.IO) {
        rows(get("https://api.deezer.com/artist/${artist.id}/top?limit=${limit.coerceIn(1, 50)}"))
            .filter { it.optJSONObject("artist")?.optLong("id") == artist.id }
            .mapNotNull { deezer(it) }
    }

    override suspend fun related(artist: CatalogArtist, limit: Int): List<CatalogArtist> = withContext(Dispatchers.IO) {
        rows(get("https://api.deezer.com/artist/${artist.id}/related?limit=${limit.coerceIn(1, 25)}")).map(::artist)
            .filter { it.id > 0 && it.id != artist.id && it.name.isNotBlank() }
    }

    override suspend fun genreChart(genre: DiscoveryGenre, limit: Int): List<DiscoverySong> = withContext(Dispatchers.IO) {
        if (genre.id < 0) return@withContext emptyList() // 0 = Deezer's all-genres chart
        rows(get("https://api.deezer.com/chart/${genre.id}/tracks?limit=${limit.coerceIn(1, 100)}"))
            .mapNotNull { deezer(it, genres = setOf(genre.name).filter(String::isNotBlank).toSet()) }
    }

    /** Fallback when Deezer is down: the iTunes Search API, one artist at a time (it is rate limited). */
    override suspend fun appleArtistSongs(artist: String, limit: Int): List<DiscoverySong> = withContext(Dispatchers.IO) {
        val key = TrackIdentity.primaryArtist(artist)
        objects(get("https://itunes.apple.com/search?term=${encode(artist)}&entity=song&attribute=artistTerm&limit=${limit.coerceIn(1, 50)}")
            .optJSONArray("results")).mapNotNull { t ->
            val id = t.optLong("trackId"); val name = t.optString("artistName")
            if (id <= 0 || t.optString("trackName").isBlank() || TrackIdentity.primaryArtist(name) != key) return@mapNotNull null
            DiscoverySong("apple:$id", t.optString("trackName"), name, t.optString("collectionName"), t.optLong("trackTimeMillis"),
                https(t.optString("artworkUrl100")).replace("100x100", "600x600"), link = https(t.optString("trackViewUrl")),
                provider = "Apple Music", genres = setOf(t.optString("primaryGenreName")).filter { it.isNotBlank() && it != "Music" }.toSet(),
                market = t.optString("country").takeIf { it.length == 2 }?.uppercase().orEmpty())
        }
    }

    override suspend fun regionalChart(country: String): List<DiscoverySong> = withContext(Dispatchers.IO) { regional(country) }

    override suspend fun page(filter: DiscoveryFilter, page: Int, likes: List<DiscoverySong>): CatalogPage = withContext(Dispatchers.IO) {
        require(page in 0..999)
        if (filter.artistCountryOnly) return@withContext artistCountryPage(filter, page)
        val found = mutableListOf<DiscoverySong>(); val problems = mutableListOf<String>()
        suspend fun attempt(block: suspend () -> List<DiscoverySong>) {
            try { found += block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { problems += e.message ?: "Catalog unavailable" }
        }
        // Country charts seed the deck; genres/vibes and liked artists broaden it beyond the same top 100.
        if (page == 0 || (filter.country.isBlank() && page % 3 == 0)) attempt {
            regional(filter.country.ifBlank { markets[page % markets.size] })
        }
        if (filter.genreId > 0 || (filter.genre.isBlank() && filter.vibe.isBlank())) attempt {
            val options = availableGenres.ifEmpty { listOf(DiscoveryGenre(132, "Pop"), DiscoveryGenre(116, "Rap/Hip Hop")) }
            val category = if (filter.genreId > 0) DiscoveryGenre(filter.genreId, filter.genre) else options[page % options.size]
            val genre = if (page == 0 && filter.genreId == 0) 0 else category.id
            rows(get("https://api.deezer.com/chart/$genre/tracks?limit=100&index=${(page % 3) * 50}"))
                .mapNotNull { deezer(it, genres = if (genre > 0) setOf(category.name) else emptySet()) }
        }
        if (filter.genre.isNotBlank() || filter.vibe.isNotBlank()) attempt {
            val query = listOf(filter.genre, filter.vibe).filter(String::isNotBlank).joinToString(" ")
            val playlist = rows(get("https://api.deezer.com/search/playlist?q=${encode(query)}&limit=1&index=$page"))
                .firstOrNull() ?: return@attempt emptyList()
            val id = playlist.optLong("id").takeIf { it > 0 } ?: return@attempt emptyList()
            rows(get("https://api.deezer.com/playlist/$id/tracks?limit=100"))
                .mapNotNull { deezer(it, genres = setOf(filter.genre).filter(String::isNotBlank).toSet(), vibe = filter.vibe) }
        }
        val seed = likes.distinctBy { it.artist }.takeLast(12).let { if (it.isEmpty()) null else it[page % it.size] }
        if (seed != null && page > 0) attempt {
            val artists = rows(get("https://api.deezer.com/search/artist?q=${encode(seed.artist)}&limit=10"))
                .filter { DiscoveryRules.normalize(it.optString("name")) == DiscoveryRules.normalize(seed.artist) }
            if (artists.size != 1) return@attempt emptyList()
            rows(get("https://api.deezer.com/artist/${artists.single().getLong("id")}/top?limit=50&index=${(page % 4) * 25}"))
                .mapNotNull { deezer(it, genres = seed.genres) }
        }
        CatalogPage(DiscoveryRules.deduplicate(found).take(300), problems.distinct().joinToString(" · ").takeIf { it.isNotBlank() })
    }

    private suspend fun regional(country: String): List<DiscoverySong> {
        require(country.matches(Regex("[A-Z]{2}")))
        val root = get("https://rss.marketingtools.apple.com/api/v2/${country.lowercase()}/music/most-played/100/songs.json")
            .getJSONObject("feed")
        check(root.optString("country").equals(country, true)) { "Regional chart is unavailable for $country." }
        return objects(root.optJSONArray("results")).mapNotNull { t ->
            val id = t.optString("id")
            if (!id.matches(Regex("[0-9]+"))) null else DiscoverySong("apple:$id", t.optString("name"), t.optString("artistName"),
                artwork = https(t.optString("artworkUrl100")), link = https(t.optString("url")), provider = "Apple Music chart",
                genres = objects(t.optJSONArray("genres")).map { it.optString("name") }.filter { it != "Music" }.toSet(), market = country)
        }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
    }

    private suspend fun artistCountryPage(filter: DiscoveryFilter, page: Int): CatalogPage {
        val country = filter.country.ifBlank { markets[page % markets.size] }
        require(country.matches(Regex("[A-Z]{2}")))
        // MusicBrainz's country is the main associated country, not a birthplace guarantee.
        val genre = filter.genre.replace(Regex("[^\\p{L}\\p{N} -]"), " ").trim()
        val tagQuery = if (DiscoveryRules.normalize(genre) in setOf("rap hip hop", "hip hop rap", "rap", "hip hop"))
            "(tag:\"hip hop\" OR tag:rap)" else "tag:\"$genre\""
        val query = "country:$country" + if (genre.isNotBlank()) " AND $tagQuery" else ""
        val artists = objects(get("https://musicbrainz.org/ws/2/artist/?query=${encode(query)}&fmt=json&limit=6&offset=${page * 6}").optJSONArray("artists"))
        val songs = mutableListOf<DiscoverySong>()
        for (artist in artists.filter { it.optString("country") == country }) {
            currentCoroutineContext().ensureActive()
            val id = artist.optString("id")
            if (!id.matches(Regex("[a-f0-9-]{36}"))) continue
            val detail = get("https://musicbrainz.org/ws/2/artist/$id?inc=url-rels&fmt=json")
            // Only an explicit catalog relationship establishes artist identity. No name-only nationality guesses.
            val deezerId = objects(detail.optJSONArray("relations")).mapNotNull {
                deezerArtistId(it.optJSONObject("url")?.optString("resource").orEmpty())
            }.firstOrNull() ?: continue
            val tags = objects(artist.optJSONArray("tags")).map { it.optString("name") }.filter(String::isNotBlank).toSet()
            songs += rows(get("https://api.deezer.com/artist/$deezerId/top?limit=50")).mapNotNull {
                if (it.optJSONObject("artist")?.optLong("id") != deezerId) null
                else deezer(it, genres = tags).copyOrNull(country)
            }
            if (songs.size >= 100) break
        }
        return CatalogPage(DiscoveryRules.deduplicate(songs), if (songs.isEmpty())
            "No linked artists in this page. Try More songs, a broader genre, or Popular in country. Unknown artist countries are excluded." else null)
    }

    private fun DiscoverySong?.copyOrNull(country: String) = this?.copy(artistCountry = country)

    override suspend fun preview(song: DiscoverySong): String? = withContext(Dispatchers.IO) {
        // Signed previews expire: obtain fresh metadata rather than persist audio/signatures forever.
        when {
            song.key.startsWith("dz:") -> get("https://api.deezer.com/track/${song.key.substringAfter(':').also { require(it.matches(Regex("[0-9]+"))) }}", fresh = true)
                .optString("preview").takeIf(::validPreview)
            song.key.startsWith("apple:") -> {
                val id = song.key.substringAfter(':').also { require(it.matches(Regex("[0-9]+"))) }
                val country = song.market.ifBlank { "US" }.also { require(it.matches(Regex("[A-Z]{2}"))) }
                objects(get("https://itunes.apple.com/lookup?id=$id&country=$country&entity=song", fresh = true).optJSONArray("results"))
                    .firstOrNull { it.optString("trackId") == id }?.optString("previewUrl")?.takeIf(::validPreview)
            }
            else -> song.preview.takeIf(::validPreview)
        }
    }

    private fun deezer(t: JSONObject, genres: Set<String> = emptySet(), vibe: String = ""): DiscoverySong? {
        val id = t.optLong("id"); val artist = t.optJSONObject("artist")?.optString("name").orEmpty()
        if (id <= 0 || t.optString("title").isBlank() || artist.isBlank() || t.optLong("duration") <= 0) return null
        val album = t.optJSONObject("album")
        return DiscoverySong("dz:$id", t.getString("title"), artist, album?.optString("title").orEmpty(), t.getLong("duration") * 1000,
            https(album?.optString("cover_big").orEmpty()), link = https(t.optString("link")).ifBlank { "https://www.deezer.com/track/$id" },
            provider = "Deezer", genres = genres, vibe = vibe, isrc = t.optString("isrc").takeIf { it.isNotBlank() })
    }

    private fun artist(o: JSONObject) = CatalogArtist(o.optLong("id"), o.optString("name"),
        https(o.optString("picture_medium")), o.optInt("nb_fan"))

    /**
     * One catalog request: cache, per-host pacing, limited retry with backoff,
     * and a short circuit breaker so a failing source is skipped (and another
     * one used) instead of being hammered by every step of the pipeline.
     */
    private suspend fun get(url: String, fresh: Boolean = false): JSONObject {
        val host = URI(url).host
        require(host in allowedHosts)
        val source = sourceOf(host)
        downUntil[source]?.let { (until, problem) -> if (now() < until) throw SourceException(source, problem) }
        return try {
            retry.run(source, wait) { fetchOnce(url, host, source, fresh) }.also { markReady(source) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = SourceErrors.fromThrowable(source, e)
            markFailed(source, failure)
            throw failure
        }
    }

    private suspend fun fetchOnce(url: String, host: String, source: String, fresh: Boolean): JSONObject = mutex.withLock {
        val start = now()
        if (!fresh) cache[url]?.takeIf { start - it.first < 15 * 60_000 }?.let { return@withLock it.second }
        if (host == "musicbrainz.org") { wait((1_100 - (start - lastBrainz)).coerceAtLeast(0)); lastBrainz = now() }
        if (host == "itunes.apple.com") { wait((3_100 - (start - lastApple)).coerceAtLeast(0)); lastApple = now() }
        val response = transport.fetch(url)
        if (response.code != 200) throw SourceErrors.fromHttp(source, host, response.code, response.retryAfter)
        val json = try { JSONObject(response.body) } catch (e: org.json.JSONException) { throw SourceException(source, SourceProblem.BAD_RESPONSE, cause = e) }
        if (host == "api.deezer.com") SourceErrors.fromDeezerBody(json)?.let { throw it }
        else if (json.has("error")) throw SourceException(source, SourceProblem.BAD_RESPONSE)
        if (!fresh) { if (cache.size >= 64) cache.remove(cache.keys.first()); cache[url] = now() to json }
        json
    }

    private fun markReady(source: String) {
        downUntil.remove(source)
        mutableStatus.update { it + (source to (it[source] ?: SourceStatus(source, "", SourceState.READY)).copy(state = SourceState.READY, note = "")) }
    }

    private fun markFailed(source: String, e: SourceException) {
        val pause = when (e.problem) {
            SourceProblem.RATE_LIMITED -> maxOf(60_000L, e.retryAfterMs ?: 0)
            SourceProblem.UNAVAILABLE, SourceProblem.TIMEOUT -> 90_000L
            else -> 0L // offline is the connection's state, not the source's; auth needs the user
        }
        if (pause > 0) downUntil[source] = now() + pause to e.problem
        val state = if (e.problem == SourceProblem.RATE_LIMITED) SourceState.LIMITED else SourceState.DOWN
        mutableStatus.update { it + (source to (it[source] ?: SourceStatus(source, "", state)).copy(state = state, note = e.message.orEmpty())) }
    }

    companion object {
        private val allowedHosts = setOf("api.deezer.com", "rss.marketingtools.apple.com", "itunes.apple.com", "musicbrainz.org")
        fun sourceOf(host: String) = when (host) {
            "api.deezer.com" -> "Deezer"
            "musicbrainz.org" -> "MusicBrainz"
            else -> "Apple Music"
        }
        val initialStatus = linkedMapOf(
            "Deezer" to SourceStatus("Deezer", "Catalog, related artists, genre charts, 30 s previews", SourceState.READY),
            "Apple Music" to SourceStatus("Apple Music", "Country charts, previews, fallback catalog", SourceState.READY),
            "MusicBrainz" to SourceStatus("MusicBrainz", "Artist country, only with that filter", SourceState.READY),
            "Qobuz" to SourceStatus("Qobuz", "Not a discovery source", SourceState.NOT_USED,
                "Qobuz's catalog API needs an approved app ID, which Harmony doesn't have. It can still be a SpotiFLAC download provider."),
        )
        private val fallbackGenres = listOf(DiscoveryGenre(132, "Pop"), DiscoveryGenre(116, "Rap/Hip Hop"), DiscoveryGenre(152, "Rock"),
            DiscoveryGenre(113, "Dance"), DiscoveryGenre(165, "R&B"), DiscoveryGenre(85, "Alternative"), DiscoveryGenre(106, "Electro"),
            DiscoveryGenre(466, "Folk"), DiscoveryGenre(144, "Reggae"), DiscoveryGenre(129, "Jazz"), DiscoveryGenre(98, "Classical"),
            DiscoveryGenre(173, "Soundtracks"), DiscoveryGenre(464, "Metal"), DiscoveryGenre(169, "Soul & Funk"), DiscoveryGenre(153, "Blues"),
            DiscoveryGenre(197, "Latin"))
        // Deezer localizes names by IP even with lang=en. Stable names keep tag matching consistent.
        private val genreNames = mapOf(132 to "Pop", 116 to "Rap/Hip Hop", 152 to "Rock", 113 to "Dance", 165 to "R&B",
            85 to "Alternative", 106 to "Electro", 466 to "Folk", 144 to "Reggae", 129 to "Jazz", 98 to "Classical",
            173 to "Soundtracks", 464 to "Metal", 169 to "Soul & Funk", 153 to "Blues", 95 to "Kids", 197 to "Latin",
            2 to "African", 16 to "Asian", 75 to "Brazilian", 81 to "Indian")
        val markets = listOf("RO", "GB", "US", "FR", "DE", "ES", "IT", "CA", "BR", "JP", "AU", "NL", "KR", "IN", "MX", "SE")
        fun validPreview(value: String): Boolean = runCatching { val u = URI(value)
            u.scheme == "https" && u.rawUserInfo == null && (u.port == -1 || u.port == 443) &&
                (u.host?.endsWith(".dzcdn.net") == true || u.host?.endsWith(".itunes.apple.com") == true || u.host?.endsWith(".mzstatic.com") == true)
        }.getOrDefault(false)
        fun deezerArtistId(value: String): Long? = runCatching { val u = URI(value)
            if (u.scheme !in setOf("https", "http") || u.host !in setOf("deezer.com", "www.deezer.com") || u.rawUserInfo != null) null
            else Regex("^/(?:[a-z]{2}/)?artist/([0-9]+)/?$").matchEntire(u.path)?.groupValues?.get(1)?.toLongOrNull()
        }.getOrNull()
        private fun https(value: String) = value.takeIf { runCatching { URI(it).scheme == "https" && URI(it).rawUserInfo == null }.getOrDefault(false) }.orEmpty()
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun rows(root: JSONObject) = objects(root.optJSONArray("data"))
        private fun objects(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}
