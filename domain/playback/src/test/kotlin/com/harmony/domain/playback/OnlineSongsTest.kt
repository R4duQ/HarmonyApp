package com.harmony.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnlineSongsTest {
    @Test
    fun keysIdsAndFilesRoundTrip() {
        val file = OnlineSongs.fileFor(File("/data/user/0/app/cache"), "ZrOKjDZOtkA")
        assertEquals("/data/user/0/app/cache/online-mix/ZrOKjDZOtkA.flac", file.path)
        val uri = "file://" + file.path
        assertEquals("ytm:ZrOKjDZOtkA", OnlineSongs.keyOf(uri))
        val song = OnlineSongs.song("ZrOKjDZOtkA", uri, "Wonderwall", "Oasis", "", 259_000, 44_100, 16)
        assertTrue(song.id < 0)
        assertEquals(OnlineSongs.idFor("ytm:ZrOKjDZOtkA"), song.id)
        assertTrue(OnlineSongs.isOnline(song))
    }

    @Test
    fun neverMistakenForALibraryOrAComputersSong() {
        val online = OnlineSongs.idFor("ytm:ZrOKjDZOtkA")
        assertTrue(online != RemoteSongs.idFor("pc:desk:ZrOKjDZOtkA"))
        assertFalse(RemoteSongs.isRemoteKey("ytm:ZrOKjDZOtkA"))
        assertNull(RemoteSongs.keyOf("file:///cache/online-mix/ZrOKjDZOtkA.flac"))
        assertNull(OnlineSongs.keyOf("content://media/external/audio/media/12"))
        assertNull(OnlineSongs.keyOf(RemoteSongs.uriFor("pc:desk:7")))
        // Only a file of the online cache, with a real video id.
        assertNull(OnlineSongs.keyOf("file:///sdcard/Music/online-mix/../x.flac"))
        assertNull(OnlineSongs.videoIdOf("ytm:../../etc"))
    }
}
