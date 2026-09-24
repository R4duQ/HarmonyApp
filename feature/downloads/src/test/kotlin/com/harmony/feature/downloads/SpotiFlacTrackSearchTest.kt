package com.harmony.feature.downloads

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder

class SpotiFlacTrackSearchTest {
    private val calls = mutableListOf<String>()

    private fun deezer(vararg tracks: Triple<Long, String, String>) = SearchResponse(200, """{"data":[${
        tracks.joinToString(",") { (id, artist, title) ->
            """{"id":$id,"title":"$title","title_short":"$title","duration":187,"rank":500000,"artist":{"name":"$artist"},"album":{"title":"Single","cover_xl":"https://c/x.jpg"}}"""
        }
    }],"total":${tracks.size}}""")
    private val emptyDeezer = SearchResponse(200, """{"data":[],"total":0}""")
    private fun apple(id: Long, artist: String, title: String) = SearchResponse(200,
        """{"resultCount":1,"results":[{"trackId":$id,"artistName":"$artist","trackName":"$title","collectionName":"Single","trackTimeMillis":187000,"artworkUrl100":"https://a/100x100bb.jpg"}]}""")

    /** Answers by the decoded query text, so each test states which phrasing the service "knows". */
    private fun search(query: String, country: String = "RO", answer: (host: String, q: String) -> SearchResponse) = runBlocking {
        SpotiFlacTrackSearch({ url ->
            calls += url
            val q = URLDecoder.decode(url.substringAfter("q=", url.substringAfter("term=")).substringBefore("&"), "UTF-8")
            answer(if ("deezer" in url) "deezer" else "apple", q)
        }, country).search(query)
    }

    private val song = Triple(3_141_592L, "Oscar", "Frati-miu vinde droguri")

    private fun tidal(vararg tracks: String) = "[" + tracks.joinToString(",") + "]"
    private fun tidalTrack(id: String, artist: String, title: String, isrc: String? = "ROA231900123", deezerId: String? = null) =
        """{"id":"$id","name":"$title","artists":"$artist","album_name":"Single","duration_ms":187000,"cover_url":"https://t/c.jpg",""" +
            (isrc?.let { "\"isrc\":\"$it\"," } ?: "") + (deezerId?.let { "\"deezer_id\":\"$it\"," } ?: "") + """"provider_id":"tidal-web"}"""

    private fun searchWithProviders(query: String, providers: suspend (String, Int) -> String, answer: (host: String, q: String) -> SearchResponse) = runBlocking {
        SpotiFlacTrackSearch({ url ->
            calls += url
            val q = URLDecoder.decode(url.substringAfter("q=", url.substringAfter("term=")).substringBefore("&"), "UTF-8")
            answer(if ("deezer" in url) "deezer" else "apple", q)
        }, "RO", providers, providerTimeoutMs = 200).search(query)
    }

    @Test fun tidalThroughSpotiFlacIsAskedFirstAndAnExactHitNeedsNothingElse() {
        val asked = mutableListOf<String>()
        val results = searchWithProviders("oscar frati-miu vinde droguri", { q, _ -> asked += q; tidal(tidalTrack("77", "Oscar", "Frati-miu vinde droguri")) }) { _, _ -> emptyDeezer }
        val hit = results.single()
        assertEquals("Tidal", hit.source)
        assertEquals("sf:tidal-web:77", hit.metadataId)
        assertEquals("ROA231900123", hit.isrc)
        assertEquals(listOf("oscar frati-miu vinde droguri"), asked)
        assertTrue("no Deezer or Apple call after an exact Tidal match", calls.isEmpty())
    }

    @Test fun aTidalTrackThatKnowsItsDeezerIdUsesIt() {
        val results = searchWithProviders("oscar frati-miu vinde droguri",
            { _, _ -> tidal(tidalTrack("77", "Oscar", "Frati-miu vinde droguri", deezerId = "3141592")) }) { _, _ -> emptyDeezer }
        assertEquals("3141592", results.single().metadataId)
    }

    @Test fun whenTidalNeedsVerificationDeezerStillAnswers() {
        val results = searchWithProviders("oscar frati-miu vinde droguri",
            { _, _ -> throw Exception("verification_required: extension 'tidal-web' needs verification") }) { host, _ ->
            if (host == "deezer") deezer(song) else emptyDeezer
        }
        assertEquals("Deezer", results.single().source)
    }

    @Test fun whenNothingIsFoundTheVerificationProblemIsExplained() {
        try {
            searchWithProviders("oscar frati-miu vinde droguri",
                { _, _ -> throw Exception("verification_required: extension 'tidal-web' needs verification") }) { host, _ ->
                if (host == "deezer") emptyDeezer else SearchResponse(200, """{"results":[]}""")
            }
            fail("expected the verification message")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("verification"))
        }
    }

    @Test fun aSlowEngineFallsBackInsteadOfSpinning() {
        val results = searchWithProviders("oscar frati-miu vinde droguri",
            { _, _ -> kotlinx.coroutines.delay(5_000); "[]" }) { host, _ -> if (host == "deezer") deezer(song) else emptyDeezer }
        assertEquals("Deezer", results.single().source)
    }

    @Test fun theSameSongFromTidalAndDeezerAppearsOnceKeepingTheIsrc() {
        val results = searchWithProviders("oscar frati-miu vinde droguri",
            { _, _ -> tidal(tidalTrack("77", "Oscar", "Frati-miu vinde droguri (Live)")) }) { host, _ ->
            if (host == "deezer") deezer(Triple(5L, "Oscar", "Frati-miu vinde droguri (Live)")) else emptyDeezer
        }
        assertEquals(1, results.size)
        assertEquals("ROA231900123", results.single().isrc)
    }

    @Test fun providerResultsGoThroughTheSameArtistCheck() {
        val results = searchWithProviders("oscar frati-miu vinde droguri",
            { _, _ -> tidal(tidalTrack("1", "Someone Else", "Droguri"), tidalTrack("2", "Oscar", "Frati-miu vinde droguri")) }) { _, _ -> emptyDeezer }
        assertEquals(listOf("Oscar"), results.map { it.artist })
    }

    @Test fun theReportedQueryIsFoundWhenDeezerOnlyMatchesTheSpacedPhrasing() {
        val results = search("oscar frati-miu vinde droguri") { host, q ->
            if (host == "deezer" && q == "oscar frati miu vinde droguri") deezer(song) else emptyDeezer
        }
        assertEquals("Frati-miu vinde droguri", results.single().title)
        assertTrue(results.single().matchScore >= 95)
        assertEquals("3141592", results.single().metadataId)
    }

    @Test fun fieldSearchFindsItWhenPlainTextDoesNot() {
        val results = search("oscar frati-miu vinde droguri") { host, q ->
            if (host == "deezer" && q == "artist:\"oscar\" track:\"frati-miu vinde droguri\"") deezer(song) else emptyDeezer
        }
        assertEquals("Oscar", results.single().artist)
    }

    @Test fun theTitleAloneIsTriedAndTheArtistStillHasToMatch() {
        val results = search("oscar frati-miu vinde droguri") { host, q ->
            if (host == "deezer" && q == "frati-miu vinde droguri")
                deezer(song, Triple(7L, "Some Cover Band", "Frati-miu vinde droguri (Cover)"), Triple(8L, "Other Artist", "Droguri"))
            else emptyDeezer
        }
        assertEquals(listOf("Oscar"), results.map { it.artist })
    }

    @Test fun titleFirstJoinedAndArtistDashTitleQueriesAllMatch() {
        for (query in listOf("frati-miu vinde droguri oscar", "oscar fratimiu vinde droguri", "Oscar - Frati-miu vinde droguri",
            "Frati-miu vinde droguri - Oscar")) {
            val results = search(query) { host, _ -> if (host == "deezer") deezer(song) else emptyDeezer }
            assertEquals(query, "Frati-miu vinde droguri", results.single().title)
        }
    }

    @Test fun anUnrelatedSongWithOneSharedWordIsStillRejected() {
        val results = search("oscar frati-miu vinde droguri") { host, _ ->
            if (host == "deezer") deezer(Triple(1L, "Oscar Isaac", "Oscar Winning Tears"), Triple(2L, "Random", "Oscar")) else SearchResponse(200, """{"results":[]}""")
        }
        assertTrue(results.isEmpty())
    }

    @Test fun appleMusicInThePhonesCountryIsTheFallback() {
        val results = search("oscar frati-miu vinde droguri", country = "RO") { host, _ ->
            if (host == "apple") apple(99, "Oscar", "Frati-miu vinde droguri") else emptyDeezer
        }
        val hit = results.single()
        assertEquals("apple:99", hit.metadataId)
        assertEquals("Apple Music", hit.source)
        assertEquals("https://a/600x600bb.jpg", hit.thumbnailUrl)
        assertTrue(calls.last().contains("country=RO"))
    }

    @Test fun deezerRateLimitSkipsStraightToAppleAndTriesNoMorePhrasings() {
        val results = search("oscar frati-miu vinde droguri") { host, _ ->
            if (host == "deezer") SearchResponse(429, "") else apple(99, "Oscar", "Frati-miu vinde droguri")
        }
        assertEquals(1, calls.count { "deezer" in it })
        assertEquals("Apple Music", results.single().source)
    }

    @Test fun whenEveryServiceFailsTheErrorIsShownNotNoMatch() {
        try {
            search("oscar frati-miu vinde droguri") { host, _ -> if (host == "deezer") SearchResponse(503, "") else SearchResponse(403, "") }
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unavailable"))
        }
    }

    @Test fun strongMatchesStopTheSearchEarly() {
        search("oscar frati-miu vinde droguri") { host, _ ->
            if (host == "deezer") deezer(song, Triple(2L, "Oscar", "Frati-miu vinde droguri (Remix)"), Triple(3L, "Oscar", "Frati-miu vinde droguri (Live)")) else emptyDeezer
        }
        assertEquals(1, calls.size)
    }

    @Test fun theSameTrackFromSeveralPhrasingsAppearsOnce() {
        val results = search("oscar frati-miu vinde droguri") { host, _ -> if (host == "deezer") deezer(song) else emptyDeezer }
        assertEquals(1, results.size)
    }

    @Test fun phrasingsAreOrderedAndDistinct() {
        assertEquals(listOf(
            "oscar frati-miu vinde droguri",
            "oscar frati miu vinde droguri",
            "oscar fratimiu vinde droguri",
            "artist:\"oscar\" track:\"frati-miu vinde droguri\"",
            "artist:\"oscar frati-miu\" track:\"vinde droguri\"",
            "frati-miu vinde droguri",
        ), SpotiFlacTrackSearch.phrasings("oscar frati-miu vinde droguri"))
        assertEquals(listOf("Oscar - Frati-miu", "Oscar Frati miu", "Oscar - Fratimiu", "artist:\"Oscar\" track:\"Frati-miu\"", "Frati-miu"),
            SpotiFlacTrackSearch.phrasings("Oscar - Frati-miu"))
    }

    @Test fun downloadedFileCheckAcceptsTheSameTitleSpacedDifferently() {
        assertTrue(SearchScoring.fuzzy("Frati-miu vinde droguri", "Fratimiu Vinde Droguri") >= 68)
        assertTrue(SearchScoring.fuzzy("Frați-miu vinde droguri", "Frati-miu vinde droguri") == 100)
        assertTrue(SearchScoring.fuzzy("Frati-miu vinde droguri", "Sunt pe drum") < 68)
    }
}
