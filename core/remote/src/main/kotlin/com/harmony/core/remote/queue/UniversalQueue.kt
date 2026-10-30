package com.harmony.core.remote.queue

/**
 * Universal Queue: one queue shared by the phone and the computer.
 *
 * The queue is a list of ENTRIES. An entry is one place in the queue: the same
 * song queued twice is two entries, each with its own [QueueEntry.id], so
 * moving or removing one never touches the other. What an entry plays is its
 * [MediaRef]: which audio, and where its original lives. Entries point at
 * media; media is fetched and cached by its [MediaRef.contentKey], so two
 * entries of one song share one cached copy.
 *
 * Every change is a [QueueOp] addressed by entry ids, never by positions, so an
 * edit made on one device still means the same thing after the other device
 * changed the queue (see QueueCoordinator).
 *
 * The rules ([QueueState.apply]):
 *  - songs the user queued ([Origin.MANUAL]) and entries the user moved
 *    ([QueueEntry.pinned]) are protected: shuffle never moves them, and
 *    nothing but an explicit [QueueOp.Remove] removes them;
 *  - Play next goes right after the song playing, after earlier Play next
 *    songs ([QueueEntry.priority]);
 *  - Add to queue goes after what the user chose and before the automatic
 *    recommendations; recommendations ([Origin.AUTO]) go at the end;
 *  - the song playing and the history (everything before it) never change
 *    with the shuffle mode.
 */

/** Which audio an entry plays, and where the original is. */
data class MediaRef(
    /**
     * Identifies the audio bytes, for the cache: the same song queued twice,
     * or found on both devices, has the same key. Provisional keys name the
     * home device and its id ("dev:<device>:<id>") until a content hash is known.
     */
    val contentKey: String,
    /** The device holding the original file. */
    val home: String,
    /** The song's id on [home]: a library id on the phone, a path on the computer. */
    val localId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
)

/** How an entry got into the queue. */
enum class Origin {
    /** Part of the list the user started (an album, a playlist). */
    LIST,
    /** Chosen by the user: Play next, Add to queue. */
    MANUAL,
    /** Picked by Smart Shuffle or a recommendation. */
    AUTO,
}

data class QueueEntry(
    val id: String,
    val media: MediaRef,
    val origin: Origin,
    /** Moved by the user: protected from shuffling like a manual entry. */
    val pinned: Boolean = false,
    /** In the Play next run, right after the song playing. */
    val priority: Boolean = false,
) {
    /** Never moved by shuffle, never removed but by the user. */
    val isProtected: Boolean get() = origin == Origin.MANUAL || pinned
}

enum class QueueRepeat { OFF, ALL, ONE }

/**
 * The whole queue. [currentId] is the entry playing; the entries before it are
 * the history, the ones after it are upcoming. [unshuffled] is the order of
 * the free upcoming entries before shuffle was turned on, for turning it off.
 */
data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    val currentId: String? = null,
    val shuffle: Boolean = false,
    val repeat: QueueRepeat = QueueRepeat.OFF,
    val unshuffled: List<String>? = null,
    /** The device that plays the queue now. */
    val owner: String? = null,
    /** Raised each time playback moves to a device: commands from an older one are refused. */
    val epoch: Long = 0,
) {
    val currentIndex: Int get() = currentId?.let { id -> entries.indexOfFirst { it.id == id } } ?: -1
    val current: QueueEntry? get() = entries.getOrNull(currentIndex)
    val history: List<QueueEntry> get() = if (currentIndex < 0) emptyList() else entries.subList(0, currentIndex)
    val upcoming: List<QueueEntry> get() = entries.subList(currentIndex + 1, entries.size)
    operator fun get(id: String): QueueEntry? = entries.firstOrNull { it.id == id }

    /** The free upcoming entries: the ones shuffle may move. */
    val freeUpcoming: List<QueueEntry> get() = upcoming.filter { !it.isProtected }
}

/** A change to the queue, addressed by entry ids. */
sealed interface QueueOp {
    /** Play next: right after the song playing, after earlier Play next songs. */
    data class PlayNext(val entries: List<QueueEntry>) : QueueOp

    /** Add to queue: after what the user chose, before the recommendations. */
    data class Add(val entries: List<QueueEntry>) : QueueOp

    /** Recommendations, at the end. */
    data class AppendAuto(val entries: List<QueueEntry>) : QueueOp

    /**
     * A new list from [startId] (an album, a playlist). The history and the
     * song playing go; protected upcoming entries stay, right after the new
     * song, in their order (what the user queued survives starting an album).
     * With shuffle on, the rest of the list is shuffled ([order], as for [SetShuffle]).
     */
    data class StartList(val entries: List<QueueEntry>, val startId: String, val order: List<String>? = null) : QueueOp

    /**
     * [entryId] goes right after [afterId] (null: right after the song
     * playing). The entry becomes pinned. Refused when either is gone, the
     * entry is the song playing or in the history, [afterId] is in the history,
     * or is the entry itself.
     */
    data class Move(val entryId: String, val afterId: String?) : QueueOp

    /** Removes [ids] (Remove, Clear queue). The song playing is never removed here. */
    data class Remove(val ids: List<String>) : QueueOp

    /** Playback moved to [entryId] (next, previous, a tap, the end of a song). */
    data class SetCurrent(val entryId: String) : QueueOp

    /**
     * Shuffle on or off. On, [order] is the new order of the free upcoming
     * entries (the coordinator fills it in, so every device applies the same
     * order); off, they go back to their order from before.
     */
    data class SetShuffle(val on: Boolean, val order: List<String>? = null) : QueueOp

    data class SetRepeat(val mode: QueueRepeat) : QueueOp

    /** Playback moves to [device]; the epoch goes up. */
    data class SetOwner(val device: String) : QueueOp
}

/** Why an op was not applied. */
enum class Refusal { UNKNOWN_ENTRY, NOT_UPCOMING, DUPLICATE_ID, CURRENT, STALE_EPOCH, BAD_REQUEST }

sealed interface ApplyResult {
    data class Applied(val state: QueueState) : ApplyResult
    data class Refused(val why: Refusal) : ApplyResult
}

/** [op] applied to this queue, or why it can't be. Pure: the same state and op give the same result on every device. */
fun QueueState.apply(op: QueueOp): ApplyResult {
    val ok = { s: QueueState -> ApplyResult.Applied(s) }
    val no = { why: Refusal -> ApplyResult.Refused(why) }
    return when (op) {
        is QueueOp.PlayNext -> {
            fresh(op.entries)?.let(no) ?: run {
                val added = op.entries.map { it.copy(origin = Origin.MANUAL, priority = true, pinned = false) }
                ok(copy(entries = entries.inserted(priorityEnd(), added)))
            }
        }
        is QueueOp.Add -> {
            fresh(op.entries)?.let(no) ?: run {
                val added = op.entries.map { it.copy(origin = Origin.MANUAL, priority = false, pinned = false) }
                // Before the first recommendation still free to move: the user's choices come first.
                val firstAuto = (currentIndex + 1 until entries.size)
                    .firstOrNull { entries[it].origin == Origin.AUTO && !entries[it].pinned } ?: entries.size
                ok(copy(entries = entries.inserted(firstAuto, added)))
            }
        }
        is QueueOp.AppendAuto -> {
            fresh(op.entries)?.let(no) ?: run {
                val added = op.entries.map { it.copy(origin = Origin.AUTO, priority = false, pinned = false) }
                ok(copy(entries = entries + added))
            }
        }
        is QueueOp.StartList -> {
            val listIds = op.entries.map { it.id }
            if (listIds.toSet().size != listIds.size || op.entries.none { it.id == op.startId }) return no(Refusal.BAD_REQUEST)
            val kept = upcoming.filter { it.isProtected }
            if (kept.any { it.id in listIds }) return no(Refusal.DUPLICATE_ID)
            val list = op.entries.map { it.copy(origin = Origin.LIST, pinned = false, priority = false) }
            val start = list.indexOfFirst { it.id == op.startId }
            // The kept entries play right after the new song; the rest of the list follows.
            val ordered = list.subList(0, start + 1) + kept + list.subList(start + 1, list.size)
            val fresh = copy(entries = ordered, currentId = op.startId, unshuffled = null, shuffle = false)
            ok(if (shuffle) fresh.shuffled(op.order) else fresh)
        }
        is QueueOp.Move -> {
            val from = entries.indexOfFirst { it.id == op.entryId }
            if (from < 0) return no(Refusal.UNKNOWN_ENTRY)
            if (from <= currentIndex) return no(if (from == currentIndex) Refusal.CURRENT else Refusal.NOT_UPCOMING)
            val anchor = if (op.afterId == null) currentIndex else entries.indexOfFirst { it.id == op.afterId }
            if (anchor < 0 && op.afterId != null) return no(Refusal.UNKNOWN_ENTRY)
            if (anchor < currentIndex) return no(Refusal.NOT_UPCOMING)
            if (op.afterId == op.entryId) return no(Refusal.BAD_REQUEST)
            val moving = entries[from]
            val runLength = priorityEnd() - (currentIndex + 1)
            val rest = entries.toMutableList().apply { removeAt(from) }
            val at = (if (op.afterId == null) currentIndex else rest.indexOfFirst { it.id == op.afterId }) + 1
            // Landing among the Play next run's places joins it; leaving them leaves it
            // (the same rule as the phone's QueueMoves.runAfterMove).
            val inRun = runLength > 0 && at in (currentIndex + 1)..(currentIndex + runLength)
            rest.add(at, moving.copy(pinned = true, priority = inRun))
            ok(copy(entries = rest))
        }
        is QueueOp.Remove -> {
            if (op.ids.any { it == currentId }) return no(Refusal.CURRENT)
            val gone = op.ids.toSet()
            // Already gone is fine: removing is idempotent.
            ok(copy(entries = entries.filter { it.id !in gone }, unshuffled = unshuffled?.filter { it !in gone }))
        }
        is QueueOp.SetCurrent -> {
            if (entries.none { it.id == op.entryId }) return no(Refusal.UNKNOWN_ENTRY)
            // The Play next run is the block right after the song playing: going on into it shortens
            // it; jumping elsewhere (back, or a tap further down) ends it.
            val at = entries.indexOfFirst { it.id == op.entryId }
            var end = at + 1
            while (end < entries.size && entries[end].priority) end++
            val runs = entries.mapIndexed { i, e -> if (e.priority && i !in (at + 1) until end) e.copy(priority = false) else e }
            ok(copy(entries = runs, currentId = op.entryId))
        }
        is QueueOp.SetShuffle -> ok(if (op.on) shuffled(op.order) else unshuffledState())
        is QueueOp.SetRepeat -> ok(copy(repeat = op.mode))
        is QueueOp.SetOwner -> ok(copy(owner = op.device, epoch = epoch + 1))
    }
}

/**
 * The entry that plays when the current one ends by itself: the same one on repeat one, the first one again after the last on
 * repeat all, none at the end otherwise.
 */
fun QueueState.nextId(): String? {
    val i = currentIndex
    if (i < 0) return entries.firstOrNull()?.id
    if (repeat == QueueRepeat.ONE) return currentId
    return entries.getOrNull(i + 1)?.id ?: if (repeat == QueueRepeat.ALL) entries.firstOrNull()?.id else null
}

/** Null when [added] can go in; otherwise why not (an id already in the queue, or twice in [added]). */
private fun QueueState.fresh(added: List<QueueEntry>): Refusal? {
    if (added.isEmpty()) return Refusal.BAD_REQUEST
    val ids = added.map { it.id }
    if (ids.toSet().size != ids.size) return Refusal.DUPLICATE_ID
    val existing = entries.mapTo(HashSet()) { it.id }
    return if (ids.any { it in existing }) Refusal.DUPLICATE_ID else null
}

/** Where the next Play next song goes: after the song playing and the run already there. */
private fun QueueState.priorityEnd(): Int {
    var i = currentIndex + 1
    while (i < entries.size && entries[i].priority) i++
    return i
}

private fun <T> List<T>.inserted(at: Int, items: List<T>): List<T> =
    toMutableList().apply { addAll(at.coerceIn(0, size), items) }

/**
 * Shuffle on: the free upcoming entries take [order] (missing ones keep their
 * order after it; unknown ids are ignored), each into one of the places free
 * entries held; protected entries, the song playing and the history stay put.
 */
private fun QueueState.shuffled(order: List<String>?): QueueState {
    val free = freeUpcoming
    val freeIds = free.map { it.id }
    val wanted = (order.orEmpty().filter { it in freeIds }.distinct()).let { it + freeIds.filter { id -> id !in it } }
    val before = if (shuffle) unshuffled else freeIds
    return copy(entries = refill(wanted), shuffle = true, unshuffled = before)
}

/** Shuffle off: the free upcoming entries back in their order from before shuffling, in the same places. */
private fun QueueState.unshuffledState(): QueueState {
    if (!shuffle) return this
    val known = unshuffled.orEmpty()
    val freeIds = freeUpcoming.map { it.id }
    val rank = known.withIndex().associate { (i, id) -> id to i }
    // Known ones in their old order; ones that arrived while shuffling keep theirs, after them.
    val restored = freeIds.sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { freeIds.indexOf(it) }))
    return copy(entries = refill(restored), shuffle = false, unshuffled = null)
}

/** The queue with its free upcoming slots filled by [order], in order. */
private fun QueueState.refill(order: List<String>): List<QueueEntry> {
    val byId = entries.associateBy { it.id }
    val next = order.iterator()
    val first = currentIndex + 1
    return entries.mapIndexed { i, e -> if (i >= first && !e.isProtected) byId.getValue(next.next()) else e }
}
