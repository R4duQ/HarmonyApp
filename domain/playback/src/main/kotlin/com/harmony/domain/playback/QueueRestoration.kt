package com.harmony.domain.playback

import com.harmony.core.model.Song

data class RestoredQueue(val songs: List<Song>, val index: Int, val positionMs: Long)

/** Retains the actual selected occurrence when unavailable files are removed from a saved queue. */
object QueueRestoration {
    fun restore(ids: List<Long>, selectedIndex: Int, positionMs: Long, available: List<Song>): RestoredQueue? {
        if (ids.isEmpty()) return null
        val byId = available.associateBy { it.id }
        val retained = ids.mapIndexedNotNull { index, id -> byId[id]?.let { index to it } }
        if (retained.isEmpty()) return null
        val originalIndex = selectedIndex.coerceIn(ids.indices)
        val selected = retained.indexOfFirst { it.first == originalIndex }
        val index = if (selected >= 0) selected else retained.indexOfFirst { it.first > originalIndex }
            .takeIf { it >= 0 } ?: retained.lastIndex
        return RestoredQueue(retained.map { it.second }, index, if (selected >= 0) positionMs.coerceAtLeast(0) else 0)
    }
}
