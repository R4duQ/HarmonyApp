package com.harmony.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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

    private fun app(eq: EqConfig = EqConfig(), dark: Boolean = true): DesktopApp {
        val tmp = createTempDir("harmony-ui")
        LibraryCache(File(tmp, "library.json")).save(tracks())
        val store = SettingsStore(File(tmp, "settings.json"))
        store.update { it.copy(folders = listOf("C:\\Users\\radu\\Music"), pcName = "RADU-DESKTOP", eq = eq, darkTheme = dark, phones = mapOf("t1" to "Pixel 8 Pro")) }
        val engine = AudioEngine({ CountingSink() })
        val player = PlayerController(engine)
        val udp = DatagramSocket().use { it.localPort }
        val connect = ConnectHost(store.current.pcId, { store.current.pcName }, player, store.current.phones, {}, httpPort = 0, discoveryPort = udp)
        connect.start()
        return DesktopApp(store, engine, player, connect, LibraryCache(File(tmp, "library.json")), LibraryScanner { null }, CoverCache(File(tmp, "covers")))
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

    @Test fun songs() = shot("songs", app()) { }

    @Test fun songsLight() = shot("songs-light", app(dark = false))

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

    @Test fun connect() {
        val a = app()
        shot("connect", a, nav = "nav_connect")
        assertEquals(4, a.connect.state.value.code.length)
    }
}
