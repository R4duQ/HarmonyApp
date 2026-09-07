package com.harmony.domain.similarity.index

import com.harmony.domain.similarity.model.IndexEntry
import com.harmony.domain.similarity.model.Neighbor

/**
 * Flat (brute-force) cosine-similarity index.
 *
 * Why brute force and not HNSW/FAISS: at 20k songs x 48 dims a full query is
 * ~1M multiply-adds — well under a millisecond on any arm64 core, and the
 * whole index is a few MB. An ANN structure would add a native dependency,
 * an index build step, and approximation error, to speed up something that
 * is already instant. The API is deliberately shaped so an HNSW drop-in
 * remains possible if libraries ever grow 50x (Phase 1 decision, reaffirmed
 * here).
 *
 * Layout: one contiguous FloatArray (SoA), songs at stride [dim]. Contiguity
 * matters more than cleverness — the query loop streams linearly through
 * memory, which lets the JIT vectorize and the prefetcher do its job.
 * Perceptual profiles use the same SoA layout at a fixed stride of
 * [PERCEPTUAL_DIM], since every song has one.
 *
 * Vectors are expected L2-normalized (EmbeddingBuilder guarantees it), so
 * cosine similarity = dot product.
 *
 * Thread-safety: guarded by a simple internal lock. Queries are sub-ms and
 * writes are rare (one per analyzed song), so contention is a non-issue;
 * lock-free snapshotting would be complexity without a measurable win.
 */
class FlatEmbeddingIndex(private val dim: Int) {

    private val lock = Any()

    private var capacity = 1024
    private var count = 0
    private var vectors = FloatArray(capacity * dim)
    private var songIds = LongArray(capacity)
    private var albumIds = LongArray(capacity)
    private var energies = FloatArray(capacity)
    private var bpms = FloatArray(capacity) { Float.NaN }
    private var perceptuals = FloatArray(capacity * PERCEPTUAL_DIM)
    private val positionBySongId = HashMap<Long, Int>()

    val size: Int get() = synchronized(lock) { count }

    fun contains(songId: Long): Boolean = synchronized(lock) { positionBySongId.containsKey(songId) }

    fun energyOf(songId: Long): Float? = synchronized(lock) {
        positionBySongId[songId]?.let { energies[it] }
    }

    fun bpmOf(songId: Long): Float? = synchronized(lock) {
        positionBySongId[songId]?.let { pos -> bpms[pos].takeUnless(Float::isNaN) }
    }

    fun upsert(entry: IndexEntry) {
        require(entry.vector.size == dim) { "Expected dim $dim, got ${entry.vector.size}" }
        require(entry.perceptual.size == PERCEPTUAL_DIM) {
            "Expected perceptual dim $PERCEPTUAL_DIM, got ${entry.perceptual.size}"
        }
        synchronized(lock) {
            val existing = positionBySongId[entry.songId]
            val pos = if (existing != null) existing else {
                if (count == capacity) grow()
                positionBySongId[entry.songId] = count
                count++
            }
            entry.vector.copyInto(vectors, pos * dim)
            entry.perceptual.copyInto(perceptuals, pos * PERCEPTUAL_DIM)
            songIds[pos] = entry.songId
            albumIds[pos] = entry.albumId
            energies[pos] = entry.energy
            bpms[pos] = entry.bpm ?: Float.NaN
        }
    }

    fun remove(songId: Long) {
        synchronized(lock) {
            val pos = positionBySongId.remove(songId) ?: return
            val last = count - 1
            if (pos != last) {
                // Swap-remove keeps the array dense.
                vectors.copyInto(vectors, pos * dim, last * dim, last * dim + dim)
                perceptuals.copyInto(
                    perceptuals, pos * PERCEPTUAL_DIM,
                    last * PERCEPTUAL_DIM, last * PERCEPTUAL_DIM + PERCEPTUAL_DIM,
                )
                songIds[pos] = songIds[last]
                albumIds[pos] = albumIds[last]
                energies[pos] = energies[last]
                bpms[pos] = bpms[last]
                positionBySongId[songIds[pos]] = pos
            }
            count = last
        }
    }

    fun replaceAll(entries: List<IndexEntry>) {
        synchronized(lock) {
            count = 0
            positionBySongId.clear()
            if (entries.size > capacity) {
                capacity = Integer.highestOneBit(entries.size - 1) shl 1
                vectors = FloatArray(capacity * dim)
                songIds = LongArray(capacity)
                albumIds = LongArray(capacity)
                energies = FloatArray(capacity)
                bpms = FloatArray(capacity) { Float.NaN }
                perceptuals = FloatArray(capacity * PERCEPTUAL_DIM)
            }
        }
        entries.forEach(::upsert)
    }

    /**
     * K nearest neighbors of [querySongId]'s vector.
     *
     * @param exclude song ids to skip entirely (the query song itself is
     *   always skipped; recently-played filtering plugs in here).
     * @param dedupeThreshold neighbors with similarity above this are treated
     *   as the same recording (re-encodes, deluxe-edition duplicates) and
     *   dropped. 0.995 chosen because distinct songs essentially never score
     *   that high in a 48-dim timbre space, while transcodes of the same
     *   master do.
     * @param maxPerAlbum album-diversity cap applied while collecting, so the
     *   result can't be one album's worth of near-identical tracks. Pass
     *   Int.MAX_VALUE to disable (the "unless the user requests it" path).
     */
    fun findNearest(
        querySongId: Long,
        k: Int,
        exclude: Set<Long> = emptySet(),
        dedupeThreshold: Float = 0.995f,
        maxPerAlbum: Int = 2,
    ): List<Neighbor> = synchronized(lock) {
        val pos = positionBySongId[querySongId] ?: return emptyList()
        findNearestVectorLocked(
            query = vectors.copyOfRange(pos * dim, pos * dim + dim),
            k = k,
            exclude = exclude + querySongId,
            dedupeThreshold = dedupeThreshold,
            maxPerAlbum = maxPerAlbum,
        )
    }

    /** Same query against an arbitrary vector (Journey Mode steers with synthetic targets). */
    fun findNearestVector(
        query: FloatArray,
        k: Int,
        exclude: Set<Long> = emptySet(),
        dedupeThreshold: Float = 2f, // no dedupe vs synthetic targets by default
        maxPerAlbum: Int = 2,
    ): List<Neighbor> = synchronized(lock) {
        findNearestVectorLocked(query, k, exclude, dedupeThreshold, maxPerAlbum)
    }

    private fun findNearestVectorLocked(
        query: FloatArray,
        k: Int,
        exclude: Set<Long>,
        dedupeThreshold: Float,
        maxPerAlbum: Int,
    ): List<Neighbor> {
        if (count == 0 || k <= 0) return emptyList()

        val want = (k * 4).coerceAtMost(count)
        val heapScores = FloatArray(want)
        var heapSize = 0
        val heapPos = IntArray(want)

        fun siftUp(i0: Int) {
            var i = i0
            while (i > 0) {
                val parent = (i - 1) / 2
                if (heapScores[i] < heapScores[parent]) {
                    val ts = heapScores[i]; heapScores[i] = heapScores[parent]; heapScores[parent] = ts
                    val tp = heapPos[i]; heapPos[i] = heapPos[parent]; heapPos[parent] = tp
                    i = parent
                } else break
            }
        }

        fun siftDown() {
            var i = 0
            while (true) {
                val l = 2 * i + 1; val r = 2 * i + 2
                var smallest = i
                if (l < heapSize && heapScores[l] < heapScores[smallest]) smallest = l
                if (r < heapSize && heapScores[r] < heapScores[smallest]) smallest = r
                if (smallest == i) break
                val ts = heapScores[i]; heapScores[i] = heapScores[smallest]; heapScores[smallest] = ts
                val tp = heapPos[i]; heapPos[i] = heapPos[smallest]; heapPos[smallest] = tp
                i = smallest
            }
        }

        for (i in 0 until count) {
            var dot = 0f
            val base = i * dim
            for (d in 0 until dim) dot += vectors[base + d] * query[d]
            if (heapSize < want) {
                heapScores[heapSize] = dot
                heapPos[heapSize] = i
                heapSize++
                siftUp(heapSize - 1)
            } else if (dot > heapScores[0]) {
                heapScores[0] = dot
                heapPos[0] = i
                siftDown()
            }
        }

        val order = (0 until heapSize)
            .sortedByDescending { heapScores[it] }
            .map { heapPos[it] }
        val scores = FloatArray(count)
        for (j in 0 until heapSize) scores[heapPos[j]] = heapScores[j]

        val result = ArrayList<Neighbor>(k)
        val perAlbum = HashMap<Long, Int>()
        for (i in order) {
            if (result.size >= k) break
            val id = songIds[i]
            if (id in exclude) continue
            val sim = scores[i]
            if (sim >= dedupeThreshold) continue
            val album = albumIds[i]
            val albumCount = perAlbum.getOrDefault(album, 0)
            if (albumCount >= maxPerAlbum) continue
            perAlbum[album] = albumCount + 1
            result += Neighbor(
                songId = id,
                similarity = sim,
                albumId = album,
                energy = energies[i],
                perceptual = perceptuals.copyOfRange(i * PERCEPTUAL_DIM, i * PERCEPTUAL_DIM + PERCEPTUAL_DIM),
                bpm = bpms[i].takeUnless(Float::isNaN),
            )
        }
        return result
    }

    private fun grow(): Int {
        val newCapacity = capacity * 2
        vectors = vectors.copyOf(newCapacity * dim)
        songIds = songIds.copyOf(newCapacity)
        albumIds = albumIds.copyOf(newCapacity)
        energies = energies.copyOf(newCapacity)
        bpms = bpms.copyOf(newCapacity)
        for (i in capacity until newCapacity) bpms[i] = Float.NaN
        perceptuals = perceptuals.copyOf(newCapacity * PERCEPTUAL_DIM)
        capacity = newCapacity
        return capacity
    }

    companion object {
        const val PERCEPTUAL_DIM = 11
    }
}