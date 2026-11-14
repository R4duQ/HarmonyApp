package com.harmony.data.shuffle

import androidx.room.Room
import com.harmony.core.common.coroutines.DispatcherProvider
import com.harmony.core.database.HarmonyDatabase
import com.harmony.core.database.entity.SongEntity
import com.harmony.core.dsp.SmartShuffleNative
import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.shuffle.brain.MixRequest
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

/** The taste memory: what online radios put together, written and then heard in the offline mix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TasteMemoryTest {
    private lateinit var harmony: HarmonyDatabase
    private lateinit var shuffle: SmartShuffleDatabase

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        harmony = Room.inMemoryDatabaseBuilder(context, HarmonyDatabase::class.java).allowMainThreadQueries().build()
        shuffle = Room.inMemoryDatabaseBuilder(context, SmartShuffleDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        harmony.close()
        shuffle.close()
    }

    private val radio = listOf(
        TasteMemory.Track("In the End", "Linkin Park"),
        TasteMemory.Track("Numb", "Linkin Park"),
        TasteMemory.Track("Chop Suey!", "System of a Down"),
        TasteMemory.Track("Bring Me to Life", "Evanescence feat. Paul McCoy"),
    )

    @Test
    fun aRadioIsRememberedAndRepeatsAddUp() = runBlocking {
        val memory = TasteMemory(shuffle)
        memory.record(seedSongId = 9, seedArtist = "Linkin Park", sameSong = true, tracks = radio, now = 1_000)
        val first = memory.all()
        val songs = first.filter { it.kind == TasteEdgeEntity.SONG }
        assertEquals(4, songs.size)
        assertTrue(songs.all { it.fromKey == "id:9" })
        val inTheEnd = songs.single { it.toKey == TrackIdentity.of("In the End", "Linkin Park").key }
        assertEquals("the top of the radio counts most", TasteMemory.SONG_STEP, inTheEnd.weight, 1e-6f)
        val artists = first.filter { it.kind == TasteEdgeEntity.ARTIST }.associate { it.toKey to it.weight }
        assertEquals("the seed's own artist is no link", setOf("system of a down", "evanescence"), artists.keys)

        // The same radio again soon after: not counted twice. A day later: it adds up.
        memory.record(9, "Linkin Park", true, radio, now = 2_000)
        assertEquals(first.toSet(), memory.all().toSet())
        memory.record(9, "Linkin Park", true, radio, now = 2_000 + TasteMemory.REPEAT_MS)
        val again = memory.all().single { it.kind == TasteEdgeEntity.SONG && it.toKey == inTheEnd.toKey }
        assertEquals(TasteMemory.grow(TasteMemory.SONG_STEP, TasteMemory.SONG_STEP), again.weight, 1e-6f)
        assertTrue(again.weight < 1f)
    }

    @Test
    fun anArtistsRadioTeachesOnlyArtists() = runBlocking {
        val memory = TasteMemory(shuffle)
        memory.record(9, "Linkin Park", sameSong = false, tracks = radio, now = 1_000)
        assertTrue(memory.all().all { it.kind == TasteEdgeEntity.ARTIST && it.fromKey == "linkin park" })
        memory.record(10, "<unknown>", sameSong = false, tracks = radio, now = 1_000)
        assertEquals("an unknown artist links nobody", 2, memory.all().size)
    }

    @Test
    fun theOfflineMixHearsWhatTheRadioSaid() = runBlocking {
        assumeTrue("needs the host libharmonyshuffle (HARMONY_SHUFFLE_HOST_LIB_DIR)", SmartShuffleNative.available)
        // 200 songs of all kinds; five of them, far from the seed by sound, are what its radio played.
        harmony.songDao().upsertSongs((1L..200L).map(::song))
        val random = java.util.Random(7)
        (1L..200L).forEach { id ->
            val raw = FloatArray(SmartShuffleNative.DIMS) { random.nextFloat() }
            shuffle.acousticVectors().upsert(AcousticVectorEntity(id, "hash$id", 1, AcousticVectorEntity.encode(raw), 1_000L))
        }
        val request = MixRequest(seedSongId = 1L, currentSongId = 1L, prefixIds = emptyList(), excludeIds = emptySet(), count = 20, seed = 5)

        val brain = NativeShuffleBrain(shuffle, harmony.songDao(), harmony.historyDao(), harmony.analysisDao(), dispatchers)
        brain.prepare()
        val before = brain.mix(request).songIds
        // The five the seed's mix by sound puts last.
        val far = brain.mix(request.copy(count = 199)).songIds.takeLast(5).toSet()
        assertTrue("by sound alone they stay out: $before", before.none { it in far })

        TasteMemory(shuffle).record(1L, "Artist 1", true, far.map { TasteMemory.Track("Song $it", "Artist $it") })
        brain.prepare()
        assertEquals("the new taste arrives without a full reload", 1, brain.refreshes)
        assertEquals("five songs and five artists, all in the library", 5 to 5, brain.tastePairs)
        val after = brain.mix(request).songIds
        assertTrue("what the radio put with the seed is in the mix: $after (far $far)", after.count { it in far } >= 4)
    }

    @Test
    fun aVersionOneDatabaseIsMigratedAndKeepsItsData() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("migrate-test.db").apply { parentFile?.mkdirs(); delete() }
        // Version 1 exactly as Room wrote it (the exported schema), with a habit in it.
        val v1 = org.json.JSONObject(java.io.File("schemas/${SmartShuffleDatabase::class.qualifiedName}/1.json").readText())
            .getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = v1.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices") ?: continue
                for (k in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                }
            }
            val setup = v1.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO markov_transitions (fromSongId, toSongId, weight, updatedAt) VALUES (1, 2, 3.0, 4)")
            db.version = 1
        }
        val migrated = Room.databaseBuilder(context, SmartShuffleDatabase::class.java, file.path)
            .addMigrations(*SmartShuffleDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(1, migrated.markov().count())
            TasteMemory(migrated).record(1L, "Artist 1", true, radio)
            assertTrue(migrated.taste().stamp().count > 0)
        } finally {
            migrated.close()
            file.delete()
        }
    }

    private fun song(id: Long) = SongEntity(
        id = id, uri = "content://songs/$id", title = "Song $id", artist = "Artist $id",
        album = "Album ${id % 23}", albumId = id % 23, albumArtist = null, composer = null, year = 2000,
        genre = null, discNumber = 1, trackNumber = (id % 12).toInt(), durationMs = 200_000L,
        bitrateKbps = 1000, sampleRateHz = 44100, bitDepth = 16, channels = 2, fileSizeBytes = 30_000_000L,
        fileHash = "hash$id", lastModified = 0L, dateAdded = id, storageVolume = "external_primary",
        embeddedLyrics = null, artworkUri = null, replayGainTrackDb = null, replayGainAlbumDb = null,
    )
}
