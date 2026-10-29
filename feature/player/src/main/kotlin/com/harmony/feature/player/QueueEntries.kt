package com.harmony.feature.player

import com.harmony.core.model.Song

/** One row of the queue sheet: a song, and a key that stays with this occurrence of it. */
internal data class QueueEntry(val key: Long, val song: Song)

/**
 * Keys for the queue sheet's rows that follow each queue entry, not its
 * position or its song.
 *
 * The player's queue is a list of songs, and the same song can be in it more
 * than once, so neither the position nor the song id tells two rows apart
 * across changes. Keying "song id + its n-th occurrence" (as before) swapped
 * the keys of two copies of a song whenever one was moved past the other, so
 * Compose moved the wrong row and the drag jumped.
 *
 * Instead each new queue is matched against the rows already shown:
 *  - the song changed and/or songs were appended (the usual changes while
 *    the sheet is open): the rows still there keep their keys, in order;
 *  - anything else (a removal, a move from elsewhere): each song takes the
 *    key of the earliest unclaimed row of the same song;
 *  - songs not shown before get new keys.
 * A move made on the sheet is told to [onMoved] first, so the player's answer
 * matches the order the user just made, and every row keeps its key.
 */
internal class QueueKeys {
    private var shown: List<QueueEntry> = emptyList()
    private var nextKey = 0L

    fun entriesFor(songs: List<Song>): List<QueueEntry> {
        val previous = shown
        val entries = followOn(previous, songs) ?: matchBySong(previous, songs)
        shown = entries
        return entries
    }

    /** The sheet reordered its rows to [order]: the player's next queue is expected to match it. */
    fun onMoved(order: List<QueueEntry>) {
        shown = order
    }

    /** The rows still shown after the first few went (the song moved on), with songs appended after them. */
    private fun followOn(previous: List<QueueEntry>, songs: List<Song>): List<QueueEntry>? {
        for (dropped in 0 until previous.size) {
            val kept = previous.size - dropped
            if (kept > songs.size) continue
            val matches = (0 until kept).all { previous[dropped + it].song.id == songs[it].id }
            // A song that left the top and is back at the end is more likely a
            // reorder than a song played and queued again: matched by song.
            val goneIds = previous.subList(0, dropped).mapTo(HashSet()) { it.song.id }
            val requeued = (kept until songs.size).any { songs[it].id in goneIds }
            if (matches && !requeued) {
                return List(songs.size) { i ->
                    if (i < kept) previous[dropped + i].copy(song = songs[i]) else QueueEntry(nextKey++, songs[i])
                }
            }
        }
        return null
    }

    private fun matchBySong(previous: List<QueueEntry>, songs: List<Song>): List<QueueEntry> {
        val free = HashMap<Long, ArrayDeque<Long>>()
        previous.forEach { free.getOrPut(it.song.id) { ArrayDeque() }.addLast(it.key) }
        return songs.map { song -> QueueEntry(free[song.id]?.removeFirstOrNull() ?: nextKey++, song) }
    }
}

/** This list with the entry at [from] moved to [to], the others in their order. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    if (from == to || from !in indices || to !in indices) this
    else toMutableList().apply { add(to, removeAt(from)) }
