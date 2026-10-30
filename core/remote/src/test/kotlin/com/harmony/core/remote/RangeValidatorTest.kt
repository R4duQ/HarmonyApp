package com.harmony.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/** Resuming and checking a copy safely: validators on ranged responses (RFC 9110 If-Range, If-None-Match). */
class RangeValidatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun source(size: Int) = FileRangeSource(tmp.newFile().apply { writeBytes(ByteArray(size) { it.toByte() }) })

    private fun body(r: HttpResponse) = ByteArrayOutputStream().also { r.writeBody(it) }.toByteArray()

    @Test fun theSameVersionResumes() {
        val s = source(1_000)
        val etag = s.etag
        val r = HttpResponse.ranged(s, "bytes=600-", "audio/flac", ifRange = etag)
        assertEquals(206, r.status)
        assertEquals(400L, r.length)
        assertEquals(etag, r.headers["ETag"])
        assertEquals("from byte 600", 600.toByte(), body(r)[0])
    }

    @Test fun anotherVersionComesWhole() {
        val s = source(1_000)
        val r = HttpResponse.ranged(s, "bytes=600-", "audio/flac", ifRange = "\"something-else\"")
        assertEquals(200, r.status)
        assertEquals(1_000L, r.length)
        assertEquals(1_000, body(r).size)
    }

    @Test fun anUnchangedCopyIsConfirmedWithoutTheBytes() {
        val s = source(1_000)
        val r = HttpResponse.ranged(s, null, "audio/flac", ifNoneMatch = s.etag)
        assertEquals(304, r.status)
        assertEquals(0L, r.length)
        assertNotNull(r.headers["ETag"])
        assertEquals(200, HttpResponse.ranged(s, null, "audio/flac", ifNoneMatch = "\"old\"").status)
    }

    @Test fun withoutValidatorsNothingChanges() {
        val s = source(1_000)
        assertEquals(206, HttpResponse.ranged(s, "bytes=0-9", "audio/flac").status)
        assertEquals(416, HttpResponse.ranged(s, "bytes=5000-", "audio/flac").status)
    }
}
