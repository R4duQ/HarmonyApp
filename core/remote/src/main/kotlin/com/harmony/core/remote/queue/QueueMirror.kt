package com.harmony.core.remote.queue

import java.util.UUID

/**
 * One place in a player's own queue, as the mirror sees it: the song, where it
 * came from, whether it is in the Play next run ([priority]), whether the user
 * moved it ([pinned]), and the player's own id for this entry ([entry]; null
 * when the player has none, and the mirror then tells entries apart by song).
 */
data class MirrorItem(
    val media: MediaRef,
    val origin: Origin,
    val priority: Boolean = false,
    val pinned: Boolean = false,
    val entry: Long? = null,
)

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
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val historyLimit: Int = 50,
    private val upcomingLimit: Int = 500,
) {
    private val ids = EntryIds(newId)

    /** Shared ids of the player's own entries, when it has them: one entry, one id, for as long as it stays. */
    private var byEntry: Map<Long, String> = emptyMap()

    /**
     * Entries another device added (Play next, Add) that the player was asked
     * to insert, by content key: the player's new entry of that song takes the
     * shared id it already has, instead of the shared one being removed and a
     * copy added. Settled by the next [ops].
     */
    private val adopting = HashMap<String, ArrayDeque<String>>()

    /** The ops that make [shared] match the player: [items], [currentIndex] playing. */
    @Synchronized
    fun ops(shared: QueueState, items: List<MirrorItem>, currentIndex: Int): List<QueueOp> {
        // An entry moved on another device stays pinned (protected) as the shared queue has it.
        val entries = entries(items).map { e -> shared[e.id]?.takeIf { it.pinned }?.let { e.copy(pinned = true) } ?: e }
        // Inserts the player hasn't shown by now aren't coming (refused, or overtaken): their entries go.
        adopting.clear()
        val current = currentIndex.coerceIn(-1, entries.lastIndex)
        val from = if (current < 0) 0 else (current - historyLimit).coerceAtLeast(0)
        val to = if (current < 0) minOf(entries.size, upcomingLimit) else minOf(entries.size, current + 1 + upcomingLimit)
        val window = entries.subList(from, to)
        return shared.mirrorOps(window, entries.getOrNull(current)?.id, device)
    }

    /**
     * What [op], made on another device, asks of the player whose queue is
     * [items] with [currentIndex] playing: moves, removals, songs to insert
     * (Play next, Add: from that device's library or this one's) and a song
     * to play, by position, each position as it is after the edits before
     * it. Empty for what the player doesn't take from others (its own list
     * stays its own: whatever the edits leave different is mirrored back as
     * the player has it).
     */
    @Synchronized
    fun edits(op: QueueOp, items: List<MirrorItem>, currentIndex: Int): List<PlayerEdit> {
        val ids = entries(items).map { it.id }
        val key = { i: Int -> items[i].media.contentKey }
        return when (op) {
            is QueueOp.Move -> {
                val from = ids.indexOf(op.entryId)
                val anchor = op.afterId?.let { ids.indexOf(it) } ?: currentIndex
                // Only upcoming songs move, and only among the upcoming ones.
                if (from <= currentIndex || anchor < currentIndex || from < 0) return emptyList()
                val to = if (anchor < from) anchor + 1 else anchor
                if (to == from) emptyList() else listOf(PlayerEdit.Move(from, to, key(from)))
            }
            is QueueOp.Remove -> op.ids.map { ids.indexOf(it) }
                .filter { it >= 0 && it != currentIndex }
                .distinct()
                .sortedDescending() // from the end, so the positions before stay true
                .map { PlayerEdit.Remove(it, key(it)) }
            is QueueOp.SetCurrent -> {
                val at = ids.indexOf(op.entryId)
                if (at < 0 || at == currentIndex) emptyList() else listOf(PlayerEdit.Play(at, key(at)))
            }
            is QueueOp.PlayNext, is QueueOp.Add -> {
                val next = op is QueueOp.PlayNext
                val added = if (op is QueueOp.PlayNext) op.entries else (op as QueueOp.Add).entries
                var now = items
                added.filter { it.id !in ids }.map { e ->
                    // Play next: after the Play next run. Add: after the user's songs, before the free picks.
                    val after = currentIndex + 1
                    val at = if (next) {
                        (after until now.size).firstOrNull { !now[it].priority } ?: now.size
                    } else {
                        (after until now.size).firstOrNull { now[it].origin == Origin.AUTO && !now[it].pinned } ?: now.size
                    }
                    adopting.getOrPut(e.media.contentKey) { ArrayDeque() }.addLast(e.id)
                    PlayerEdit.Insert(at, e.media, next).also { now = it.applyTo(now, currentIndex).first }
                }
            }
            else -> emptyList()
        }
    }

    private fun entries(items: List<MirrorItem>): List<QueueEntry> {
        val keyed = if (items.isNotEmpty() && items.all { it.entry != null }) {
            val known = byEntry
            val now = LinkedHashMap<Long, String>()
            // Entries already known keep their ids; a new one may take the id of an entry added elsewhere.
            items.forEach { item -> known[item.entry]?.let { now[item.entry!!] = it } }
            val taken = now.values.toHashSet()
            items.map { item ->
                now.getOrPut(item.entry!!) {
                    adopting[item.media.contentKey]?.firstOrNull { it !in taken }?.also { taken += it } ?: newId()
                }
            }.also { byEntry = now }
        } else {
            ids.idsFor(items.map { it.media.contentKey })
        }
        return items.mapIndexed { i, item ->
            QueueEntry(keyed[i], item.media, item.origin, pinned = item.pinned, priority = item.priority)
        }
    }
}

/** Entry numbers for [PlayerEdit.Insert]'s place-holders: below zero, never a player's own. */
private val placeholders = java.util.concurrent.atomic.AtomicLong()

/** A change to a player's own list, by position, with the song expected there ([mediaKey]). */
sealed interface PlayerEdit {
    data class Move(val from: Int, val to: Int, val mediaKey: String) : PlayerEdit
    data class Remove(val index: Int, val mediaKey: String) : PlayerEdit

    /** [media] put in at [at] ([next]: as Play next), as the user's. */
    data class Insert(val at: Int, val media: MediaRef, val next: Boolean) : PlayerEdit
    data class Play(val index: Int, val mediaKey: String) : PlayerEdit

    /** [items] with [current] playing, after this edit. */
    fun applyTo(items: List<MirrorItem>, current: Int): Pair<List<MirrorItem>, Int> = when (this) {
        is Move -> items.toMutableList().apply { add(to, removeAt(from)) } to current
        is Remove -> items.toMutableList().apply { removeAt(index) } to (if (index < current) current - 1 else current)
        is Insert -> items.toMutableList().apply {
            // A place-holder until the player shows its own entry (its id is the player's to give).
            add(at.coerceIn(0, size), MirrorItem(media, Origin.MANUAL, priority = next, entry = if (items.all { it.entry != null }) placeholders.decrementAndGet() else null))
        } to (if (at <= current) current + 1 else current)
        is Play -> items to index
    }
}

/**
 * The player whose queue is mirrored, taking edits made on other devices.
 * Each returns false when it didn't make the change (the song expected is no
 * longer there, the queue changed meanwhile): what was refused is then
 * mirrored back as the player has it.
 */
interface QueuePlayer {
    suspend fun move(from: Int, to: Int, mediaKey: String): Boolean
    suspend fun remove(index: Int, mediaKey: String): Boolean
    suspend fun play(index: Int, mediaKey: String): Boolean

    /** Puts [media] in at [at] ([next]: as Play next), as the user's song. False: it can't (not playable here). */
    suspend fun insert(at: Int, media: MediaRef, next: Boolean): Boolean = false
}

/**
 * Keeps a player and the shared queue in step, both ways (stage 3b-2): edits
 * made on other devices go to the player first, then the player's queue is
 * mirrored to the shared one. The player stays the truth: an edit it refuses
 * is undone in the shared queue by the mirror, never forced on the player.
 */
class QueueFollower(private val link: QueueLink, private val mirror: QueueMirror) {
    /**
     * One look at the player's queue, [items] with [current] playing. True when
     * edits from other devices were given to the player: look again once the
     * player shows them (mirroring now would undo them).
     */
    suspend fun step(items: List<MirrorItem>, current: Int, player: QueuePlayer): Boolean {
        val (ops, resynced) = link.replica.takeForeign()
        var now = items
        var at = current
        var applied = false
        // After a snapshot the ops alone don't say what others changed: the player's queue wins.
        if (!resynced) {
            outer@ for (op in ops) {
                for (edit in mirror.edits(op, now, at)) {
                    val done = when (edit) {
                        is PlayerEdit.Move -> player.move(edit.from, edit.to, edit.mediaKey)
                        is PlayerEdit.Remove -> player.remove(edit.index, edit.mediaKey)
                        is PlayerEdit.Play -> player.play(edit.index, edit.mediaKey)
                        is PlayerEdit.Insert -> player.insert(edit.at, edit.media, edit.next)
                    }
                    if (!done) break@outer
                    applied = true
                    edit.applyTo(now, at).let { (i, c) -> now = i; at = c }
                }
            }
        }
        if (applied) return true
        mirror.ops(link.replica.view, items, current).forEach { link.issue(it) }
        return false
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
