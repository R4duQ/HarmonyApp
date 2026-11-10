package com.harmony.feature.downloads

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stage 3, the checks that block a merge: an album of 14 tracks with one
 * cover makes one cover request; every track carries its own disc and track
 * numbers and the album's artist; an ambiguous recording is refused; and the
 * audio a tagged file decodes to is the audio that was downloaded.
 */
class SpotiFlacAlbumWorkTest {
    @get:Rule val folder = TemporaryFolder()

    private class CoverHost(private val body: ByteArray) {
        val requests = AtomicInteger()
        fun open(@Suppress("UNUSED_PARAMETER") url: String) = object : SpotiFlacArtworkFetcher.Exchange {
            init { requests.incrementAndGet() }
            override val status = 200
            override fun header(name: String): String? = when (name.lowercase()) {
                "content-type" -> "image/jpeg"
                "content-length" -> body.size.toString()
                else -> null
            }
            override fun body(): InputStream = ByteArrayInputStream(body)
            override fun close() = Unit
        }
    }

    private class Track(val disc: Int, val number: Int, val title: String, val artist: String, val pcm: ShortArray)

    @Test fun fourteenTracksMakeOneCoverRequestAndEachGetsItsOwnTagsAndTheSameAudio() {
        val coverUrl = "https://cdn.example.com/album/1000x1000.jpg"
        val coverBytes = ByteArray(40_000) { (it % 251).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        val host = CoverHost(coverBytes)
        val covers = SpotiFlacArtworkFetcher(
            maxBytes = 8L * 1024 * 1024, resolve = { listOf(InetAddress.getByName("93.184.216.34")) }, open = host::open,
        )
        // Two discs, nine and five tracks; a compilation, so the track artists differ from the album's.
        val album = (1..9).map { Track(1, it, "Song $it", "Artist $it", TestFlac.pcm(9_000 + it * 37, seed = it)) } +
            (1..5).map { Track(2, it, "Bonus $it", "Guest $it", TestFlac.pcm(5_000 + it * 53, seed = 100 + it)) }
        assertEquals(14, album.size)

        for (track in album) {
            // What a provider (and the runtime's own embedding) left in the file: some of it wrong for this album.
            val providerTags = listOf(
                "TITLE=${track.title} (provider spelling)", "ARTIST=${track.artist}", "ALBUM=Greatest Hits",
                "ALBUMARTIST=${track.artist}", "album_artist=${track.artist}", "TRACKNUMBER=99", "TRACKTOTAL=99",
                "DISCNUMBER=9", "DATE=1999", "GENRE=Rock", "REPLAYGAIN_TRACK_GAIN=-7.20 dB", "ISRC=OLD000000000",
            )
            val source = File(folder.root, "d${track.disc}t${track.number}-source.flac").apply { writeBytes(TestFlac.encode(track.pcm, providerTags)) }
            val before = TestFlac.decode(source.readBytes())
            val output = File(folder.root, "d${track.disc}t${track.number}.flac")

            val fetched = covers.fetch(coverUrl)!!
            val tags = FlacMetadataWriter.Tags(
                title = track.title, artist = track.artist, album = "Night Drive", albumArtist = "Various Artists",
                isrc = "USRC1${track.disc}${track.number.toString().padStart(6, '0')}",
                trackNumber = track.number, totalTracks = if (track.disc == 1) 9 else 5,
                discNumber = track.disc, totalDiscs = 2, date = "2013-05-17",
            )
            assertTrue(FlacMetadataWriter.rewrite(source, output, tags, FlacMetadataWriter.Artwork(fetched.bytes, "image/jpeg", 1000, 1000, 24)))

            val after = TestFlac.decode(output.readBytes())
            // Each decided tag exactly once, with this track's value; no other spelling of it left behind.
            val expected = mapOf(
                "TITLE" to track.title, "ARTIST" to track.artist, "ALBUM" to "Night Drive", "ALBUMARTIST" to "Various Artists",
                "TRACKNUMBER" to track.number.toString(), "TRACKTOTAL" to (if (track.disc == 1) "9" else "5"),
                "DISCNUMBER" to track.disc.toString(), "DISCTOTAL" to "2", "DATE" to "2013-05-17", "ISRC" to tags.isrc,
            )
            for ((key, value) in expected) assertEquals("disc ${track.disc} track ${track.number} $key", listOf(value), after.comment(key))
            assertTrue("no second spelling of the album artist", after.comment("ALBUM_ARTIST").isEmpty())
            // What Harmony does not decide stays as the provider wrote it.
            assertEquals(listOf("Rock"), after.comment("GENRE"))
            assertEquals(listOf("-7.20 dB"), after.comment("REPLAYGAIN_TRACK_GAIN"))
            assertEquals("provider encoder", after.vendor)
            assertEquals("one front cover", 1, after.pictures.size)
            assertTrue(after.pictures.single().size > coverBytes.size)

            // The audio: the same frames byte for byte, the same STREAMINFO, the same decoded PCM.
            assertArrayEquals("audio frames copied verbatim", before.audioFrames, after.audioFrames)
            assertArrayEquals(before.streamInfo, after.streamInfo)
            assertArrayEquals("decodes to the downloaded audio", track.pcm, after.samples)
            assertEquals("and to what STREAMINFO promises", after.declaredMd5, after.decodedMd5)
        }
        assertEquals("the album's cover was fetched once", 1, host.requests.get())

        // Another album's cover is another request; the first is still remembered.
        assertTrue(covers.fetch("https://cdn.example.com/other/1000x1000.jpg") != null)
        assertTrue(covers.fetch(coverUrl) != null)
        assertEquals(2, host.requests.get())
    }

    @Test fun tracksAskingForTheCoverAtTheSameTimeShareOneRequest() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val covers = SpotiFlacArtworkFetcher(maxBytes = 1_000_000, resolve = { listOf(InetAddress.getByName("93.184.216.34")) }) { _ ->
            object : SpotiFlacArtworkFetcher.Exchange {
                init { requests.incrementAndGet(); arrived.countDown(); release.await(5, TimeUnit.SECONDS) }
                override val status = 200
                override fun header(name: String): String? = if (name.equals("Content-Type", true)) "image/png" else null
                override fun body(): InputStream = ByteArrayInputStream(ByteArray(100) { 1 })
                override fun close() = Unit
            }
        }
        val results = java.util.Collections.synchronizedList(mutableListOf<ArtworkBytes?>())
        val callers = (1..6).map { thread { results += covers.fetch("https://cdn.example.com/cover.png") } }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)   // the others are now waiting on the first
        release.countDown()
        callers.forEach { it.join(5_000) }
        assertEquals(6, results.size)
        assertTrue(results.all { it != null && it.bytes.size == 100 })
        assertEquals(1, requests.get())
    }

    @Test fun onlyDecidedTagsChangeAndAFileWithoutTagsGetsThem() {
        val pcm = TestFlac.pcm(4_200, seed = 7)
        val bare = File(folder.root, "bare.flac").apply { writeBytes(TestFlac.encode(pcm)) }
        val tagged = File(folder.root, "tagged.flac")
        assertTrue(FlacMetadataWriter.rewrite(bare, tagged, FlacMetadataWriter.Tags(title = "T", artist = "A", album = "L"), null))
        val after = TestFlac.decode(tagged.readBytes())
        assertEquals(listOf("T"), after.comment("TITLE"))
        // No album artist is known: the track's artist stands in, as before.
        assertEquals(listOf("A"), after.comment("ALBUMARTIST"))
        assertTrue("no numbering was decided, none is invented", after.comment("TRACKNUMBER").isEmpty() && after.comment("DISCNUMBER").isEmpty())
        assertTrue(after.pictures.isEmpty())
        assertArrayEquals(pcm, after.samples)

        // A single track with nothing to say about the album keeps the provider's numbering.
        val numbered = File(folder.root, "numbered.flac").apply { writeBytes(TestFlac.encode(pcm, listOf("TRACKNUMBER=7", "DISCNUMBER=2", "ALBUMARTIST=Band"))) }
        val kept = File(folder.root, "kept.flac")
        assertTrue(FlacMetadataWriter.rewrite(numbered, kept, FlacMetadataWriter.Tags(title = "T"), null))
        val keptTags = TestFlac.decode(kept.readBytes())
        assertEquals(listOf("7"), keptTags.comment("TRACKNUMBER"))
        assertEquals(listOf("2"), keptTags.comment("DISCNUMBER"))
        assertEquals(listOf("Band"), keptTags.comment("ALBUMARTIST"))

        // Something that is not FLAC is left alone.
        val notFlac = File(folder.root, "x.flac").apply { writeText("ID3 not flac at all") }
        assertFalse(FlacMetadataWriter.rewrite(notFlac, File(folder.root, "y.flac"), FlacMetadataWriter.Tags(title = "T"), null))
    }

    @Test fun anAmbiguousRecordingIsRefused() {
        val listed = 214_000L   // the edition's track
        assertTrue(AlbumDownloadPolicy.isSameRecording(listed, 214_000))
        assertTrue(AlbumDownloadPolicy.isSameRecording(listed, 218_900))
        assertTrue(AlbumDownloadPolicy.isSameRecording(listed, 209_100))
        // Same title, another recording: a live take, an extended mix, a radio edit.
        assertFalse(AlbumDownloadPolicy.isSameRecording(listed, 251_000))
        assertFalse(AlbumDownloadPolicy.isSameRecording(listed, 219_001))
        assertFalse(AlbumDownloadPolicy.isSameRecording(listed, 180_000))
        // A file whose length cannot be read is not evidence of anything.
        assertFalse(AlbumDownloadPolicy.isSameRecording(listed, 0))
        assertFalse(AlbumDownloadPolicy.isSameRecording(listed, -1))
    }

    // -- song.link ---------------------------------------------------------------------------

    @Test fun aConfirmedMatchIsReusedUntilItExpires() {
        var now = 0L
        val memory = LookupMemory<String>(capacity = 2, ttlMs = 1_000) { now }
        var asked = 0
        fun ids(key: String) = memory.getOrAsk(key) { asked++; "ids of $key #$asked" }
        assertEquals("ids of a #1", ids("a"))
        now += 999
        assertEquals("ids of a #1", ids("a"))
        assertEquals(1, asked)
        now += 1
        assertEquals("expired: asked again", "ids of a #2", ids("a"))
        // An answer that was no answer is not remembered.
        assertNull(memory.getOrAsk("b") { null })
        assertEquals("ids", memory.getOrAsk("b") { "ids" })
        // The least recently used makes room.
        ids("c")
        assertNull(memory.get("a"))
        // Asked too often: nobody asks until the pause ends, but what is remembered is still used.
        memory.pauseFor(60_000)
        val before = asked
        assertNull(memory.getOrAsk("z") { asked++; "never" })
        assertEquals(before, asked)
        assertEquals("ids", memory.getOrAsk("b") { "not asked" })
        now += 60_000
        assertFalse(memory.paused)
    }

    @Test fun simultaneousRequestsForTheSameTrackBecomeOne() {
        val memory = LookupMemory<String>(capacity = 8, ttlMs = 60_000) { 0L }
        val asking = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val asked = AtomicInteger()
        val results = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val callers = (1..8).map {
            thread {
                results += memory.getOrAsk("https://open.spotify.com/track/abc") {
                    asked.incrementAndGet(); asking.countDown(); answer.await(5, TimeUnit.SECONDS); "tidal 1, qobuz 2"
                }
            }
        }
        assertTrue(asking.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        answer.countDown()
        callers.forEach { it.join(5_000) }
        assertEquals(List(8) { "tidal 1, qobuz 2" }, results.toList())
        assertEquals(1, asked.get())
        // A different track is its own request.
        assertEquals("other", memory.getOrAsk("https://open.spotify.com/track/xyz") { asked.incrementAndGet(); "other" })
        assertEquals(2, asked.get())
    }

    @Test fun aProviderIsTriedWithoutAskingWhenItsExactIdIsAlreadyKnown() {
        fun exact(provider: String, deezer: String? = null, tidal: String? = null, qobuz: String? = null) =
            SpotiFlacProviderMatch.hasExactId(provider, deezer, tidal, qobuz)
        // Harmony's own search gives Deezer ids: Deezer can start at once.
        assertTrue(exact("deezer", deezer = "3135556"))
        // Tidal and Qobuz ids come from song.link: with only a Deezer id they still need the lookup.
        assertFalse(exact("tidal-web", deezer = "3135556"))
        assertFalse(exact("qobuz-web", deezer = "3135556"))
        assertTrue(exact("tidal-web", tidal = "77646168"))
        assertTrue(exact("qobuz-web", qobuz = "59954870"))
        assertFalse(exact("deezer", deezer = " "))
        // Amazon has no id Harmony carries.
        assertFalse(exact("amazon", deezer = "1", tidal = "2", qobuz = "3"))
        // Availability is a state of its own, and "unknown" is one of them.
        assertEquals(
            listOf("AVAILABLE", "UNAVAILABLE", "UNKNOWN"),
            SpotiFlacAvailabilityState.entries.map { it.name },
        )
    }
}
