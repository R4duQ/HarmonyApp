package com.harmony.core.remote

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class ConnectTest {
    private val closers = mutableListOf<AutoCloseable>()

    @After fun tearDown() = closers.reversed().forEach { runCatching { it.close() } }

    private class FakeRenderer : RemoteRenderer {
        val plays = mutableListOf<PlayRequest>()
        val controls = mutableListOf<ControlRequest>()
        var level = 1f
        var disconnectedBy: String? = null
        var pending = mutableListOf<RemoteRequest>()
        override fun play(request: PlayRequest) { plays += request }
        override fun control(request: ControlRequest) { controls += request }
        override fun setVolume(volume: Float) { level = volume }
        override fun status(): RemoteStatus {
            val last = plays.lastOrNull()
            val playing = controls.lastOrNull()?.action != ControlAction.PAUSE && (last?.playing ?: false)
            return RemoteStatus(
                state = if (last == null) RemoteState.IDLE else if (playing) RemoteState.PLAYING else RemoteState.PAUSED,
                trackId = last?.track?.id,
                positionMs = controls.lastOrNull { it.positionMs != null }?.positionMs ?: last?.positionMs ?: 0,
                durationMs = last?.track?.durationMs ?: 0,
                volume = level,
                requests = pending.toList().also { pending.clear() },
            )
        }
        override fun disconnect(phoneName: String?) { disconnectedBy = phoneName }
    }

    private fun track(id: String = "42") = RemoteTrack(id, "Instant Crush", "Daft Punk", "Random Access Memories", 337_000, "http://10.0.0.5:5000/track/$id?t=x", "http://10.0.0.5:5000/art/$id?t=x", "FLAC 16/44.1")

    private fun receiver(renderer: RemoteRenderer, pairing: Pairing): MiniHttpServer {
        val info = { PcInfo("pc-1", "Living Room PC", 0) }
        return MiniHttpServer(0, "test-connect", ConnectReceiver(info, pairing, renderer)::handle).start().also { closers += it }
    }

    @Test fun `messages survive the trip through JSON`() {
        val play = PlayRequest(track(), 61_000, playing = true, phoneName = "Pixel 8", upNext = listOf(track("43"), track("44").copy(artUrl = null, quality = null)))
        assertEquals(play, PlayRequest.fromJson(play.toJson()))
        val following = play.copy(autoAdvance = true, followUp = true)
        assertEquals(following, PlayRequest.fromJson(following.toJson()))
        // An older phone says nothing about either: the computer waits for it, as before.
        val old = org.json.JSONObject(play.toJson()).apply { remove("autoAdvance"); remove("followUp") }.toString()
        assertEquals(play, PlayRequest.fromJson(old))
        val status = RemoteStatus(RemoteState.PLAYING, "42", 12_345, 337_000, 0.8f, null, listOf(RemoteRequest.NEXT))
        assertEquals(status, RemoteStatus.fromJson(status.toJson()))
        assertEquals(ControlRequest(ControlAction.SEEK, 90_000), ControlRequest.fromJson(ControlRequest(ControlAction.SEEK, 90_000).toJson()))
        assertEquals(ControlRequest(ControlAction.PAUSE), ControlRequest.fromJson(ControlRequest(ControlAction.PAUSE).toJson()))
        val info = PcInfo("id", "PC", 47800)
        assertEquals(info.copy(host = "10.0.0.2"), PcInfo.fromJson(info.toJson(), "10.0.0.2"))
        assertNull(PcInfo.fromJson("""{"type":"something-else","id":"x","name":"y","port":1}"""))
        assertNull(PlayRequest.fromJson("not json"))
    }

    @Test fun `a phone pairs with the code on screen and then plays`() {
        val renderer = FakeRenderer()
        var saved: Map<String, String> = emptyMap()
        val pairing = Pairing { saved = it }
        val server = receiver(renderer, pairing)
        val client = ConnectClient("127.0.0.1", server.boundPort)

        assertEquals("Living Room PC", client.info().name)
        // Not paired yet: refused.
        try { client.play(PlayRequest(track(), 0, true, "Pixel 8")); fail() } catch (_: ConnectRefused) {}
        // Wrong code: refused, and nothing kept.
        val wrong = if (pairing.code == "0000") "1111" else "0000"
        try { client.pair(wrong, "Pixel 8"); fail() } catch (_: ConnectRefused) {}
        assertTrue(saved.isEmpty())

        val code = pairing.code
        val token = client.pair(code, "Pixel 8")
        assertEquals("Pixel 8", saved[token])
        assertTrue("the code changes once used", pairing.code != code || pairing.code.length == 4)

        val status = client.play(PlayRequest(track(), 61_000, playing = true, phoneName = "Pixel 8"))
        assertEquals(RemoteState.PLAYING, status?.state)
        assertEquals("42", renderer.plays.single().track.id)
        assertEquals(61_000L, renderer.plays.single().positionMs)

        client.control(ControlRequest(ControlAction.SEEK, 120_000))
        client.control(ControlRequest(ControlAction.PAUSE))
        client.volume(0.4f)
        val now = client.status()
        assertEquals(RemoteState.PAUSED, now.state)
        assertEquals(0.4f, now.volume, 1e-4f)
        assertEquals(listOf(ControlAction.SEEK, ControlAction.PAUSE), renderer.controls.map { it.action })

        // Next pressed on the computer reaches the phone once.
        renderer.pending += RemoteRequest.NEXT
        assertEquals(listOf(RemoteRequest.NEXT), client.status().requests)
        assertTrue(client.status().requests.isEmpty())

        client.disconnect()
        assertEquals("Pixel 8", renderer.disconnectedBy)

        // A token remembered from before still works after a restart.
        val restarted = receiver(FakeRenderer(), Pairing(saved))
        assertEquals(RemoteState.IDLE, ConnectClient("127.0.0.1", restarted.boundPort, token).status().state)
    }

    @Test fun `ranges are served the way a seeking player asks for them`() {
        assertEquals(0L to 99L, HttpResponse.parseRange("bytes=0-", 100))
        assertEquals(10L to 19L, HttpResponse.parseRange("bytes=10-19", 100))
        assertEquals(90L to 99L, HttpResponse.parseRange("bytes=-10", 100))
        assertEquals(50L to 99L, HttpResponse.parseRange("bytes=50-500", 100))
        assertNull(HttpResponse.parseRange("bytes=100-", 100))
        assertNull(HttpResponse.parseRange("items=0-1", 100))

        val bytes = ByteArray(200_000) { (it * 7 % 251).toByte() }
        val file = File.createTempFile("song", ".flac").apply { writeBytes(bytes); deleteOnExit() }
        val server = MiniHttpServer(0, "test-files") { req ->
            if (req.path == "/track/1") HttpResponse.ranged(FileRangeSource(file), req.header("Range"), "audio/flac")
            else HttpResponse.text(404, "no")
        }.start().also { closers += it }

        fun get(range: String?): Triple<Int, Map<String, String?>, ByteArray> {
            val c = URL("http://127.0.0.1:${server.boundPort}/track/1").openConnection() as HttpURLConnection
            range?.let { c.setRequestProperty("Range", it) }
            val body = c.inputStream.use { it.readBytes() }
            return Triple(c.responseCode, mapOf("range" to c.getHeaderField("Content-Range"), "accept" to c.getHeaderField("Accept-Ranges")), body)
        }

        val (whole, wholeHeaders, all) = get(null)
        assertEquals(200, whole)
        assertEquals("bytes", wholeHeaders["accept"])
        assertArrayEquals(bytes, all)

        val (partial, headers, part) = get("bytes=150000-")
        assertEquals(206, partial)
        assertEquals("bytes 150000-199999/200000", headers["range"])
        assertArrayEquals(bytes.copyOfRange(150_000, 200_000), part)

        val c = URL("http://127.0.0.1:${server.boundPort}/track/1").openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=300000-")
        assertEquals(416, c.responseCode)
    }

    @Test fun `the phone finds the computer on the network`() {
        val port = DatagramSocket().use { it.localPort }
        DiscoveryResponder({ PcInfo("pc-9", "Studio", 47800) }, port).start().also { closers += it }
        val found = Discovery.find(timeoutMs = 800, targets = listOf(InetAddress.getByName("127.0.0.1")), port = port)
        assertEquals(listOf("Studio"), found.map { it.name })
        assertEquals("127.0.0.1", found.single().host)
        assertEquals(47800, found.single().port)
    }
}
