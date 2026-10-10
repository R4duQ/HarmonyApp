package com.harmony.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertTrue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteTrack
import com.harmony.desktop.connect.ConnectHost
import com.harmony.desktop.engine.AudioEngine
import com.harmony.desktop.engine.CountingSink
import com.harmony.desktop.engine.EqConfig
import com.harmony.desktop.library.CoverCache
import com.harmony.desktop.library.LibraryCache
import com.harmony.desktop.library.LibraryScanner
import com.harmony.desktop.library.LocalTrack
import com.harmony.desktop.player.PlayerController
import com.harmony.desktop.ui.HarmonyDesktopApp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class UiShots {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    // 1920x1080 (-Dshots.width/-Dshots.height) for the Microsoft Store listing.
    private val width = System.getProperty("shots.width")?.toIntOrNull() ?: 1280
    private val height = System.getProperty("shots.height")?.toIntOrNull() ?: 820

    private val artists = listOf("Daft Punk", "The Weeknd", "Joji", "Mira Sol", "Harbor & Pine")
    private val albums = listOf("Random Access Memories", "After Hours", "Smithereens", "Harbour Lights", "Open Water", "Field Notes", "Evergreen")
    private val titles = listOf("Instant Crush", "Blinding Lights", "Glimpse of Us", "Night Ferry", "Slow Weather", "Paper Moons", "Afterglow Street", "Glass Harbour", "Blue Hour Radio", "Tidewater", "Satellite Hearts", "Winter Garden")

    private fun tracks() = List(36) { i ->
        LocalTrack(
            path = "/music/$i.flac", title = titles[i % titles.size], artist = artists[i % artists.size], album = albums[i % albums.size],
            trackNumber = i % 12 + 1, year = 2013 + i % 10, durationMs = 180_000L + (i * 17_000L) % 140_000,
            codec = if (i % 3 == 0) "mp3" else "flac", sampleRate = if (i % 4 == 0) 96_000 else 44_100,
            bitDepth = if (i % 3 == 0) null else if (i % 4 == 0) 24 else 16, bitrateKbps = if (i % 3 == 0) 320 else 1411,
        )
    }

    private fun app(eq: EqConfig = EqConfig(), dark: Boolean = true, library: List<LocalTrack> = tracks()): DesktopApp {
        val tmp = createTempDir("harmony-ui")
        LibraryCache(File(tmp, "library.json")).save(library)
        val store = SettingsStore(File(tmp, "settings.json"))
        store.update {
            it.copy(
                folders = listOf("C:\\Users\\radu\\Music"), pcName = "RADU-DESKTOP", eq = eq, darkTheme = dark, phones = mapOf("t1" to "Pixel 8 Pro"),
                favorites = listOf(library[0].path, library[4].path, library[9].path),
            )
        }
        // A listening history, for Most played and Jump back in.
        var now = 1_700_000_000_000L
        val plays = PlayStats(File(tmp, "plays.json")) { now }
        listOf(0, 0, 0, 0, 0, 4, 4, 4, 4, 9, 9, 9, 13, 13, 2, 2, 7, 11, 18, 22, 25).forEach { now += 60_000; plays.record(library[it].path) }
        val engine = AudioEngine({ CountingSink(realTime = true) })
        val player = PlayerController(engine)
        val udp = DatagramSocket().use { it.localPort }
        val connect = ConnectHost(store.current.pcId, { store.current.pcName }, player, store.current.phones, {}, httpPort = 0, discoveryPort = udp)
        connect.start()
        return DesktopApp(store, engine, player, connect, LibraryCache(File(tmp, "library.json")), LibraryScanner { null }, CoverCache(File(tmp, "covers")), plays)
    }

    private fun shot(name: String, app: DesktopApp, nav: String? = null, before: () -> Unit = {}) = runDesktopComposeUiTest(width, height) {
        mainClock.autoAdvance = false
        before()
        setContent { HarmonyDesktopApp(app) }
        mainClock.advanceTimeBy(800)
        if (nav != null) {
            onNodeWithTag(nav).performSemanticsAction(SemanticsActions.OnClick)
            repeat(10) { mainClock.advanceTimeBy(300); waitForIdle() }
        }
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        app.close()
    }

    @Test fun home() = shot("home", app())

    @Test fun homeLight() = shot("home-light", app(dark = false))

    @Test fun songs() = shot("songs", app(), nav = "nav_songs")

    @Test fun songsLight() = shot("songs-light", app(dark = false), nav = "nav_songs")

    @Test fun favourites() = shot("favourites", app(), nav = "nav_favorites")

    /** A real song on disk (made with ffmpeg), so the waveform has a shape. */
    private fun songOnDisk(): LocalTrack {
        val file = File(createTempDir("harmony-song"), "Midnight Drive.flac")
        runCatching {
            ProcessBuilder(
                com.harmony.desktop.engine.FfmpegTools.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "aevalsrc=0.7*sin(2*PI*110*t)*(0.35+0.65*abs(sin(PI*t/37)))*(0.6+0.4*abs(sin(PI*t*1.9))):s=44100:d=226",
                "-c:a", "flac", file.path,
            ).inheritIO().start().waitFor()
        }
        return tracks()[0].copy(path = file.path, title = "Midnight Drive", artist = "Neon Coast", album = "Chill Vibes", durationMs = 226_000, codec = "flac", sampleRate = 44_100, bitDepth = 16, bitrateKbps = 1411)
    }

    private fun nowPlayingLocal(name: String, dark: Boolean) {
        val song = songOnDisk()
        val library = listOf(song) + tracks().drop(1)
        val a = app(dark = dark, library = library)
        a.setVolume(0.62f)
        a.setEq(EqConfig(enabled = true, bassDb = 2.4f))
        if (song.path.let(::File).isFile) kotlinx.coroutines.runBlocking { com.harmony.desktop.engine.Waveforms.of(song.path) }
        shot(name, a, nav = "nav_now_playing") {
            a.player.playLocal(library, 0)
            a.player.togglePlay()
            a.player.seek(84_000)
        }
    }

    @Test fun nowPlaying() = nowPlayingLocal("now-playing", dark = true)

    @Test fun nowPlayingLight() = nowPlayingLocal("now-playing-light", dark = false)

    @Test fun albums() = shot("albums", app(), nav = "nav_albums")

    @Test fun nowPlayingFromPhone() {
        val a = app()
        shot("now-playing-phone", a, nav = "nav_now_playing") {
            a.player.play(
                PlayRequest(
                    RemoteTrack("1", "Instant Crush", "Daft Punk", "Random Access Memories", 337_000, "http://127.0.0.1:9/none", null, "FLAC 16/44.1"),
                    positionMs = 120_000, playing = false, phoneName = "Pixel 8 Pro",
                    upNext = listOf(RemoteTrack("2", "Blinding Lights", "The Weeknd", "After Hours", 200_000, "x"), RemoteTrack("3", "Glimpse of Us", "Joji", "Smithereens", 233_000, "y")),
                ),
            )
        }
    }

    @Test fun equalizer() = shot("equalizer", app(EqConfig(enabled = true, winampGainsDb = listOf(6f, 4f, 2f, 0f, -2f, -1f, 2f, 4f, 5f, 6f), winampPreampDb = -4f, clarity = true)), nav = "nav_equalizer")

    @Test fun discKeepsTurningAfterTheSongChanges() = runDesktopComposeUiTest(200, 200) {
        mainClock.autoAdvance = false
        val key = androidx.compose.runtime.mutableStateOf("song-1")
        val loader = com.harmony.desktop.ui.ImageLoader(CoverCache(createTempDir("covers")))
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.testTag("disc")) {
                com.harmony.desktop.ui.Disc(null, loader, playing = true, size = androidx.compose.ui.unit.Dp(160f), key = key.value)
            }
        }
        fun frame() = onNodeWithTag("disc").captureToImage().toAwtImage().let { img -> IntArray(img.width * img.height) { img.getRGB(it % img.width, it / img.width) } }
        mainClock.advanceTimeBy(1_000)
        key.value = "song-2"
        repeat(30) { mainClock.advanceTimeBy(100) }
        val a = frame()
        repeat(10) { mainClock.advanceTimeBy(100) }
        val b = frame()
        key.value = "song-3"
        repeat(30) { mainClock.advanceTimeBy(100) }
        val c = frame()
        repeat(10) { mainClock.advanceTimeBy(100) }
        val d = frame()
        assertTrue("still turning after the second song", !a.contentEquals(b))
        assertTrue("and after the third", !c.contentEquals(d))
    }

    @Test fun equalizerLive() {
        // Pink noise through a bass-and-treble curve, so the analyzer has something to show.
        val noise = File(createTempDir("noise"), "pink.wav")
        val p = ProcessBuilder(
            com.harmony.desktop.engine.FfmpegTools.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.25:duration=20:sample_rate=48000", noise.absolutePath,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        org.junit.Assume.assumeTrue("ffmpeg is needed", p.waitFor() == 0)
        val eq = EqConfig(
            enabled = true, winampGainsDb = listOf(8f, 6f, 3f, 0f, -2f, -1f, 2f, 5f, 6f, 7f), winampPreampDb = -6f,
            bassDb = 3f, trebleDb = 2f, width = 1.3f, leveling = true,
        )
        val a = app(eq)
        val engine = a.engine
        a.engine.load(noise.absolutePath, 0, play = true, durationMs = 20_000)
        runDesktopComposeUiTest(width, height + 500) {
            mainClock.autoAdvance = false
            setContent { HarmonyDesktopApp(a) }
            mainClock.advanceTimeBy(800)
            onNodeWithTag("nav_equalizer").performSemanticsAction(SemanticsActions.OnClick)
            repeat(10) { mainClock.advanceTimeBy(300); waitForIdle() }
            Thread.sleep(1_500)
            repeat(20) { mainClock.advanceTimeBy(40); Thread.sleep(15) }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "equalizer-live.png"))
        }
        engine.stop()
        a.close()
    }

    @Test fun connect() {
        val a = app()
        shot("connect", a, nav = "nav_connect")
        assertEquals(4, a.connect.state.value.code.length)
    }
}
