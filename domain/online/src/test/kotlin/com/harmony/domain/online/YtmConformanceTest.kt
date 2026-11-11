package com.harmony.domain.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun fixture(path: String): JsonObject {
    val text = YtmConformanceTest::class.java.getResource("/ytmusic/$path")?.readText()
        ?: error("missing fixture $path")
    return Json.parseToJsonElement(text).jsonObject
}

internal fun fixtureText(path: String): String =
    YtmConformanceTest::class.java.getResource("/ytmusic/$path")?.readText() ?: error("missing fixture $path")

/**
 * Harmony's parser against ytmusicapi 1.12.3 on the same answers: every
 * field Harmony keeps must be what ytmusicapi reads. The expected files are
 * written by scripts/ytmusic/expected_outputs.py, which CI re-runs with the
 * pinned ytmusicapi (--check), so they cannot drift from the library.
 */
class YtmConformanceTest {
    private fun expected(name: String): List<JsonObject> {
        val doc = fixture("expected/$name")
        assertEquals("1.12.3", (doc["ytmusicapi"] as JsonPrimitive).content)
        return doc["results"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonObject.names(key: String): List<String> = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()

    private fun checkSearch(fixturePath: String) {
        val ours = YtmParsers.songSearch(fixture(fixturePath))
        val theirs = expected(fixturePath.substringAfterLast('/'))
        assertEquals("count for $fixturePath", theirs.size, ours.size)
        theirs.zip(ours).forEach { (e, a) ->
            val where = "$fixturePath ${e.str("videoId")}"
            assertEquals(where, e.str("videoId"), a.videoId)
            assertEquals(where, e.str("title"), a.title)
            assertEquals(where, e.names("artists"), a.artists)
            assertEquals(where, e.str("album"), a.album)
            assertEquals(where, e.str("albumId"), a.albumId)
            assertEquals(where, (e["duration_seconds"] as? JsonPrimitive)?.longOrNull?.times(1000), a.durationMs)
            assertEquals(where, e.str("videoType"), a.videoType)
            assertEquals(where, (e["isExplicit"] as JsonPrimitive).booleanOrNull, a.explicit)
            assertEquals(where, (e["isAvailable"] as JsonPrimitive).booleanOrNull, a.playable)
            assertEquals(where, e.str("year"), a.year)
        }
    }

    private fun checkWatch(fixturePath: String, vararg continuations: String) {
        val first = YtmParsers.watchPanel(fixture(fixturePath))!!
        val ours = YtmParsers.watchTracks(first) +
            continuations.flatMap { YtmParsers.watchTracks(YtmParsers.continuationPanel(fixture(it))!!) }
        val theirs = expected(fixturePath.substringAfterLast('/'))
        assertEquals("count for $fixturePath", theirs.size, ours.size)
        theirs.zip(ours).forEach { (e, a) ->
            val where = "$fixturePath ${e.str("videoId")}"
            assertEquals(where, e.str("videoId"), a.videoId)
            assertEquals(where, e.str("title"), a.title)
            assertEquals(where, YtmParsers.parseDuration(e.str("length"))?.times(1000), a.durationMs)
            assertEquals(where, e.str("videoType"), a.videoType)
            assertEquals(where, e.names("artists"), a.artists)
            assertEquals(where, e.str("album"), a.album)
            assertEquals(where, e.str("albumId"), a.albumId)
            assertEquals(where, e.str("year"), a.year)
        }
    }

    @Test fun noResultsIsAnEmptyList() {
        checkSearch("real/2026_11_search_songs_no_results_oasis.json")
        checkSearch("real/2026_11_search_songs_no_results_subcarpati.json")
    }

    @Test fun aVideosShelfIsNeverReadAsSongs() = checkSearch("real/2026_11_search_videos_subcarpati.json")

    @Test fun songResultsReadAsYtmusicapiReadsThem() = checkSearch("derived/search_songs_atv.json")

    @Test fun realRadioPagesReadAsYtmusicapiReadsThem() {
        checkWatch("real/2026_11_watch_radio_ZrOKjDZOtkA.json", "real/2026_11_watch_radio_ZrOKjDZOtkA.page1.json")
        checkWatch("real/2026_11_watch_radio_DF_CKTwIg3I.json")
    }

    @Test fun songRadioReadsAsYtmusicapiReadsIt() = checkWatch("derived/watch_radio_atv.json")

    @Test fun theRealAnswersFromThisNetworkHoldNoSongAtAll() {
        // Recorded 2026-11-11 without an account from a data-centre network: the radio
        // is uploads and an episode, even for an official "Topic" track. All rejected.
        val radio = YtmParsers.watchTracks(YtmParsers.watchPanel(fixture("real/2026_11_watch_radio_DF_CKTwIg3I.json"))!!)
        assertTrue(radio.isNotEmpty())
        assertTrue(radio.none { it.kind == CatalogKind.SONG })
        val oasis = YtmParsers.watchTracks(YtmParsers.watchPanel(fixture("real/2026_11_watch_radio_ZrOKjDZOtkA.json"))!!)
        assertEquals(CatalogKind.PODCAST_EPISODE, oasis.first { it.videoId == "0wPhbmeNSOs" }.kind)
    }

    @Test fun ytmusicapiRulesForRuns() {
        // parse_song_run: "2Pac" is an artist, "1.4M views" a count, "2020" a year, "3:07" a duration.
        assertEquals(null, YtmParsers.parseViews("2Pac"))
        assertEquals("1.4M", YtmParsers.parseViews("1.4M views"))
        assertEquals(187L, YtmParsers.parseDuration("3:07"))
        assertEquals(3723L, YtmParsers.parseDuration("1:02:03"))
        assertEquals(null, YtmParsers.parseDuration("2,343"))
        assertEquals(null, YtmParsers.parseDuration(" "))
    }
}
