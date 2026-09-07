package com.harmony.feature.downloads

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProcessAwaiterTest {
    private class Encoder : Process() {
        var running = true
        var stopped = false
        override fun isAlive() = running
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { stopped = true; running = false }
    }
    @Test fun completionLeavesTheFinishedEncoderAlone() = runTest {
        val encoder = Encoder()
        launch { delay(250); encoder.running = false }
        assertTrue(awaitDownloadProcess(encoder, 2_000))
        assertFalse(encoder.stopped)
    }
    @Test fun timeoutStopsTheEncoder() = runTest {
        val encoder = Encoder()
        assertFalse(awaitDownloadProcess(encoder, 500))
        assertTrue(encoder.stopped)
    }
    @Test fun cancellingDownloadStopsTheEncoder() = runTest {
        val encoder = Encoder()
        val job = launch { awaitDownloadProcess(encoder, 60_000) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(encoder.stopped)
    }
}
