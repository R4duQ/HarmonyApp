package com.harmony.feature.discover

import com.harmony.feature.discover.provider.CatalogArtist
import com.harmony.feature.discover.provider.CatalogResponse
import com.harmony.feature.discover.provider.CatalogTransport
import com.harmony.feature.discover.provider.LiveSongCatalog
import com.harmony.feature.discover.provider.RetryPolicy
import com.harmony.feature.discover.provider.SourceException
import com.harmony.feature.discover.provider.SourceProblem
import com.harmony.feature.discover.provider.SourceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class CatalogResilienceTest {
    private val calls = mutableListOf<String>()
    private val waits = mutableListOf<Long>()
    private var now = 10_000_000L

    private fun catalog(handler: (String) -> CatalogResponse) = LiveSongCatalog(
        CatalogTransport { url -> calls += url; handler(url) }, { now }, RetryPolicy(), { waits += it; now += it },
    )

    private fun ok(body: String) = CatalogResponse(200, body)
    private val muse = CatalogArtist(2, "Muse")
    private fun track(id: Int, artistId: Int, artist: String = "Muse") =
        """{"id":$id,"title":"Song $id","duration":200,"artist":{"id":$artistId,"name":"$artist"},"album":{"title":"A","cover_big":"https://e-cdns-images.dzcdn.net/c.jpg"}}"""

    private fun expect(problem: SourceProblem, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("expected $problem") } catch (e: SourceException) { assertEquals(problem, e.problem) }
    }

    @Test fun serverErrorsAreRetriedWithGrowingBackoffThenSucceed() = runBlocking {
        var n = 0
        val c = catalog { if (n++ < 2) CatalogResponse(503, "") else ok("""{"data":[${track(1, 2)}]}""") }
        assertEquals(1, c.artistTop(muse, 5).size)
        assertEquals(3, calls.size)
        assertTrue("backoff grows: $waits", waits[1] > waits[0])
        assertEquals(SourceState.READY, c.status.value.getValue("Deezer").state)
    }

    @Test fun deezerQuotaIsRateLimitedAndTheSourcePausesInsteadOfHammering() {
        val c = catalog { ok("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""") }
        expect(SourceProblem.RATE_LIMITED) { c.artistTop(muse, 5) }
        assertEquals(3, calls.size)
        assertEquals(SourceState.LIMITED, c.status.value.getValue("Deezer").state)
        // Circuit open: the next request fails fast without touching the network.
        expect(SourceProblem.RATE_LIMITED) { c.related(muse, 5) }
        assertEquals(3, calls.size)
        // After the pause the source is tried again.
        now += 61_000
        expect(SourceProblem.RATE_LIMITED) { c.related(muse, 5) }
        assertTrue(calls.size > 3)
    }

    @Test fun noConnectionIsReportedAsOfflineWithoutRetrying() {
        val c = catalog { throw UnknownHostException("api.deezer.com") }
        expect(SourceProblem.OFFLINE) { c.artistTop(muse, 5) }
        assertEquals(1, calls.size)
    }

    @Test fun expiredAuthenticationIsItsOwnProblem() {
        val c = catalog { CatalogResponse(401, "") }
        expect(SourceProblem.AUTH_EXPIRED) { c.artistTop(muse, 5) }
        assertEquals(1, calls.size)
    }

    @Test fun appleAnswers403WhenRateLimited() {
        val c = catalog { CatalogResponse(403, "") }
        expect(SourceProblem.RATE_LIMITED) { c.appleArtistSongs("Muse", 5) }
    }

    @Test fun aLongRetryAfterIsReportedNotWaitedOut() {
        val c = catalog { CatalogResponse(429, "", retryAfter = "120") }
        expect(SourceProblem.RATE_LIMITED) { c.artistTop(muse, 5) }
        assertEquals(1, calls.size)
        assertTrue(waits.none { it > 8_000 })
    }

    @Test fun timeoutsAreRetriedAFewTimesThenReported() {
        val c = catalog { throw SocketTimeoutException("read timed out") }
        expect(SourceProblem.TIMEOUT) { c.artistTop(muse, 5) }
        assertEquals(3, calls.size)
    }

    @Test fun artistLookupPrefersTheExactNameMostListenersFollowAndIsCached() = runBlocking {
        val c = catalog {
            ok("""{"data":[{"id":1,"name":"Muse Tribute Band","nb_fan":10},{"id":2,"name":"Muse","nb_fan":900},{"id":3,"name":"MUSE","nb_fan":5}]}""")
        }
        assertEquals(2L, c.findArtist("Muse")!!.id)
        assertEquals(2L, c.findArtist("muse")!!.id)
        assertEquals(1, calls.size)
    }

    @Test fun topTracksKeepOnlyTheArtistsOwnRecordings() = runBlocking {
        val c = catalog { ok("""{"data":[${track(1, 2)},${track(2, 9, "Someone Else")}]}""") }
        assertEquals(listOf("dz:1"), c.artistTop(muse, 5).map { it.key })
    }

    @Test fun deezerNoDataIsAnEmptyResultNotAnError() = runBlocking {
        val c = catalog { ok("""{"error":{"type":"DataException","message":"no data","code":800}}""") }
        assertTrue(c.related(muse, 5).isEmpty())
    }

    @Test fun qobuzIsListedAsNotUsedWithTheReason() {
        val qobuz = catalog { ok("{}") }.status.value.getValue("Qobuz")
        assertEquals(SourceState.NOT_USED, qobuz.state)
        assertTrue(qobuz.note.contains("app ID"))
    }
}
