package com.harmony.feature.downloads

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpotiFlacProviderInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val provider = ProviderPackage(
        id = "fixture",
        displayName = "Fixture",
        version = "1.0.0",
        url = "https://example.invalid/fixture.sflx",
        // Independently computed for the fixed UTF-8 fixture, not by the installer.
        sha256 = "71b57fe255093d2ec27c4686b481597aadb2f578f76a517390bf92a5a825c318",
    )
    private val officialBytes = "official provider fixture v1\n".toByteArray(Charsets.UTF_8)
    private val oldBytes = "old provider fixture\n".toByteArray(Charsets.UTF_8)

    @Test fun verifiedCacheAvoidsDownload() {
        val directory = temporaryFolder.newFolder()
        val destination = File(directory, "fixture.sflx").apply { writeBytes(officialBytes) }

        SpotiFlacProviderInstaller.install(provider, directory) { _, _ ->
            fail("A verified cached package must not be downloaded again")
        }

        assertArrayEquals(officialBytes, destination.readBytes())
        assertFalse(File(directory, "archive").exists())
    }

    @Test fun staleCacheIsReplacedOnlyAfterValidDownloadAndArchived() {
        val directory = temporaryFolder.newFolder()
        val destination = File(directory, "fixture.sflx").apply { writeBytes(oldBytes) }
        val partial = File(directory, "fixture.sflx.part").apply { writeText("stale partial") }
        var downloads = 0

        SpotiFlacProviderInstaller.install(provider, directory) { requested, output ->
            downloads++
            assertEquals(provider, requested)
            assertEquals(partial, output)
            assertFalse("Stale partial must be removed before transport", output.exists())
            assertArrayEquals("Existing cache must remain until verification", oldBytes, destination.readBytes())
            output.writeBytes(officialBytes)
        }

        assertEquals(1, downloads)
        assertArrayEquals(officialBytes, destination.readBytes())
        assertFalse(partial.exists())
        val archived = File(directory, "archive").listFiles()?.toList().orEmpty()
        assertEquals(1, archived.size)
        assertTrue(archived.single().name.endsWith(".sflx.bak"))
        assertArrayEquals(oldBytes, archived.single().readBytes())
    }

    @Test fun corruptedResponseIsRejectedAndOriginalIsPreserved() {
        val directory = temporaryFolder.newFolder()
        val destination = File(directory, "fixture.sflx").apply { writeBytes(oldBytes) }

        try {
            SpotiFlacProviderInstaller.install(provider, directory) { _, output ->
                output.writeText("corrupt provider fixture\n", Charsets.UTF_8)
            }
            fail("Corrupted provider must fail the SHA-256 check")
        } catch (failure: SpotiFlacException) {
            assertEquals("provider_integrity", failure.errorType)
            assertEquals(
                "expected=${provider.sha256}; actual=d41b94b2e80c3c6d3248df96349fa1195bdfd538a26e343c4bb3bec490e3cefe; version=1.0.0",
                failure.technicalDetails,
            )
        }

        assertArrayEquals(oldBytes, destination.readBytes())
        assertFalse(File(directory, "fixture.sflx.part").exists())
        assertFalse("Failed verification must not archive the original", File(directory, "archive").exists())
    }

    @Test fun interruptedDownloadRemovesPartialAndPreservesOriginal() {
        val directory = temporaryFolder.newFolder()
        val destination = File(directory, "fixture.sflx").apply { writeBytes(oldBytes) }
        val interrupted = IOException("connection interrupted")

        try {
            SpotiFlacProviderInstaller.install(provider, directory) { _, output ->
                output.writeBytes(officialBytes.copyOfRange(0, 8))
                throw interrupted
            }
            fail("Interrupted download must be propagated")
        } catch (failure: IOException) {
            assertSame(interrupted, failure)
        }

        assertArrayEquals(oldBytes, destination.readBytes())
        assertFalse(File(directory, "fixture.sflx.part").exists())
        assertFalse(File(directory, "archive").exists())
    }
}
