package com.harmony.feature.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDeadlineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `returns the lookup result when it finishes in time`() = runBlocking {
        assertEquals("track", awaitWithin(1_000, scope) { "track" })
    }

    @Test
    fun `stops waiting at the deadline even when the lookup blocks`() = runBlocking {
        val started = System.nanoTime()
        // Thread.sleep ignores cancellation, like a stuck DNS lookup or socket read.
        val result = awaitWithin(100, scope) { Thread.sleep(3_000); "late" }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertNull(result)
        assertTrue("waited $waitedMs ms", waitedMs < 1_500)
    }

    @Test
    fun `a failing lookup gives null instead of an error`() = runBlocking {
        assertNull(awaitWithin<String>(1_000, scope) { error("Deezer unreachable") })
    }

    @Test
    fun `a lookup with no result gives null`() = runBlocking {
        assertNull(awaitWithin<String>(1_000, scope) { null })
    }
}
