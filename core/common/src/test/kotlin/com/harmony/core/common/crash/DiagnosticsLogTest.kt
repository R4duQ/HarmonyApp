package com.harmony.core.common.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticsLogTest {
    @Test
    fun linesAndErrorsAreKeptReadCappedAndCleared() {
        val dir = Files.createTempDirectory("diag").toFile()
        try {
            DiagnosticsLog.init(dir)
            DiagnosticsLog.clear()
            DiagnosticsLog.line("ONLINE_MIX", "seed=7 identify=ok ms=320")
            DiagnosticsLog.error("ONLINE_MIX", "session stopped", IllegalStateException("boom"))
            val text = DiagnosticsLog.read()
            assertTrue(text.contains("ONLINE_MIX seed=7 identify=ok ms=320"))
            assertTrue(text.contains("ONLINE_MIX session stopped\njava.lang.IllegalStateException: boom"))
            assertTrue("with the stack", text.contains("at com.harmony.core.common.crash.DiagnosticsLogTest"))

            repeat(4_000) { DiagnosticsLog.line("T", "x".repeat(100)) }
            assertTrue(File(dir, DiagnosticsLog.FILE).length() <= DiagnosticsLog.MAX_BYTES + 200)
            assertTrue(DiagnosticsLog.read().startsWith("… older entries removed …"))

            DiagnosticsLog.clear()
            assertEquals("", DiagnosticsLog.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
