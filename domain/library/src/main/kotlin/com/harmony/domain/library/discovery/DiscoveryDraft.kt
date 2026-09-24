package com.harmony.domain.library.discovery

import com.harmony.core.model.Song
import com.harmony.domain.library.repository.DiscoveryBatch
import com.harmony.domain.library.repository.DiscoverySong

enum class DraftStep { PREFERENCES, SONGS, REVIEW }

/**
 * The selection being built. Persisted after every change, so leaving the
 * screen, losing the connection or killing the app never loses picks.
 */
data class DiscoveryDraft(
    val id: String,
    val step: DraftStep = DraftStep.PREFERENCES,
    val level: ExplorationLevel = ExplorationLevel.BALANCED,
    val size: Int = RecommendationMixer.DEFAULT_SIZE,
    val pickedArtists: List<String> = emptyList(),
    val pickedGenres: List<String> = emptyList(),
    val items: List<DraftItem> = emptyList(),
    /** Identities shown in this draft; a regenerate prefers songs not shown yet. */
    val shown: Set<String> = emptySet(),
    val name: String = "",
    val generatedAt: Long = 0,
    /** False when the songs came from the library only (offline). */
    val generatedOnline: Boolean = false,
    /** Where the metadata came from, e.g. "Deezer", "Apple Music charts", "Your library". */
    val sources: List<String> = emptyList(),
    /** Set once "Create playlist" has turned this draft into a saved batch. */
    val batchId: String? = null,
) {
    val keptCount: Int get() = items.count { it.kept }
}

/** Library lookups built once per library change, not once per song. */
class LibraryIndex(val songs: List<Song>) {
    private val byUri = songs.associateBy { it.uri }
    private val byIdentity = songs.filter { it.durationMs > 0 }
        .groupBy { TrackIdentity.of(it.title, it.artist, it.durationMs).key }

    fun byUri(uri: String?): Song? = uri?.let(byUri::get)

    /** Same recording by URI, or by identity with a known duration (local files carry no ISRC here). */
    fun match(song: DiscoverySong): Song? {
        byUri(song.localUri)?.let { return it }
        val probe = song.track.copy(isrc = null)
        return byIdentity[probe.key]?.firstOrNull { TrackIdentity.same(probe, TrackIdentity.of(it.title, it.artist, it.durationMs)) }
    }
}

enum class SongAvailability { IN_LIBRARY, DOWNLOADING, FAILED, NEEDS_DOWNLOAD }

data class BatchProgress(
    /** Batch song key → library song id, for every song that can be played now. */
    val available: Map<String, Long>,
    val downloading: String?,
    val failed: Set<String>,
    val missing: List<String>,
) {
    val availableCount: Int get() = available.size
    fun status(key: String): SongAvailability = when {
        key in available -> SongAvailability.IN_LIBRARY
        key == downloading -> SongAvailability.DOWNLOADING
        key in failed -> SongAvailability.FAILED
        else -> SongAvailability.NEEDS_DOWNLOAD
    }
}

/**
 * Rules for turning a saved selection into a playlist, possibly in parts.
 * A song is available only when the library has indexed it: a catalog match
 * is not a file, and a file is not playable until it is scanned.
 */
object PlaylistPlacement {

    fun progress(batch: DiscoveryBatch, index: LibraryIndex): BatchProgress {
        val available = LinkedHashMap<String, Long>()
        val usedIds = HashSet<Long>()
        for (song in batch.songs) {
            val local = index.byUri(batch.uris[song.key]) ?: index.match(song) ?: continue
            if (usedIds.add(local.id)) available[song.key] = local.id
        }
        val missing = batch.songs.map { it.key }.filterNot { it in available }
        return BatchProgress(available, batch.activeKey?.takeIf { it in missing },
            batch.errors.keys.filter { it in missing }.toSet(), missing)
    }

    /** Song ids for a new playlist, in the order of the selection. */
    fun toCreate(batch: DiscoveryBatch, progress: BatchProgress): List<Long> =
        batch.songs.mapNotNull { progress.available[it.key] }

    /**
     * Songs that became available after the playlist was created and were
     * never placed in it. Appended at the end: the user may have reordered
     * or removed songs since, and both choices are kept.
     */
    fun toAppend(batch: DiscoveryBatch, progress: BatchProgress): List<Pair<String, Long>> =
        if (batch.playlistId == null || batch.playlistDeleted) emptyList()
        else batch.songs.filter { it.key !in batch.placedKeys }.mapNotNull { s -> progress.available[s.key]?.let { s.key to it } }

    /** Total length when every song's duration is known; otherwise the known part and how many are known. */
    fun duration(songs: List<DiscoverySong>): Pair<Long, Int> =
        songs.filter { it.durationMs > 0 }.let { known -> known.sumOf { it.durationMs } to known.size }
}
