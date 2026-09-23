package com.harmony.domain.library.discovery

import com.harmony.domain.library.repository.DiscoverySong
import kotlin.math.roundToInt

/** How far from the listener's habits a mix may go. Shares are starting points, not guarantees. */
enum class ExplorationLevel(val label: String, val closeShare: Float) {
    FOR_MY_TASTE("For my taste", 0.8f),
    BALANCED("Balanced mix", 0.6f),
    SURPRISE_ME("Surprise me", 0.4f);

    val closePercent: Int get() = (closeShare * 100).roundToInt()
    val explorePercent: Int get() = 100 - closePercent
}

/**
 * CLOSE: an artist or a genre the listener already plays.
 * EXPLORE: outside both, reached through a stated link (Deezer's related-artist graph from a liked artist).
 */
enum class CandidateKind { CLOSE, EXPLORE }

data class Candidate(
    val song: DiscoverySong,
    val kind: CandidateKind,
    val reason: Reason,
    val score: Float,
)

data class DraftItem(
    val song: DiscoverySong,
    val kind: CandidateKind,
    val reason: Reason,
    val kept: Boolean = false,
) {
    val key: String get() = song.key
    val identity: String get() = song.identity
}

data class MixResult(val items: List<DraftItem>, val missing: Int) {
    val closeCount: Int get() = items.count { it.kind == CandidateKind.CLOSE }
    val exploreCount: Int get() = items.count { it.kind == CandidateKind.EXPLORE }
}

/**
 * Turns scored candidates into a playlist-sized selection. Pure and seeded,
 * so the same inputs give the same mix and every rule is testable:
 *  - kept songs stay where they are;
 *  - the close/explore split follows the level, and a short bucket is
 *    filled from the other instead of leaving holes;
 *  - one recording once (ISRC, then artist/title/version/duration);
 *  - at most [artistCap] songs per artist, never two in a row;
 *  - songs recommended in earlier sessions go last, used only when nothing
 *    fresher is left.
 */
object RecommendationMixer {
    const val MIN_SIZE = 10
    const val MAX_SIZE = 100
    const val DEFAULT_SIZE = 50

    fun artistCap(size: Int): Int = if (size <= 25) 2 else 3

    fun compose(
        size: Int,
        level: ExplorationLevel,
        candidates: List<Candidate>,
        current: List<DraftItem> = emptyList(),
        excluded: Set<String> = emptySet(),
        stale: Set<String> = emptySet(),
        seed: Long = 0,
    ): MixResult {
        val target = size.coerceIn(MIN_SIZE, MAX_SIZE)
        val kept = distinct(current.filter { it.kept }).take(target)
        val picker = Picker(target, kept, excluded, stale, seed)
        val closeWanted = (target * level.closeShare).roundToInt() - kept.count { it.kind == CandidateKind.CLOSE }
        val exploreWanted = target - kept.size - closeWanted.coerceAtLeast(0)
        val close = picker.take(candidates, CandidateKind.CLOSE, closeWanted.coerceAtLeast(0))
        val explore = picker.take(candidates, CandidateKind.EXPLORE, exploreWanted.coerceAtLeast(0))
        // A bucket that ran dry is topped up from the other one.
        val short = target - kept.size - close.size - explore.size
        val extra = if (short > 0) picker.take(candidates, null, short) else emptyList()
        val fresh = interleave(close + extra.filter { it.kind == CandidateKind.CLOSE },
            explore + extra.filter { it.kind == CandidateKind.EXPLORE })
        val placed = placeAroundKept(current, kept, fresh, target)
        return MixResult(spreadArtists(placed), target - placed.size)
    }

    /** One new song for the slot at [index]: same kind when possible, never a neighbour's artist. */
    fun replacement(
        items: List<DraftItem>,
        index: Int,
        candidates: List<Candidate>,
        excluded: Set<String> = emptySet(),
        stale: Set<String> = emptySet(),
        seed: Long = 0,
    ): DraftItem? {
        val slot = items.getOrNull(index) ?: return null
        val others = items.filterIndexed { i, _ -> i != index }
        val neighbours = listOfNotNull(items.getOrNull(index - 1), items.getOrNull(index + 1))
            .map { TrackIdentity.primaryArtist(it.song.artist) }.toSet() + TrackIdentity.primaryArtist(slot.song.artist)
        val picker = Picker(items.size, others, excluded + slot.key + slot.identity, stale, seed, neighbours)
        return picker.take(candidates, slot.kind, 1).firstOrNull() ?: picker.take(candidates, null, 1).firstOrNull()
    }

    /** Fills up to [size] after removals, without touching what is already there. */
    fun fill(
        items: List<DraftItem>,
        size: Int,
        candidates: List<Candidate>,
        level: ExplorationLevel,
        excluded: Set<String> = emptySet(),
        stale: Set<String> = emptySet(),
        seed: Long = 0,
    ): List<DraftItem> {
        val target = size.coerceIn(MIN_SIZE, MAX_SIZE)
        if (items.size >= target) return items.take(target)
        val picker = Picker(target, items, excluded, stale, seed)
        val closeWanted = ((target * level.closeShare).roundToInt() - items.count { it.kind == CandidateKind.CLOSE })
            .coerceIn(0, target - items.size)
        val close = picker.take(candidates, CandidateKind.CLOSE, closeWanted)
        val explore = picker.take(candidates, CandidateKind.EXPLORE, target - items.size - close.size)
        val rest = picker.take(candidates, null, target - items.size - close.size - explore.size)
        return spreadArtists(items + interleave(close, explore) + rest)
    }

    private fun distinct(items: List<DraftItem>): List<DraftItem> {
        val out = ArrayList<DraftItem>()
        for (item in items) if (out.none { same(it.song, item.song) }) out += item
        return out
    }

    private fun same(a: DiscoverySong, b: DiscoverySong) = a.key == b.key || TrackIdentity.same(a.track, b.track)

    private class Picker(
        size: Int,
        existing: List<DraftItem>,
        private val excluded: Set<String>,
        private val stale: Set<String>,
        private val seed: Long,
        private val avoidArtists: Set<String> = emptySet(),
    ) {
        private val cap = artistCap(size)
        private val chosen = existing.map { it.song }.toMutableList()
        private val perArtist = HashMap<String, Int>().apply {
            existing.forEach { merge(TrackIdentity.primaryArtist(it.song.artist), 1, Int::plus) }
        }

        fun take(candidates: List<Candidate>, kind: CandidateKind?, count: Int): List<DraftItem> {
            if (count <= 0) return emptyList()
            val ranked = candidates.asSequence()
                .filter { kind == null || it.kind == kind }
                .filter { it.song.key !in excluded && it.song.identity !in excluded && (it.song.isrc == null || it.song.isrc !in excluded) }
                .sortedWith(compareBy<Candidate> { it.song.identity in stale || it.song.key in stale }
                    .thenByDescending { it.score + jitter(seed, it.song.key) * 0.35f })
                .toList()
            val out = ArrayList<DraftItem>()
            for (c in ranked) {
                if (out.size == count) break
                val artist = TrackIdentity.primaryArtist(c.song.artist)
                if (artist in avoidArtists || (perArtist[artist] ?: 0) >= cap) continue
                if (chosen.any { same(it, c.song) }) continue
                chosen += c.song; perArtist.merge(artist, 1, Int::plus)
                out += DraftItem(c.song, c.kind, c.reason)
            }
            return out
        }
    }

    /** Spreads discoveries evenly through the familiar songs instead of stacking them at the end. */
    private fun interleave(close: List<DraftItem>, explore: List<DraftItem>): List<DraftItem> {
        if (close.isEmpty() || explore.isEmpty()) return close + explore
        val total = close.size + explore.size
        val out = ArrayList<DraftItem>(total)
        var c = 0; var e = 0
        for (i in 0 until total) {
            val wantExplore = (e + 1).toFloat() / explore.size <= (i + 1).toFloat() / total + 1e-6f
            if ((wantExplore && e < explore.size) || c >= close.size) out += explore[e++] else out += close[c++]
        }
        return out
    }

    /** Kept songs keep their index; new songs fill the other slots in order. */
    private fun placeAroundKept(current: List<DraftItem>, kept: List<DraftItem>, fresh: List<DraftItem>, target: Int): List<DraftItem> {
        if (kept.isEmpty()) return fresh.take(target)
        val slots = arrayOfNulls<DraftItem>(target)
        val keptKeys = kept.map { it.key }.toSet()
        current.forEachIndexed { i, item -> if (item.key in keptKeys && i < target) slots[i] = item }
        val homeless = kept.filter { k -> slots.none { it?.key == k.key } }.toMutableList()
        val queue = (homeless + fresh).iterator()
        for (i in slots.indices) if (slots[i] == null && queue.hasNext()) slots[i] = queue.next()
        return slots.filterNotNull()
    }

    /** Moves a song down when it would follow a song by the same artist. Kept songs don't move. */
    private fun spreadArtists(items: List<DraftItem>): List<DraftItem> {
        val list = items.toMutableList()
        for (i in 1 until list.size) {
            val prev = TrackIdentity.primaryArtist(list[i - 1].song.artist)
            if (TrackIdentity.primaryArtist(list[i].song.artist) != prev || list[i].kept) continue
            val swap = (i + 1 until list.size).firstOrNull { j ->
                !list[j].kept && TrackIdentity.primaryArtist(list[j].song.artist) != prev
            } ?: continue
            val tmp = list[i]; list[i] = list[swap]; list[swap] = tmp
        }
        return list
    }

    /** Stable per-seed noise in [0, 1): a new session reorders near-ties, the same one never does. */
    fun jitter(seed: Long, key: String): Float {
        var z = seed + key.hashCode().toLong() * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 40).toFloat() / (1L shl 24).toFloat())
    }
}
