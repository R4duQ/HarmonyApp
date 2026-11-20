package com.harmony.feature.downloads

import com.harmony.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeptFileMatchTest {
    private fun song(id: Long, uri: String) = Song(
        id, uri, "Title", "Artist", "Album", 1, null, null, null, null, null, null, 200_000,
        null, null, null, null, null, null, null, null,
    )

    @Test
    fun `a MediaStore save is found although the library names the volume`() {
        // What MediaStore.insert(EXTERNAL_CONTENT_URI) returns, and what the scanner stores.
        val saved = "content://media/external/audio/media/42"
        val library = listOf(song(7, "content://media/external_primary/audio/media/41"), song(8, "content://media/external_primary/audio/media/42"))
        assertEquals(8L, KeptFileMatch.librarySong(library, saved, KeptFileMatch.mediaRowId(saved))?.id)
    }

    @Test
    fun `row ids are read only from MediaStore audio addresses`() {
        assertEquals(42L, KeptFileMatch.mediaRowId("content://media/external_primary/audio/media/42"))
        assertEquals(5L, KeptFileMatch.mediaRowId("content://media/7f3a-11c2/audio/media/5?x=1"))
        assertNull(KeptFileMatch.mediaRowId("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/42"))
        assertNull(KeptFileMatch.mediaRowId("file:///cache/online-mix/abcdefghijk.flac"))
    }

    @Test
    fun `a chosen folder's document is found by the row Android gives for it`() {
        val document = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FSong.flac"
        val library = listOf(song(9, "content://media/external_primary/audio/media/77"))
        assertNull(KeptFileMatch.librarySong(library, document, null))
        assertEquals(9L, KeptFileMatch.librarySong(library, document, 77)?.id)
    }

    @Test
    fun `online songs, which have no library row, never match`() {
        val library = listOf(song(-3, "content://media/external_primary/audio/media/42"))
        assertNull(KeptFileMatch.librarySong(library, "content://media/external/audio/media/42", 42))
    }
}
