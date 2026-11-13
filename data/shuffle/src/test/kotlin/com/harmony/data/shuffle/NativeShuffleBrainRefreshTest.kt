package com.harmony.data.shuffle

import androidx.room.Room
import com.harmony.core.common.coroutines.DispatcherProvider
import com.harmony.core.database.HarmonyDatabase
import com.harmony.core.database.entity.AnalysisResultEntity
import com.harmony.core.database.entity.SongEntity
import com.harmony.core.dsp.SmartShuffleNative
import com.harmony.domain.shuffle.brain.MixRequest
import com.harmony.domain.shuffle.brain.QueueRequest
import com.harmony.domain.shuffle.model.ShuffleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The incremental refresh against real (in-memory) databases: while the
 * background indexer adds vectors and analyses, [NativeShuffleBrain.prepare]
 * sends only those, and every queue and mix must come out exactly as after a
 * full reload of the same databases, with the session kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeShuffleBrainRefreshTest {
    private lateinit var harmony: HarmonyDatabase
    private lateinit var shuffle: SmartShuffleDatabase

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }

    @Before
    fun setUp() {
        assumeTrue("needs the host libharmonyshuffle (HARMONY_SHUFFLE_HOST_LIB_DIR)", SmartShuffleNative.available)
        val context = RuntimeEnvironment.getApplication()
        harmony = Room.inMemoryDatabaseBuilder(context, HarmonyDatabase::class.java).allowMainThreadQueries().build()
        shuffle = Room.inMemoryDatabaseBuilder(context, SmartShuffleDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        if (::harmony.isInitialized) harmony.close()
        if (::shuffle.isInitialized) shuffle.close()
    }

    private fun brain() = NativeShuffleBrain(shuffle, harmony.songDao(), harmony.historyDao(), harmony.analysisDao(), dispatchers)

    @Test
    fun refreshGivesTheQueuesAFullReloadGives() = runBlocking {
        val songs = 300L
        harmony.songDao().upsertSongs((1..songs).map(::song))
        (1L..200L).forEach { harmony.analysisDao().upsert(analysis(it)) }
        (1L..100L).forEach { putVector(it, measuredAt = 1_000L) }

        // Two brains on the same databases, the same session, the same steering.
        val incremental = brain()
        val reloaded = brain()
        val steering = ShuffleConfig(energyTarget = 0.7f)
        for (b in listOf(incremental, reloaded)) {
            b.prepare()
            b.applyConfig(steering)
            for (id in listOf(3L, 17L, 42L, 77L)) {
                b.onTrackStarted(id)
                b.onManualSelect(id)
            }
        }

        // The indexer measures more of the library: new vectors (one failed), new analyses.
        (101L..250L).forEach { putVector(it, measuredAt = 2_000L, failed = it == 150L) }
        (201L..280L).forEach { harmony.analysisDao().upsert(analysis(it)) }

        incremental.prepare()
        reloaded.forceFullReload()
        reloaded.prepare()
        assertEquals("the indexer's additions arrive without a full reload", 1, incremental.fullLoads)
        assertEquals(1, incremental.refreshes)
        assertEquals(2, reloaded.fullLoads)

        assertTrue(incremental.sessionVector()!!.contentEquals(reloaded.sessionVector()!!))
        for (seed in 1L..5L) {
            val queue = QueueRequest(currentSongId = 42L, prefixIds = emptyList(), excludeIds = setOf(3L), count = 12, seed = seed)
            val a = incremental.generate(queue)
            val b = reloaded.generate(queue)
            assertEquals("queue, seed $seed", b.songIds, a.songIds)
            assertEquals(b.traces, a.traces)
            assertEquals(b.audit?.copy(elapsedMs = 0f), a.audit?.copy(elapsedMs = 0f))
            val mix = MixRequest(seedSongId = 120L, currentSongId = 120L, prefixIds = emptyList(), excludeIds = emptySet(), count = 10, seed = seed)
            val ma = incremental.mix(mix)
            val mb = reloaded.mix(mix)
            assertEquals("mix, seed $seed", mb.songIds, ma.songIds)
            assertEquals(mb.traces, ma.traces)
        }

        // Nothing new: no work at all.
        incremental.prepare()
        assertEquals(1, incremental.refreshes)
        // A new song changes the library itself: that is a full load.
        harmony.songDao().upsertSongs(listOf(song(songs + 1)))
        incremental.prepare()
        assertEquals(2, incremental.fullLoads)
    }

    private suspend fun putVector(id: Long, measuredAt: Long, failed: Boolean = false) {
        val raw = FloatArray(SmartShuffleNative.DIMS) { d -> ((id * 31 + d * 17) % 97) / 97f }
        shuffle.acousticVectors().upsert(
            AcousticVectorEntity(
                songId = id,
                fileHash = "hash$id",
                extractorVersion = 1,
                vector = if (failed) null else AcousticVectorEntity.encode(raw),
                measuredAt = measuredAt,
            ),
        )
    }

    private fun song(id: Long) = SongEntity(
        id = id, uri = "content://songs/$id", title = "Song $id", artist = "Artist ${id % 37}",
        album = "Album ${id % 23}", albumId = id % 23, albumArtist = null, composer = null, year = 2000,
        genre = null, discNumber = 1, trackNumber = (id % 12).toInt(), durationMs = 200_000L,
        bitrateKbps = 1000, sampleRateHz = 44100, bitDepth = 16, channels = 2, fileSizeBytes = 30_000_000L,
        fileHash = "hash$id", lastModified = 0L, dateAdded = id, storageVolume = "external_primary",
        embeddedLyrics = null, artworkUri = null, replayGainTrackDb = null, replayGainAlbumDb = null,
    )

    private fun analysis(id: Long): AnalysisResultEntity {
        fun f(k: Int) = ((id * 13 + k * 7) % 100) / 100f
        return AnalysisResultEntity(
            songId = id, analyzedAtFileHash = "hash$id", analysisVersion = 1,
            bpm = 80f + (id % 60), beatConfidence = f(1), musicalKey = null, isMajor = null,
            rms = 0.1f + f(2) / 4, lufs = -12f, peakLevel = 0.5f + f(3) / 2, dynamicRange = 8f,
            bassEnergy = f(4), midEnergy = f(5), trebleEnergy = f(6),
            energy = f(7), danceability = f(8), acousticness = f(9), instrumentalness = f(10),
            brightness = f(11), warmth = f(12), aggressiveness = f(13), calmness = f(14),
            happiness = f(15), sadness = f(16), tension = f(17),
            embeddingBlob = ByteArray(0),
        )
    }
}
