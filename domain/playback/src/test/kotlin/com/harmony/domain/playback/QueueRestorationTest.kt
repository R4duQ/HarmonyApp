package com.harmony.domain.playback

import com.harmony.core.model.Song
import org.junit.Assert.*
import org.junit.Test

class QueueRestorationTest {
    private fun song(id: Long) = Song(id, "content://test/$id", "Title", "Artist", "Album", 1,
        null, null, null, null, 1, 1, 180_000, null, null, null, null, null, null, null, null)

    @Test fun deletedEarlierTrackDoesNotChangeCurrentSongOrPosition() {
        val restored = requireNotNull(QueueRestoration.restore(listOf(1, 2, 3), 1, 45_000, listOf(song(2), song(3))))
        assertEquals(2L, restored.songs[restored.index].id)
        assertEquals(0, restored.index)
        assertEquals(45_000L, restored.positionMs)
    }
    @Test fun deletedCurrentTrackStartsNextAvailableTrackAtZero() {
        val restored = requireNotNull(QueueRestoration.restore(listOf(1, 2, 3), 1, 45_000, listOf(song(1), song(3))))
        assertEquals(3L, restored.songs[restored.index].id)
        assertEquals(0L, restored.positionMs)
    }
    @Test fun deletedFinalTrackFallsBackToPreviousAtZero() {
        val restored = requireNotNull(QueueRestoration.restore(listOf(1, 2, 3), 2, 45_000, listOf(song(1))))
        assertEquals(1L, restored.songs[restored.index].id)
        assertEquals(0L, restored.positionMs)
    }
    @Test fun duplicateOccurrenceAndOriginalQueueOrderArePreserved() {
        val restored = requireNotNull(QueueRestoration.restore(listOf(1, 2, 1), 2, 20_000, listOf(song(2), song(1))))
        assertEquals(listOf(1L, 2L, 1L), restored.songs.map { it.id })
        assertEquals(2, restored.index)
    }
    @Test fun emptyAndUnavailableQueuesAreNotRestored() {
        assertNull(QueueRestoration.restore(emptyList(), 0, 0, listOf(song(1))))
        assertNull(QueueRestoration.restore(listOf(1), 0, 0, emptyList()))
    }
}
