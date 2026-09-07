package com.harmony.domain.library

import com.harmony.core.model.Song
import com.harmony.domain.library.gateway.*
import com.harmony.domain.library.model.*
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ScanLibraryUseCaseTest {
    private val track = ScannedTrack(Song(1, "content://test/1", "Title", "Artist", "Album", 1,
        null, null, null, null, 1, 1, 180_000, null, null, null, null, null, null, null, null), 1000, 1, "hash", "internal")
    private class Writer : LibraryWriteGateway {
        val saved = mutableListOf<ScannedTrack>()
        override suspend fun currentScanKeys() = saved.map { ScanKey(it.song.uri, it.fileSizeBytes, it.lastModified) }
        override suspend fun upsert(tracks: List<ScannedTrack>) { saved.addAll(tracks) }
        override suspend fun removeByUris(uris: List<String>) { }
    }
    private fun scanner(events: () -> Flow<ScanEvent>) = object : MediaScanGateway {
        override fun scan(knownKeys: Collection<ScanKey>, force: Boolean) = events()
        override fun changes(): Flow<Unit> = emptyFlow()
    }
    @Test fun completedIsEmittedOnlyAfterTheLastPartialBatchIsCommitted() = runBlocking {
        val writer = Writer()
        val scan = ScanLibraryUseCase(scanner { flowOf(ScanEvent.TrackScanned(track, 0, 1), ScanEvent.Completed(1, 0, 0, 0)) }, writer)
        scan().first { it is ScanEvent.Completed }
        assertEquals(listOf(track), writer.saved)
    }
    @Test fun concurrentManualAndBackgroundScansDoNotOverlap() = runBlocking {
        var active = 0
        var maximum = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scan = ScanLibraryUseCase(scanner { flow {
            active++; maximum = maxOf(maximum, active); entered.complete(Unit)
            release.await()
            emit(ScanEvent.Completed(0, 0, 0, 0))
            active--
        } }, Writer())
        val first = launch { scan().collect() }
        entered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { scan().collect() }
        release.complete(Unit)
        joinAll(first, second)
        assertEquals(1, maximum)
    }
    @Test fun failedScanReleasesTheGateForRetry() = runBlocking {
        var attempts = 0
        val scan = ScanLibraryUseCase(scanner { flow {
            if (attempts++ == 0) error("Provider unavailable")
            emit(ScanEvent.Completed(0, 0, 0, 0))
        } }, Writer())
        assertTrue(runCatching { scan().collect() }.isFailure)
        assertTrue(scan().first() is ScanEvent.Completed)
    }
}
