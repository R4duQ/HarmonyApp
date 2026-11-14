package com.harmony.data.shuffle

import com.harmony.domain.library.discovery.TrackIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What YouTube Music's radios put together, remembered on the phone for the
 * offline mix. The acoustic vectors say what sounds alike; a radio says what
 * people play together (the same scene, era, fans), which sound alone can't
 * tell. Each radio read while an online mix runs is written here, and
 * [NativeShuffleBrain] hands it to the engine, so "Mix from this song"
 * without a network still knows that these songs and these artists go with
 * the seed, among the songs of the library.
 *
 * Only names and ids are kept: a seed song's id, the recordings ([TrackIdentity]
 * keys) and artists (normalized lead credits) of its radio, a weight and
 * a date. Nothing is asked for to fill it: it learns from the online mixes
 * that run anyway. Bounded to [MAX_EDGES], the oldest going first.
 */
@Singleton
class TasteMemory @Inject constructor(
    private val database: SmartShuffleDatabase,
) {
    private val lock = Mutex()

    /** Seed keys written lately: the same radio read again soon after says nothing new. */
    private val recent = HashMap<String, Long>()

    /** A recording of a radio, as the catalog names it. */
    data class Track(val title: String, val artist: String)

    /**
     * Writes a radio read for the seed song [seedSongId] by [seedArtist]:
     * [tracks] in its order (the first ones the closest). [sameSong] says
     * the radio is the seed's own; when it started from another song of the
     * artist only the artists are learned.
     */
    suspend fun record(seedSongId: Long, seedArtist: String, sameSong: Boolean, tracks: List<Track>, now: Long = System.currentTimeMillis()) {
        if (tracks.isEmpty()) return
        lock.withLock {
            val from = "id:$seedSongId"
            val last = recent[from]
            if (last != null && now - last < REPEAT_MS) return
            recent[from] = now
            if (recent.size > 500) recent.entries.removeAll { now - it.value >= REPEAT_MS }

            val dao = database.taste()
            val n = tracks.size
            /** Closer to the top of the radio, closer to the seed: 1 for the first, 0.4 for the last (x [SONG_STEP] for a song). */
            fun rank(i: Int) = 1f - 0.6f * i / n.coerceAtLeast(1)

            val edges = ArrayList<TasteEdgeEntity>()
            if (sameSong) {
                val known = dao.from(TasteEdgeEntity.SONG, from).associateBy { it.toKey }
                val step = LinkedHashMap<String, Float>()
                tracks.forEachIndexed { i, t ->
                    val key = TrackIdentity.of(t.title, t.artist).key
                    if (TrackIdentity.primaryArtist(t.artist).isNotEmpty() && key !in step) step[key] = SONG_STEP * rank(i)
                }
                step.forEach { (key, p) -> edges += TasteEdgeEntity(TasteEdgeEntity.SONG, from, key, grow(known[key]?.weight, p), now) }
            }
            val seedLead = TrackIdentity.primaryArtist(seedArtist)
            if (seedLead.isNotEmpty() && seedLead !in UNKNOWN) {
                val known = dao.from(TasteEdgeEntity.ARTIST, seedLead).associateBy { it.toKey }
                // Every appearance of an artist in the radio adds to its link: noisy-OR.
                val rest = LinkedHashMap<String, Float>()
                tracks.forEachIndexed { i, t ->
                    val lead = TrackIdentity.primaryArtist(t.artist)
                    if (lead.isEmpty() || lead == seedLead || lead in UNKNOWN) return@forEachIndexed
                    rest[lead] = (rest[lead] ?: 1f) * (1f - ARTIST_STEP * rank(i))
                }
                rest.forEach { (lead, notYet) ->
                    edges += TasteEdgeEntity(TasteEdgeEntity.ARTIST, seedLead, lead, grow(known[lead]?.weight, 1f - notYet), now)
                }
            }
            if (edges.isEmpty()) return
            dao.put(edges)
            val count = dao.stamp().count
            if (count > MAX_EDGES) dao.pruneOldest(count - MAX_EDGES + MAX_EDGES / 10)
        }
    }

    /** All of it, for the engine. */
    suspend fun all(): List<TasteEdgeEntity> = database.taste().all()

    suspend fun stamp(): TasteDao.Stamp = database.taste().stamp()

    companion object {
        /** One radio's say about a song: at most this, growing toward 1 as radios repeat it. */
        const val SONG_STEP = 0.8f

        /** One appearance of an artist in a radio. */
        const val ARTIST_STEP = 0.25f

        const val MAX_EDGES = 40_000

        /** The same seed's radio read again within this long is not counted twice. */
        const val REPEAT_MS = 12 * 60 * 60 * 1000L

        private val UNKNOWN = setOf("unknown", "unknown artist", "various artists", "various", "va")

        /** Noisy-OR: what was known, and what this radio says ([p]), as evidence that adds up. */
        fun grow(old: Float?, p: Float): Float = 1f - (1f - (old ?: 0f).coerceIn(0f, 1f)) * (1f - p.coerceIn(0f, 1f))
    }
}
