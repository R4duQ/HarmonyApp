package com.harmony.core.media.scanner

import com.harmony.core.common.coroutines.DispatcherProvider
import com.harmony.core.media.metadata.MetadataExtractor
import com.harmony.core.media.watcher.MediaChangeWatcher
import com.harmony.domain.library.gateway.MediaScanGateway
import com.harmony.domain.library.model.ScanEvent
import com.harmony.domain.library.model.ScanKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The concrete [MediaScanGateway]: enumerates all sources, diffs against the
 * known library, extracts metadata for new/changed files in parallel, and
 * reports deletions.
 *
 * Concurrency: enumeration is one indexed query per volume (fast, serial),
 * but extraction opens each file (MMR + tag parse + head hash), so changed
 * files are processed with bounded parallelism ([EXTRACT_PARALLELISM]).
 * The bound exists because MediaMetadataRetriever hits OEM codec services;
 * unbounded parallelism causes binder exhaustion on several devices.
 *
 * Everything runs on the IO dispatcher; callers just collect the flow.
 */
@Singleton
class LibraryScanner @Inject constructor(
    private val mediaStoreSource: MediaStoreSource,
    private val safTreeSource: SafTreeSource,
    private val extractor: MetadataExtractor,
    private val watcher: MediaChangeWatcher,
    private val dispatchers: DispatcherProvider,
) : MediaScanGateway {

    override fun scan(
        knownKeys: Collection<ScanKey>,
        force: Boolean,
    ): Flow<ScanEvent> = channelFlow {
        val knownByUri = knownKeys.associateBy { it.uri }

        // Enumerate completely before pruning. An unavailable provider must never look empty.
        val mountedBefore = mediaStoreSource.mountedAudioPrefixes()
        val trees = safTreeSource.persistedTreeUris()
        val allCandidates = mediaStoreSource.queryAll() + safTreeSource.queryAll(trees)
        val candidates = ScanIdentity.deduplicate(allCandidates, knownByUri.keys)
        val candidateUris = candidates.mapTo(HashSet(candidates.size)) { it.uri }

        // A disconnected SD card is not a deleted library. Only prune sources that were
        // mounted for the entire enumeration, or a tree that was read successfully.
        val scannedPrefixes = (mountedBefore intersect mediaStoreSource.mountedAudioPrefixes()) +
            trees.map { it.toString().substringBefore("/document/").trimEnd('/') + "/document/" }
        val removedUris = ScanIdentity.missing(knownByUri.keys, candidateUris, scannedPrefixes)
        if (removedUris.isNotEmpty()) send(ScanEvent.TracksRemoved(removedUris))

        // 3. New or changed: no key match on (uri, size, lastModified).
        // force re-extracts everything; note this is decided AFTER the
        // deletion diff above, which always uses the real known keys.
        val toExtract = if (force) {
            candidates
        } else {
            candidates.filter { c ->
                val known = knownByUri[c.uri]
                known == null ||
                    known.sizeBytes != c.sizeBytes ||
                    known.lastModified != c.lastModified
            }
        }
        send(ScanEvent.Started(totalCandidates = toExtract.size))

        // 4. Bounded-parallel extraction, results funneled through a channel
        //    so event order stays sane while work interleaves.
        val results = Channel<ScanEvent>(capacity = Channel.BUFFERED)
        val semaphore = Semaphore(EXTRACT_PARALLELISM)
        val producer = launch {
            val jobs = toExtract.mapIndexed { index, candidate ->
                launch {
                    semaphore.withPermit {
                        val event = try {
                            ScanEvent.TrackScanned(
                                track = extractor.extract(candidate),
                                index = index,
                                total = toExtract.size,
                            )
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            ScanEvent.Failed(candidate.uri, e.message ?: e.javaClass.simpleName)
                        }
                        results.send(event)
                    }
                }
            }
            jobs.forEach { it.join() }
            results.close()
        }

        var added = 0
        var updated = 0
        var failed = 0
        results.consumeAsFlow().collect { event ->
            when (event) {
                is ScanEvent.TrackScanned ->
                    if (knownByUri.containsKey(event.track.song.uri)) updated++ else added++
                is ScanEvent.Failed -> failed++
                else -> Unit
            }
            send(event)
        }
        producer.join()

        send(ScanEvent.Completed(added, updated, removedUris.size, failed))
    }.buffer(Channel.BUFFERED).flowOn(BackgroundPriorityDispatchers.analysis)

    override fun changes(): Flow<Unit> = watcher.changes()

    private companion object {
       const val EXTRACT_PARALLELISM = 2
    }
}
