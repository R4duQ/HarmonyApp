package com.harmony.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSongsTest {
    @Test fun aComputersSongIsNeverALibrarySong() {
        repeat(10_000) { i ->
            val id = RemoteSongs.idFor("pc:pc-1:song$i")
            assertTrue(id < 0)
            assertTrue(RemoteSongs.isRemote(id))
        }
        assertFalse(RemoteSongs.isRemote(42L))
        assertEquals(RemoteSongs.idFor("pc:a:b"), RemoteSongs.idFor("pc:a:b"))
        assertNotEquals(RemoteSongs.idFor("pc:a:b"), RemoteSongs.idFor("pc:a:c"))
    }

    @Test fun theAddressCarriesTheKeyAndBack() {
        val key = "pc:3f1c-9a:0a1b2c3d4e5f"
        val song = RemoteSongs.song(key, "Title", "Artist", "Album", 200_000)
        assertTrue(RemoteSongs.isRemote(song))
        assertEquals(key, RemoteSongs.keyOf(song.uri))
        assertEquals("3f1c-9a", RemoteSongs.homeOf(key))
        assertEquals("0a1b2c3d4e5f", RemoteSongs.localIdOf(key))
        assertTrue(RemoteSongs.isRemoteKey(key))
        assertFalse(RemoteSongs.isRemoteKey("42"))
        assertNull(RemoteSongs.keyOf("content://media/external/audio/media/42"))
        assertNull(RemoteSongs.homeOf("dev:phone:42"))
    }
}
