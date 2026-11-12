package com.harmony.core.common.crash

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class CrashReportsTest {
    @Test
    fun theLastCrashIsKeptReadAndCleared() {
        val dir = Files.createTempDirectory("crash").toFile()
        try {
            assertNull(CrashReports.read(dir))
            CrashReports.write(dir, "1.0 (160)", "main", IllegalStateException("boom"), Instant.EPOCH)
            val text = CrashReports.read(dir)!!
            assertTrue(text.startsWith("Harmony 1.0 (160)\ntime 1970-01-01T00:00:00Z\nthread main\njava.lang.IllegalStateException: boom"))
            assertTrue("with the stack", "at com.harmony.core.common.crash.CrashReportsTest" in text)
            CrashReports.clear(dir)
            assertNull(CrashReports.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
