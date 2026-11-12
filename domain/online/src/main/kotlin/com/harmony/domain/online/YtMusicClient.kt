package com.harmony.domain.online

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * YouTube Music's two anonymous calls Harmony uses, made the way ytmusicapi
 * 1.12.3 makes them (`YTMusicBase._send_request`, `SearchMixin.search`,
 * `WatchMixin.get_watch_playlist`), without an account:
 *
 *  - `POST /youtubei/v1/search?alt=json` with the songs filter
 *    (`params = EgWKAQIIAWoMEA4QChADEAQQCRAF`);
 *  - `POST /youtubei/v1/next?alt=json` for the radio (`params = wAEB`,
 *    `playlistId = RDAMVM<videoId>`), then its continuations
 *    (`&ctoken=…&continuation=…`) until [radio]'s limit or [MAX_RADIO_PAGES].
 *
 * The client context is ytmusicapi's (`WEB_REMIX`, `1.<UTC date>.01.00`,
 * `hl=en`); the visitor id is read once from the home page's `ytcfg`, as
 * ytmusicapi does, and kept. No cookie but `SOCS=CAI` (the consent answer
 * ytmusicapi sends), no account, no API key.
 *
 * What is logged: nothing here. Callers log ids, counts and timings only.
 */
class YtMusicClient(
    private val transport: HttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Two-letter country for `gl`, as ytmusicapi's `location`; null lets the server decide. */
    private val location: String? = null,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : OnlineCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val visitorLock = Mutex()
    @Volatile private var visitorId: String? = null

    /** Requests made, by endpoint (for tests and the diagnostics line). */
    val requests: Map<String, Int> get() = synchronized(counts) { counts.toMap() }
    private val counts = HashMap<String, Int>()

    override suspend fun searchSongs(query: String, limit: Int): CatalogResult<List<CatalogTrack>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return CatalogResult.Ok(emptyList())
        val body = buildJsonObject {
            put("query", trimmed)
            put("params", SONGS_FILTER_PARAMS)
        }
        return call("search", body) { root -> YtmParsers.songSearch(root).take(limit) }
    }

    override suspend fun radio(videoId: String, limit: Int): CatalogResult<List<CatalogTrack>> {
        if (!CatalogTrack.isVideoId(videoId)) return CatalogResult.Failed(CatalogFailure.SchemaChanged("videoId"))
        // get_watch_playlist(videoId, radio=True): no watchEndpointMusicSupportedConfigs with radio.
        val body = buildJsonObject {
            put("enablePersistentPlaylistPanel", true)
            put("isAudioOnly", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("params", RADIO_PARAMS)
        }
        val first = request("next", body, "")
        val firstRoot = (first as? CatalogResult.Ok)?.value ?: return first as CatalogResult.Failed
        val tracks = mutableListOf<CatalogTrack>()
        var panel = try {
            YtmParsers.watchPanel(firstRoot) ?: return CatalogResult.Failed(CatalogFailure.SchemaChanged("next.playlistPanelRenderer"))
        } catch (e: YtmParsers.SchemaException) {
            return CatalogResult.Failed(CatalogFailure.SchemaChanged(e.where))
        }
        try {
            tracks += YtmParsers.watchTracks(panel)
        } catch (e: RuntimeException) {
            return CatalogResult.Failed(CatalogFailure.SchemaChanged("next: ${e.javaClass.simpleName}"))
        }
        var pages = 1
        while (tracks.size < limit && pages < MAX_RADIO_PAGES) {
            // As ytmusicapi: the token goes into the URL as given (it comes URL-encoded already).
            // Anything but URL-safe characters is not followed.
            val token = YtmParsers.radioContinuation(panel)?.takeIf(CONTINUATION::matches) ?: break
            val next = request("next", body, "&ctoken=$token&continuation=$token")
            // A later page failing still leaves the tracks already read.
            val root = (next as? CatalogResult.Ok)?.value ?: break
            panel = YtmParsers.continuationPanel(root) ?: break
            // Seen live (2026-11, anonymous): a continuation can repeat the first page.
            // ytmusicapi returns the repeats; Harmony stops when a page brings nothing new.
            val known = tracks.mapTo(HashSet()) { it.videoId }
            val more = runCatching { YtmParsers.watchTracks(panel) }.getOrDefault(emptyList()).filter { it.videoId !in known }
            if (more.isEmpty()) break
            tracks += more
            pages++
        }
        return CatalogResult.Ok(tracks.distinctBy { it.videoId }.take(limit))
    }

    private suspend fun <T> call(endpoint: String, body: JsonObject, parse: (JsonObject) -> T): CatalogResult<T> =
        when (val reply = request(endpoint, body, "")) {
            is CatalogResult.Failed -> reply
            is CatalogResult.Ok -> try {
                CatalogResult.Ok(parse(reply.value))
            } catch (e: YtmParsers.SchemaException) {
                CatalogResult.Failed(CatalogFailure.SchemaChanged(e.where))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                CatalogResult.Failed(CatalogFailure.SchemaChanged("$endpoint: ${e.javaClass.simpleName}"))
            }
        }

    /** One POST to `/youtubei/v1/<endpoint>`, the context merged into [body] as ytmusicapi does. */
    private suspend fun request(endpoint: String, body: JsonObject, extraParams: String): CatalogResult<JsonObject> {
        val visitor = visitor()
        val payload = JsonObject(body + context())
        val headers = baseHeaders() + buildMap {
            put("Content-Type", "application/json")
            if (!visitor.isNullOrEmpty()) put("X-Goog-Visitor-Id", visitor)
        }
        synchronized(counts) { counts[endpoint] = (counts[endpoint] ?: 0) + 1 }
        val reply = try {
            transport.execute(
                HttpRequest("POST", "$API_BASE$endpoint$PARAMS$extraParams", headers, payload.toString().toByteArray(Charsets.UTF_8), timeoutMs),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return CatalogResult.Failed(networkFailure(e))
        } catch (e: Exception) {
            // A platform's HTTP stack can throw more than IOException (a SecurityException, say).
            return CatalogResult.Failed(CatalogFailure.Unexpected(e.javaClass.simpleName))
        }
        if (reply.status == 429) return CatalogResult.Failed(CatalogFailure.RateLimited(retryAfterMs(reply.headers["retry-after"])))
        if (reply.status >= 400) return CatalogResult.Failed(CatalogFailure.Http(reply.status))
        val root = try {
            json.parseToJsonElement(reply.body).jsonObject
        } catch (e: RuntimeException) {
            return CatalogResult.Failed(CatalogFailure.SchemaChanged("$endpoint: not JSON"))
        }
        return CatalogResult.Ok(root)
    }

    /** The visitor id from the home page's `ytcfg` (ytmusicapi `get_visitor_id`); null when it can't be had. */
    private suspend fun visitor(): String? {
        visitorId?.let { return it }
        return visitorLock.withLock {
            visitorId?.let { return it }
            val reply = try {
                synchronized(counts) { counts["home"] = (counts["home"] ?: 0) + 1 }
                transport.execute(HttpRequest("GET", DOMAIN, baseHeaders(), null, timeoutMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            if (reply.status != 200) return null
            // The page is large; a regex engine that gives up on it (Android's ICU can) means no id, not a crash.
            val found = runCatching {
                YTCFG.findAll(reply.body).firstNotNullOfOrNull { m ->
                    runCatching {
                        val cfg = json.parseToJsonElement(m.groupValues[1]).jsonObject
                        (cfg["VISITOR_DATA"] as? JsonPrimitive)?.content
                    }.getOrNull()
                }
            }.getOrNull()
            // An empty id is remembered too: ytmusicapi sends the header empty and the calls still work.
            (found ?: "").also { visitorId = it }
        }
    }

    private fun context(): Map<String, JsonElement> = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", CLIENT_NAME)
                put("clientVersion", clientVersion())
                put("hl", LANGUAGE)
                location?.let { put("gl", it) }
            }
            putJsonObject("user") {}
        }
    }

    /** ytmusicapi: `"1." + time.strftime("%Y%m%d", time.gmtime()) + ".01.00"`. */
    internal fun clientVersion(): String =
        "1." + DATE.format(Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.UTC)) + ".01.00"

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip",
        "Origin" to DOMAIN,
        "Cookie" to "SOCS=CAI",
    )

    private fun networkFailure(e: IOException): CatalogFailure = when (e) {
        is SocketTimeoutException -> CatalogFailure.Timeout
        is UnknownHostException, is ConnectException -> CatalogFailure.Offline
        else -> if (e.cause is SocketTimeoutException) CatalogFailure.Timeout else CatalogFailure.Offline
    }

    private fun retryAfterMs(value: String?): Long? =
        value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000L)

    companion object {
        /** The ytmusicapi release this client follows (its parsers and request bodies). */
        const val YTMUSICAPI_VERSION = "1.12.3"
        const val DOMAIN = "https://music.youtube.com"
        private const val API_BASE = "$DOMAIN/youtubei/v1/"
        private const val PARAMS = "?alt=json"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val LANGUAGE = "en"
        // ytmusicapi constants.USER_AGENT.
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0"
        // get_search_params("songs", None, False): "EgWKAQ" + "II" + "AWoMEA4QChADEAQQCRAF".
        const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        // get_watch_playlist(radio=True).
        const val RADIO_PARAMS = "wAEB"
        const val MAX_RADIO_PAGES = 3
        const val DEFAULT_TIMEOUT_MS = 10_000
        private val CONTINUATION = Regex("^[A-Za-z0-9%_-]{8,512}$")
        private val YTCFG = Regex("ytcfg\\.set\\s*\\(\\s*(\\{.+?\\})\\s*\\)\\s*;")
        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
