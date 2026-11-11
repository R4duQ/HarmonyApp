package com.harmony.domain.online

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real anonymous requests to music.youtube.com. Not part of CI (no network
 * there, and the catalog differs by network and country): run by hand with
 * `HARMONY_LIVE_YTM=1 ./gradlew :domain:online:test --tests '*YtMusicLiveTest*'`.
 * It prints what came back (ids, kinds, counts and timings) and asserts
 * only what holds anywhere: the requests are answered and parsed.
 */
class YtMusicLiveTest {
    private val queries = listOf("Oasis Wonderwall", "Subcarpați Pe Drum", "Phoenix Andrii Popa", "Massive Attack Teardrop")

    @Test
    fun anonymousSearchAndRadio() = runBlocking {
        assumeTrue("set HARMONY_LIVE_YTM=1 to run", System.getenv("HARMONY_LIVE_YTM") == "1")
        val client = YtMusicClient(UrlConnectionTransport(), location = System.getenv("HARMONY_LIVE_YTM_GL"))
        for (q in queries) {
            val t = System.nanoTime()
            val r = client.searchSongs(q, 10)
            val ms = (System.nanoTime() - t) / 1_000_000
            when (r) {
                is CatalogResult.Ok -> println("LIVE search q=\"$q\" songs=${r.value.size} kinds=${r.value.groupingBy { it.kind }.eachCount()} ms=$ms")
                is CatalogResult.Failed -> println("LIVE search q=\"$q\" failed=${r.failure} ms=$ms")
            }
            check(r !is CatalogResult.Failed || r.failure !is CatalogFailure.SchemaChanged) { "schema changed: $r" }
        }
        for (id in listOf("ZrOKjDZOtkA", "DF_CKTwIg3I")) {
            val t = System.nanoTime()
            val r = client.radio(id, 25)
            val ms = (System.nanoTime() - t) / 1_000_000
            when (r) {
                is CatalogResult.Ok -> println("LIVE radio id=$id tracks=${r.value.size} kinds=${r.value.groupingBy { it.kind }.eachCount()} ms=$ms")
                is CatalogResult.Failed -> println("LIVE radio id=$id failed=${r.failure} ms=$ms")
            }
            check(r is CatalogResult.Ok) { "radio failed: $r" }
        }
        println("LIVE requests ${client.requests}")
    }
}
