package com.harmony.playback.service.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.harmony.domain.playback.PlaybackController
import com.harmony.playback.service.controller.PlaybackConnection
import com.harmony.playback.service.player.HarmonyPlayer
import com.harmony.playback.service.player.ReplayGainAudioProcessor
import dagger.Binds
import com.harmony.core.media.artwork.ArtworkCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Singleton

/**
 * Wires the playback stack:
 *  - [PlaybackController] (domain interface) -> [PlaybackConnection] for the app process
 *  - [HarmonyPlayer] owned by one service instance
 *
 * Note the two halves talk only through the MediaSession, never directly,
 * which is what keeps this working when the service is started by Android
 * Auto or a Bluetooth button with no activity alive.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindsModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackController(impl: PlaybackConnection): PlaybackController
}

@OptIn(UnstableApi::class)
@Module
@InstallIn(ServiceComponent::class)
object PlaybackProvidesModule {

    @Provides
    @ServiceScoped
    fun provideReplayGainProcessor(): ReplayGainAudioProcessor = ReplayGainAudioProcessor()

    @Provides
    @ServiceScoped
    fun provideEqualizerProcessor(): com.harmony.playback.service.player.EqualizerAudioProcessor =
        com.harmony.playback.service.player.EqualizerAudioProcessor()

    @Provides
    @ServiceScoped
    fun provideHarmonyPlayer(
        @ApplicationContext context: Context,
        replayGainProcessor: ReplayGainAudioProcessor,
        equalizerProcessor: com.harmony.playback.service.player.EqualizerAudioProcessor,
        artworkCache: ArtworkCache,
    ): HarmonyPlayer =
        HarmonyPlayer(context, replayGainProcessor, equalizerProcessor, artworkCache)

}

@Module
@InstallIn(SingletonComponent::class)
object PlaybackContextModule {
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context
}
