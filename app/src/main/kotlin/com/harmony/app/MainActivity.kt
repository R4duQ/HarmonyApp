package com.harmony.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.app.navigation.HarmonyApp
import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.datastore.ThemeMode
import com.harmony.core.datastore.UserSettings
import com.harmony.core.ui.theme.HarmonyTheme
import com.harmony.feature.downloads.SpotiFlacDownloadEngine
import com.harmony.feature.downloads.SpotiFlacRequestOwner
import com.harmony.feature.downloads.SpotifyPlaylistClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var spotiFlacDownloadEngine: SpotiFlacDownloadEngine
    @Inject lateinit var spotifyPlaylistClient: SpotifyPlaylistClient
    @Inject lateinit var playbackConnection: com.harmony.playback.service.controller.PlaybackConnection

    private val verificationReturnSignal = MutableStateFlow(0)
    private val albumVerificationReturnSignal = MutableStateFlow(0)
    private val playlistVerificationReturnSignal = MutableStateFlow(0)
    private val spotifyAuthorizationReturnSignal = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = UserSettings())

            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // enableEdgeToEdge()'s own default (SystemBarStyle.auto) picks
            // status/nav bar icon colour from the OS's OWN dark-mode flag,
            // not from `dark` above — those two can disagree whenever the
            // user picks LIGHT or DARK here regardless of the phone's
            // system setting, which is exactly what left the clock/battery/
            // signal icons invisible: light-coloured icons meant for a dark
            // background, drawn over this app's near-white #F4FDFF light
            // field. Re-asserting the style explicitly, keyed to our own
            // `dark`, keeps the icons correct regardless of what the OS
            // itself is set to.
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }
            val returnSignal by verificationReturnSignal.collectAsStateWithLifecycle()
            val albumReturnSignal by albumVerificationReturnSignal.collectAsStateWithLifecycle()
            val playlistReturnSignal by playlistVerificationReturnSignal.collectAsStateWithLifecycle()
            val spotifyReturnSignal by spotifyAuthorizationReturnSignal.collectAsStateWithLifecycle()
            HarmonyTheme(
                darkTheme = dark,
                amoled = settings.amoledDark,
                dynamicColors = settings.dynamicColors,
            ) {
                HarmonyApp(
                    verificationReturnSignal = returnSignal,
                    albumVerificationReturnSignal = albumReturnSignal,
                    playlistVerificationReturnSignal = playlistReturnSignal,
                    spotifyAuthorizationReturnSignal = spotifyReturnSignal,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        playbackConnection.ensureConnected()
    }

    private fun handleIncomingIntent(sourceIntent: Intent?) {
        handleSpotiFlacVerificationIntent(sourceIntent)
        handleSpotifyPlaylistAuthorizationIntent(sourceIntent)
    }

    /**
     * Android host bridge for the callback contract used by SpotiFLAC Mobile.
     * Secrets are intentionally never logged or persisted by the Activity; the
     * raw URI is handed directly to the singleton backend adapter and then
     * removed from the Activity intent so configuration changes cannot replay it.
     */
    private fun handleSpotiFlacVerificationIntent(sourceIntent: Intent?) {
        if (sourceIntent?.action != Intent.ACTION_VIEW) return
        val data = sourceIntent.data ?: return
        if (!data.scheme.orEmpty().equals(SPOTIFLAC_SCHEME, ignoreCase = true)) return
        if (data.host.orEmpty().lowercase(Locale.ROOT) !in SPOTIFLAC_CALLBACK_HOSTS) return

        val callbackUrl = data.toString()
        sourceIntent.data = null
        // The pending-request owner decides whether the user returns to regular
        // Downloads or Playlist Transfer after provider verification.
        if (spotiFlacDownloadEngine.pendingVerificationOwner() ==
            SpotiFlacRequestOwner.PLAYLIST_TRANSFER
        ) {
            playlistVerificationReturnSignal.value = playlistVerificationReturnSignal.value + 1
        } else if (spotiFlacDownloadEngine.pendingVerificationOwner() == SpotiFlacRequestOwner.ALBUM_DOWNLOAD) {
            albumVerificationReturnSignal.value = albumVerificationReturnSignal.value + 1
        } else {
            verificationReturnSignal.value = verificationReturnSignal.value + 1
        }
        // The engine owns an application-lifetime callback scope so browser or
        // Activity lifecycle transitions cannot cancel grant completion. Do not
        // print callbackUrl here: signed-session grants and OAuth codes are
        // one-time credentials.
        spotiFlacDownloadEngine.enqueueExternalVerificationCallback(callbackUrl)
    }

    /** Receives the Authorization Code + PKCE return used by Playlist Transfer. */
    private fun handleSpotifyPlaylistAuthorizationIntent(sourceIntent: Intent?) {
        if (sourceIntent?.action != Intent.ACTION_VIEW) return
        val data = sourceIntent.data ?: return
        if (!data.scheme.orEmpty().equals(SPOTIFY_PLAYLIST_SCHEME, ignoreCase = true)) return
        if (!data.host.orEmpty().equals(SPOTIFY_PLAYLIST_CALLBACK_HOST, ignoreCase = true)) return

        val callbackUrl = data.toString()
        sourceIntent.data = null
        spotifyAuthorizationReturnSignal.value = spotifyAuthorizationReturnSignal.value + 1
        // The singleton owns an application-lifetime scope and never logs the
        // one-time code carried by callbackUrl.
        spotifyPlaylistClient.enqueueAuthorizationCallback(callbackUrl)
    }

    companion object {
        private const val SPOTIFLAC_SCHEME = "spotiflac"
        private val SPOTIFLAC_CALLBACK_HOSTS = setOf("callback", "spotify-callback", "session-grant")
        private const val SPOTIFY_PLAYLIST_SCHEME = "com.harmony.app"
        private const val SPOTIFY_PLAYLIST_CALLBACK_HOST = "spotify-playlist-callback"
    }
}
