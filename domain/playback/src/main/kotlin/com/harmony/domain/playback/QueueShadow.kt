package com.harmony.domain.playback

import com.harmony.core.model.QueueEntryInfo
import com.harmony.core.model.QueueOrigin

/**
 * Where each entry of the player's queue came from, kept beside the player
 * (Universal Queue, stage 5). Media3's items carry only the song (its media
 * id); this list, in the same order, carries each entry's own id, its origin
 * (the list started, the user, Smart Shuffle) and whether the user moved it.
 *
 * Every queue change made through the app is told here as it is made on the
 * player ([insert], [remove], [move], [replace], [startList], [setAll]). Changes made
 * elsewhere (Android Auto, a queue restored by the system) are caught by
 * [align], which matches the player's list against this one: entries that
 * stay keep what is known of them, new ones count as part of a list.
 *
 * Pure and single-threaded (the player's thread).
 */
class QueueShadow {
    private var mediaIds: List<String> = emptyList()
    private var infos: List<QueueEntryInfo> = emptyList()
    private var nextId = 1L

    /** The entries, in the player's order. */
    val entries: List<QueueEntryInfo> get() = infos

    private fun fresh(origin: QueueOrigin) = QueueEntryInfo(nextId++, origin)

    /** A whole new queue: [items] with what is known of each (null: a new entry of the list). */
    fun setAll(items: List<Pair<String, QueueEntryInfo?>>) {
        mediaIds = items.map { it.first }
        infos = items.map { it.second ?: fresh(QueueOrigin.LIST) }
    }

    /** [ids] inserted at [at], all of [origin]. */
    fun insert(at: Int, ids: List<String>, origin: QueueOrigin) {
        val i = at.coerceIn(0, mediaIds.size)
        mediaIds = mediaIds.toMutableList().apply { addAll(i, ids) }
        infos = infos.toMutableList().apply { addAll(i, ids.map { fresh(origin) }) }
    }

    /** Entries [from] until [toExclusive] removed. */
    fun remove(from: Int, toExclusive: Int) {
        val a = from.coerceIn(0, mediaIds.size)
        val b = toExclusive.coerceIn(a, mediaIds.size)
        mediaIds = mediaIds.toMutableList().apply { subList(a, b).clear() }
        infos = infos.toMutableList().apply { subList(a, b).clear() }
    }

    /** The entry at [from] moved to [to] by the user: it is pinned (protected from shuffle) from now on. */
    fun move(from: Int, to: Int) {
        if (from !in mediaIds.indices || to !in mediaIds.indices) return
        mediaIds = mediaIds.toMutableList().apply { add(to, removeAt(from)) }
        infos = infos.toMutableList().apply { add(to, removeAt(from).copy(pinned = true)) }
    }

    /**
     * Entries [from] until [toExclusive] replaced by [ids] (a shuffle, a
     * re-rank, the order restored). An entry of the replaced range whose song
     * is among [ids] carries over (its id, origin, pin) when it is protected
     * or of [origin], so reordering never makes the user's songs forget they
     * are the user's, and a list song coming back where Smart Shuffle had
     * picked the same song is a list song again; the rest are new, of [origin].
     */
    fun replace(from: Int, toExclusive: Int, ids: List<String>, origin: QueueOrigin) {
        val a = from.coerceIn(0, mediaIds.size)
        val b = toExclusive.coerceIn(a, mediaIds.size)
        val pool = HashMap<String, MutableList<QueueEntryInfo>>()
        for (i in a until b) pool.getOrPut(mediaIds[i]) { mutableListOf() }.add(infos[i])
        val placed = ids.map { id ->
            val candidates = pool[id] ?: return@map fresh(origin)
            val at = candidates.indexOfFirst { it.isProtected }.takeIf { it >= 0 }
                ?: candidates.indexOfFirst { it.origin == origin }.takeIf { it >= 0 }
            if (at != null) candidates.removeAt(at) else fresh(origin)
        }
        mediaIds = mediaIds.subList(0, a) + ids + mediaIds.subList(b, mediaIds.size)
        infos = infos.subList(0, a) + placed + infos.subList(b, infos.size)
    }

    /**
     * A new list [ids] started at [start], replacing the queue, whose song
     * [currentIndex] was playing. The user's songs still to come (Play next,
     * Add to queue) are kept, in their order, right after the song started.
     *
     * @return the positions, in the old queue, of the entries kept: the
     *   player's new list is ids[0..start], those, then the rest of [ids].
     */
    fun startList(ids: List<String>, start: Int, currentIndex: Int): List<Int> {
        val kept = if (ids.isEmpty()) emptyList() else
            (currentIndex + 1 until mediaIds.size).filter { it >= 0 && infos[it].origin == QueueOrigin.MANUAL }
        val cut = if (ids.isEmpty()) 0 else start.coerceIn(ids.indices) + 1
        val newIds = ids.subList(0, cut) + kept.map { mediaIds[it] } + ids.subList(cut, ids.size)
        val newInfos = List(cut) { fresh(QueueOrigin.LIST) } + kept.map { infos[it] } +
            List(ids.size - cut) { fresh(QueueOrigin.LIST) }
        mediaIds = newIds
        infos = newInfos
        return kept
    }

    /**
     * The entries for the player's list as it actually is ([actual], its media
     * ids in order). The same list: as known. Otherwise (changed outside the
     * app): entries dropped from the front and/or added at the end keep theirs;
     * anything else is matched song by song, earliest first; new ones are
     * part of a list.
     */
    fun align(actual: List<String>): List<QueueEntryInfo> {
        if (actual == mediaIds) return infos
        val kept = followOn(actual) ?: bySong(actual)
        mediaIds = actual
        infos = kept
        return infos
    }

    private fun followOn(actual: List<String>): List<QueueEntryInfo>? {
        for (dropped in mediaIds.indices) {
            val keep = mediaIds.size - dropped
            if (keep > actual.size) continue
            if ((0 until keep).any { mediaIds[dropped + it] != actual[it] }) continue
            val gone = mediaIds.subList(0, dropped).toHashSet()
            if ((keep until actual.size).any { actual[it] in gone }) continue
            return List(actual.size) { i -> if (i < keep) infos[dropped + i] else fresh(QueueOrigin.LIST) }
        }
        return null
    }

    private fun bySong(actual: List<String>): List<QueueEntryInfo> {
        val pool = HashMap<String, ArrayDeque<QueueEntryInfo>>()
        mediaIds.forEachIndexed { i, id -> pool.getOrPut(id) { ArrayDeque() }.addLast(infos[i]) }
        return actual.map { id -> pool[id]?.removeFirstOrNull() ?: fresh(QueueOrigin.LIST) }
    }
}
