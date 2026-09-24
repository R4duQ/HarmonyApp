package com.harmony.feature.discover.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.harmony.core.ui.network.InternetMonitor
import com.harmony.core.ui.network.InternetState
import com.harmony.feature.discover.provider.DiscoveryConnectivity
import com.harmony.feature.discover.provider.LocalFileProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** A throwaway ExoPlayer per clip, capped at 30 seconds, with audio focus. */
class ExoPreviewPlayerFactory @Inject constructor(@ApplicationContext private val context: Context) : PreviewPlayerFactory {
    override fun create(): PreviewPlayer = object : PreviewPlayer {
        private var player: ExoPlayer? = null

        override fun play(uri: String, fromLibrary: Boolean, onReady: () -> Unit, onEnded: () -> Unit, onError: () -> Unit) {
            val next = ExoPlayer.Builder(context).build()
            player = next
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            next.setHandleAudioBecomingNoisy(true)
            next.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (player !== next) return
                    if (state == Player.STATE_READY) onReady()
                    if (state == Player.STATE_ENDED) onEnded()
                }
                override fun onPlayerError(error: PlaybackException) { if (player === next) onError() }
            })
            next.setMediaItem(MediaItem.Builder().setUri(Uri.parse(uri)).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(30_000).build()).build())
            next.prepare(); next.play()
        }

        override fun release() { player?.release(); player = null }
    }
}

class ContentResolverFileProbe @Inject constructor(@ApplicationContext private val context: Context) : LocalFileProbe {
    override fun readable(uri: String): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } == true
    } catch (_: Exception) { false }
}

/** Validated internet (Android's own check that the network actually reaches the internet). */
class InternetDiscoveryConnectivity @Inject constructor(@ApplicationContext context: Context) : DiscoveryConnectivity {
    override val state: StateFlow<InternetState> = InternetMonitor.get(context).state
}
