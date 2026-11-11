package com.harmony.domain.online

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/** A transport answering from a script: the home page first, then each POST in turn. */
private class ScriptedTransport(
    private val home: HttpReply = HttpReply(200, emptyMap(), """<script>ytcfg.set({"VISITOR_DATA":"visitor-123"});</script>"""),
    private val replies: MutableList<suspend (HttpRequest) -> HttpReply>,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpReply {
        requests += request
        if (request.method == "GET") return home
        val next = replies.removeFirstOrNull() ?: error("unexpected request ${request.url}")
        return next(request)
    }
}

/** [root] with every byline run that is a duration or an album removed, with the separator before it. */
private fun withoutDurationAndAlbum(root: JsonElement): JsonElement = when (root) {
    is JsonObject -> JsonObject(root.mapValues { (key, value) ->
        if (key == "runs" && value is JsonArray) {
            val keep = mutableListOf<JsonElement>()
            for (run in value) {
                val text = ((run as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
                val browse = run.toString().contains("\"MPREb_")
                if (Regex("^\\d+:\\d+$").matches(text) || browse) {
                    if ((keep.lastOrNull() as? JsonObject)?.get("text")?.toString() == "\" • \"") keep.removeAt(keep.size - 1)
                    continue
                }
                keep += run
            }
            JsonArray(keep)
        } else withoutDurationAndAlbum(value)
    })
    is JsonArray -> JsonArray(root.map(::withoutDurationAndAlbum))
    else -> root
}

private fun ok(path: String): suspend (HttpRequest) -> HttpReply = { HttpReply(200, emptyMap(), fixtureText(path)) }

class YtMusicClientTest {
    private val nov11 = 1_794_398_400_000L // 2026-11-11T12:00:00Z

    @Test
    fun searchIsTheSongsFilteredCallYtmusicapiMakes() = runTest {
        val t = ScriptedTransport(replies = mutableListOf(ok("derived/search_songs_atv.json")))
        val client = YtMusicClient(t, clock = { nov11 }, location = "RO")
        val r = client.searchSongs("  Subcarpați Pe Drum ", 10)
        val songs = (r as CatalogResult.Ok).value
        assertEquals(6, songs.size)

        val post = t.requests.last()
        assertEquals("https://music.youtube.com/youtubei/v1/search?alt=json", post.url)
        val body = Json.parseToJsonElement(post.body!!.decodeToString()).jsonObject
        assertEquals("Subcarpați Pe Drum", (body["query"] as JsonPrimitive).content)
        assertEquals("EgWKAQIIAWoMEA4QChADEAQQCRAF", (body["params"] as JsonPrimitive).content)
        val client0 = (body["context"] as JsonObject)["client"] as JsonObject
        assertEquals("WEB_REMIX", (client0["clientName"] as JsonPrimitive).content)
        assertEquals("1.20261111.01.00", (client0["clientVersion"] as JsonPrimitive).content)
        assertEquals("en", (client0["hl"] as JsonPrimitive).content)
        assertEquals("RO", (client0["gl"] as JsonPrimitive).content)
        assertEquals("visitor-123", post.headers["X-Goog-Visitor-Id"])
        assertEquals("SOCS=CAI", post.headers["Cookie"])
        // No account: nothing that authenticates, and nothing secret in the URL.
        assertFalse(post.headers.keys.any { it.equals("Authorization", true) })
        assertFalse("key=" in post.url)
    }

    @Test
    fun theVisitorIdIsReadOnceAndKept() = runTest {
        val t = ScriptedTransport(replies = mutableListOf(ok("derived/search_songs_atv.json"), ok("derived/search_songs_atv.json")))
        val client = YtMusicClient(t, clock = { nov11 })
        client.searchSongs("a b", 10)
        client.searchSongs("c d", 10)
        assertEquals(1, t.requests.count { it.method == "GET" })
        assertEquals(mapOf("home" to 1, "search" to 2), client.requests)
    }

    @Test
    fun noVisitorIdStillAsks() = runTest {
        val t = ScriptedTransport(home = HttpReply(500, emptyMap(), ""), replies = mutableListOf(ok("real/2026_11_search_songs_no_results_oasis.json")))
        val r = YtMusicClient(t, clock = { nov11 }).searchSongs("Oasis Wonderwall", 10)
        assertEquals(CatalogResult.Ok(emptyList<CatalogTrack>()), r)
        assertNull(t.requests.last().headers["X-Goog-Visitor-Id"])
    }

    @Test
    fun rateLimitedCarriesRetryAfter() = runTest {
        val t = ScriptedTransport(replies = mutableListOf({ HttpReply(429, mapOf("retry-after" to "12"), "{}") }))
        val r = YtMusicClient(t, clock = { nov11 }).searchSongs("x y", 10)
        assertEquals(CatalogResult.Failed(CatalogFailure.RateLimited(12_000)), r)
    }

    @Test
    fun serverErrorsTimeoutsAndNoNetworkAreTold() = runTest {
        suspend fun outcome(reply: suspend (HttpRequest) -> HttpReply) =
            (YtMusicClient(ScriptedTransport(replies = mutableListOf(reply)), clock = { nov11 }).searchSongs("x y", 10) as CatalogResult.Failed).failure
        assertEquals(CatalogFailure.Http(503), outcome { HttpReply(503, emptyMap(), "") })
        assertTrue((outcome { HttpReply(503, emptyMap(), "") } as CatalogFailure.Http).retryable)
        assertFalse((outcome { HttpReply(403, emptyMap(), "") } as CatalogFailure.Http).retryable)
        assertEquals(CatalogFailure.Timeout, outcome { throw SocketTimeoutException("read timed out") })
        assertEquals(CatalogFailure.Offline, outcome { throw ConnectException("no route") })
    }

    @Test
    fun aChangedSchemaIsNotMistakenForNoResults() = runTest {
        suspend fun outcome(body: String) =
            YtMusicClient(ScriptedTransport(replies = mutableListOf({ HttpReply(200, emptyMap(), body) })), clock = { nov11 }).searchSongs("x y", 10)
        // Not JSON at all.
        assertTrue((outcome("<html>consent</html>") as CatalogResult.Failed).failure is CatalogFailure.SchemaChanged)
        // JSON, but the section list moved.
        val moved = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"somethingNew":{}}}}]}}}"""
        assertTrue((outcome(moved) as CatalogResult.Failed).failure is CatalogFailure.SchemaChanged)
        // A song row without its second column: the layout changed under it.
        val noByline = fixtureText("derived/search_songs_atv.json").replace("\"flexColumns\":[", "\"flexColumnsOld\":[")
        assertTrue((outcome(noByline) as CatalogResult.Ok).value.isEmpty())
    }

    @Test
    fun missingFieldsStayUnknown() = runTest {
        // Drop the duration and the album (and their separators) from every row's byline.
        val stripped = withoutDurationAndAlbum(fixture("derived/search_songs_atv.json")).toString()
        val t = ScriptedTransport(replies = mutableListOf({ HttpReply(200, emptyMap(), stripped) }))
        val songs = (YtMusicClient(t, clock = { nov11 }).searchSongs("x y", 10) as CatalogResult.Ok).value
        assertEquals(6, songs.size)
        assertTrue(songs.all { it.durationMs == null })
        assertTrue(songs.all { it.album == null })
        // A radio track without a badge: explicit not known (ytmusicapi does not read it there).
        val radio = YtmParsers.watchTracks(YtmParsers.watchPanel(fixture("derived/watch_radio_atv.json"))!!)
        assertEquals(true, radio.first { it.videoId == "bbbbbbbbb03" }.explicit)
        assertNull(radio.first { it.videoId == "bbbbbbbbb01" }.explicit)
    }

    @Test
    fun radioFollowsContinuationsAndStopsWhenAPageRepeats() = runTest {
        val t = ScriptedTransport(replies = mutableListOf(
            ok("real/2026_11_watch_radio_ZrOKjDZOtkA.json"),
            ok("real/2026_11_watch_radio_ZrOKjDZOtkA.page1.json"),
            // The live page 2 repeated page 1: answered here with page 1 again.
            ok("real/2026_11_watch_radio_ZrOKjDZOtkA.page1.json"),
        ))
        val r = YtMusicClient(t, clock = { nov11 }).radio("ZrOKjDZOtkA", 25) as CatalogResult.Ok
        // Page 1 brings nothing new (the live answer repeated page 0): no third request.
        assertEquals(6, r.value.size)
        assertEquals(r.value.size, r.value.map { it.videoId }.toSet().size)
        val posts = t.requests.filter { it.method == "POST" }
        assertEquals(2, posts.size)
        val body = Json.parseToJsonElement(posts[0].body!!.decodeToString()).jsonObject
        assertEquals("RDAMVMZrOKjDZOtkA", (body["playlistId"] as JsonPrimitive).content)
        assertEquals("wAEB", (body["params"] as JsonPrimitive).content)
        // The token goes in as given (already URL-encoded), once in each parameter.
        assertTrue(posts[1].url.contains("&ctoken=CAYS") && posts[1].url.contains("%3D&continuation=CAYS"))
        assertFalse(posts[1].url.contains("%253D"))
    }

    @Test
    fun aBadVideoIdIsNotSent() = runTest {
        val t = ScriptedTransport(replies = mutableListOf())
        val r = YtMusicClient(t, clock = { nov11 }).radio("../../x?y", 25)
        assertTrue(r is CatalogResult.Failed)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun cancellingARequestCancelsIt() = runTest {
        val started = CompletableDeferred<Unit>()
        val t = ScriptedTransport(replies = mutableListOf({ started.complete(Unit); awaitCancellation() }))
        val client = YtMusicClient(t, clock = { nov11 })
        val call = async { client.searchSongs("x y", 10) }
        started.await()
        call.cancel()
        runCurrent()
        try {
            call.await()
            fail("a cancelled request must not turn into a result")
        } catch (_: CancellationException) {
        }
    }
}
