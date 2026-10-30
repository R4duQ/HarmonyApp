package com.harmony.core.remote.queue

import java.util.UUID

/** One place in a player's own queue, as the mirror sees it. */
data class MirrorItem(val media: MediaRef, val origin: Origin, val priority: Boolean = false)

/**
 * Mirrors a player's own queue (the phone's Media3 playlist) into the shared
 * queue, for the device that plays it (stage 3b: the phone plays, the computer
 * shows the queue).
 *
 * The player's queue is a list of songs; the shared queue needs an id per
 * entry that stays the same while the entry stays. [EntryIds] keeps those ids
 * across changes, so a song moved on the phone is one Reposition in the shared
 * queue, not a removal and a new entry. [ops] then gives the fewest ops that
 * make the shared queue match ([mirrorOps]); nothing when it already does, so
 * the shared queue's own echo of a change sends nothing back.
 *
 * Only a window is mirrored: the last [historyLimit] songs played, the song
 * playing and the next [upcomingLimit]. A queue of thousands of songs (a whole
 * library on shuffle) would otherwise be megabytes on the Wi-Fi for every
 * device that catches up.
 */
class QueueMirror(
    private val device: String,
    newId: () -> String = { UUID.randomUUID().toString() },
    private val historyLimit: Int = 50,
    private val upcomingLimit: Int = 500,
) {
    private val ids = EntryIds(newId)

    /** The ops that make [shared] match the player: [items], [currentIndex] playing. */
    @Synchronized
    fun ops(shared: QueueState, items: List<MirrorItem>, currentIndex: Int): List<QueueOp> {
        val entries = entries(items)
        val current = currentIndex.coerceIn(-1, entries.lastIndex)
        val from = if (current < 0) 0 else (current - historyLimit).coerceAtLeast(0)
        val to = if (current < 0) minOf(entries.size, upcomingLimit) else minOf(entries.size, current + 1 + upcomingLimit)
        val window = entries.subList(from, to)
        return shared.mirrorOps(window, entries.getOrNull(current)?.id, device)
    }

    private fun entries(items: List<MirrorItem>): List<QueueEntry> {
        val keyed = ids.idsFor(items.map { it.media.contentKey })
        return items.mapIndexed { i, item ->
            QueueEntry(keyed[i], item.media, item.origin, pinned = false, priority = item.priority)
        }
    }
}

/**
 * Ids for the places in a list of keys, kept across changes of the list: the
 * same place keeps its id. Each new list is matched against the last one:
 *  - songs dropped from the front and/or added at the end (the usual case)
 *    keep the ids of the ones that stay;
 *  - anything else: each key takes the id of the earliest unclaimed place with
 *    the same key (two copies of a song may then trade ids; both play the same).
 */
class EntryIds(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private var last: List<Pair<String, String>> = emptyList()

    fun idsFor(keys: List<String>): List<String> {
        val previous = last
        val ids = followOn(previous, keys) ?: bySong(previous, keys)
        last = keys.zip(ids)
        return ids
    }

    private fun followOn(previous: List<Pair<String, String>>, keys: List<String>): List<String>? {
        for (dropped in previous.indices) {
            val kept = previous.size - dropped
            if (kept > keys.size) continue
            if ((0 until kept).any { previous[dropped + it].first != keys[it] }) continue
            val gone = previous.subList(0, dropped).mapTo(HashSet()) { it.first }
            if ((kept until keys.size).any { keys[it] in gone }) continue
            return List(keys.size) { i -> if (i < kept) previous[dropped + i].second else newId() }
        }
        return null
    }

    private fun bySong(previous: List<Pair<String, String>>, keys: List<String>): List<String> {
        val free = HashMap<String, ArrayDeque<String>>()
        previous.forEach { (key, id) -> free.getOrPut(key) { ArrayDeque() }.addLast(id) }
        return keys.map { free[it]?.removeFirstOrNull() ?: newId() }
    }
}
