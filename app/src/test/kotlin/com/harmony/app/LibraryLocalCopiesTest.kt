package com.harmony.app

import android.app.Application
import android.net.Uri
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.online.CatalogKind
import com.harmony.domain.online.CatalogTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LibraryLocalCopiesTest {
    @Test
    fun firstLookupBuildsTheIndexAndTheIndexRefreshesAfterExpiry() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("local-copy", ".flac", context.cacheDir)
        try {
            file.writeText("fLaC")
            val first = Song(1, Uri.fromFile(file).toString(), "Pe Drum", "Subcarpați", "Album", 1,
                null, null, null, null, null, null, 222_000, null, null, null, null, null, null, null, null)
            val songs = MutableStateFlow(listOf(first))
            var reads = 0
            val library = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader,
                arrayOf(LibraryRepository::class.java)) { _, method, _ ->
                check(method.name == "observeSongs") { "unexpected ${method.name}" }
                reads++
                songs
            } as LibraryRepository
            val copies = LibraryLocalCopies(library, context)
            val track = CatalogTrack("abcdefghijk", first.title, listOf(first.artist), first.album, null,
                first.durationMs, CatalogKind.SONG, null, true)
            assertEquals(1L, copies.find(track))
            assertEquals(1L, copies.find(track))
            assertEquals("the first lookup builds once", 1, reads)

            songs.value = listOf(first.copy(id = 2))
            ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
            assertEquals(2L, copies.find(track))
            assertEquals(2, reads)
            file.delete()
            assertNull("an unreadable copy is never handed out", copies.find(track))
        } finally { file.delete() }
    }
}
