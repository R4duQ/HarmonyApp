package com.harmony.feature.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** An online mix preparing in the background gives the engine to the user's own downloads. */
class DownloadEngineGateTest {
    @Test
    fun aDownloadTheUserStartsTakesTheEngineFromTheBackground() = runBlocking {
        withTimeout(10_000) {
            val inside = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val background = async {
                runCatching {
                    DownloadEngineGate.runInBackground {
                        inside.complete(Unit)
                        try { awaitCancellation() } finally {
                            // The engine tidies its staging folder before letting go.
                            withContext(NonCancellable) { events += "background tidied" }
                        }
                    }
                }
            }
            inside.await()
            val mine = DownloadEngineGate.run { events += "user download"; "done" }
            assertEquals("done", mine)
            assertEquals("the background let go before the user's download ran", listOf("background tidied", "user download"), events)
            assertTrue(background.await().exceptionOrNull() is DownloadEngineBusyException)
        }
    }

    @Test
    fun theBackgroundNeverEntersWhileSomeoneHoldsTheEngine() = runBlocking {
        withTimeout(10_000) {
            val inside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val user = async { DownloadEngineGate.run { inside.complete(Unit); release.await() } }
            inside.await()
            try {
                DownloadEngineGate.runInBackground { fail("must not run") }
                fail("expected busy")
            } catch (_: DownloadEngineBusyException) {
            }
            release.complete(Unit)
            user.await()
            // Free again: the background may enter.
            assertEquals(1, DownloadEngineGate.runInBackground { 1 })
        }
    }

    @Test
    fun cancellingTheBackgroundCallerItselfIsACancellationNotBusy() = runBlocking {
        withTimeout(10_000) {
            val inside = CompletableDeferred<Unit>()
            val background = async { DownloadEngineGate.runInBackground { inside.complete(Unit); awaitCancellation() } }
            inside.await()
            background.cancel()
            val outcome = runCatching { background.await() }.exceptionOrNull()
            assertTrue("$outcome", outcome is kotlinx.coroutines.CancellationException)
            assertEquals("free again", 2, DownloadEngineGate.run { 2 })
        }
    }
}
