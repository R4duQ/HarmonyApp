package com.harmony.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class PartialHashTest {
    /** The scanner's recipe as it was written before it was shared: every songs.fileHash in a library was made by it. */
    private fun scannerBefore(bytes: ByteArray, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = bytes.inputStream()
        val buffer = ByteArray(64 * 1024)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        digest.update(buffer, 0, read)
        digest.update(size.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun theSameHashAsTheLibraryAlreadyHas() {
        for (size in listOf(0, 1, 1000, 65_535, 65_536, 65_537, 3_000_000)) {
            val bytes = Random(size).nextBytes(size)
            assertEquals("size $size", scannerBefore(bytes, size.toLong()), PartialHash.of(bytes.inputStream(), size.toLong()))
        }
        assertEquals(scannerBefore(ByteArray(0), 42), PartialHash.of(null, 42))
    }

    @Test fun theSizeTellsFilesWithTheSameStartApart() {
        val head = Random(1).nextBytes(70_000)
        assertNotEquals(PartialHash.of(head.inputStream(), 70_000), PartialHash.of(head.inputStream(), 80_000))
    }
}
