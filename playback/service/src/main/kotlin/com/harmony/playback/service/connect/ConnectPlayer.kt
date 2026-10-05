package com.harmony.playback.service.connect

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The player the media session sees. Normally it is simply the phone's
 * ExoPlayer. While the music plays on a computer ([ConnectSession.isActive])
 * the ExoPlayer stays paused and keeps the queue, and this player answers
 * for the computer: playing or not, where in the song, and play, pause and
 * seek go to the computer. Skipping still moves the ExoPlayer's queue; the
 * session sends the computer the new song.
 *
 * So the notification, the lock screen, Bluetooth buttons, Android Auto and
 * the app itself all keep working unchanged, now as a remote.
 */
@UnstableApi
class ConnectPlayer(private val exo: ExoPlayer, private val session: ConnectSession) : ForwardingPlayer(exo) {
    private val listeners = CopyOnWriteArrayList<Pair<Player.Listener, Filtering>>()

    private val remote: Boolean get() = session.isActive

    override fun addListener(listener: Player.Listener) {
        val wrapper = Filtering(listener)
        listeners += listener to wrapper
        exo.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.filter { it.first == listener }.forEach {
            listeners.remove(it)
            exo.removeListener(it.second)
        }
    }

    // ---- State ----------------------------------------------------------------

    override fun isPlaying(): Boolean = if (remote) session.remoteIsPlaying() else super.isPlaying()
    override fun getPlayWhenReady(): Boolean = if (remote) session.remoteWantsPlay() else super.getPlayWhenReady()
    override fun getPlaybackState(): Int = if (remote) session.remotePlaybackState() else super.getPlaybackState()
    override fun getPlaybackSuppressionReason(): Int =
        if (remote) Player.PLAYBACK_SUPPRESSION_REASON_NONE else super.getPlaybackSuppressionReason()
    override fun isLoading(): Boolean = if (remote) false else super.isLoading()
    override fun getCurrentPosition(): Long = if (remote) session.remotePosition() else super.getCurrentPosition()
    override fun getContentPosition(): Long = if (remote) session.remotePosition() else super.getContentPosition()
    override fun getBufferedPosition(): Long = if (remote) session.remotePosition() else super.getBufferedPosition()
    override fun getContentBufferedPosition(): Long = if (remote) session.remotePosition() else super.getContentBufferedPosition()
    override fun getTotalBufferedDuration(): Long = if (remote) 0 else super.getTotalBufferedDuration()

    // ---- Commands ---------------------------------------------------------------

    override fun play() = if (remote) session.remotePlay() else super.play()
    override fun pause() = if (remote) session.remotePause() else super.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!remote) return super.setPlayWhenReady(playWhenReady)
        if (playWhenReady) session.remotePlay() else session.remotePause()
    }

    override fun stop() = if (remote) session.remotePause() else super.stop()

    override fun seekTo(positionMs: Long) {
        if (!remote) return super.seekTo(positionMs)
        exo.seekTo(positionMs)
        session.remoteSeek(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val same = mediaItemIndex == exo.currentMediaItemIndex
        super.seekTo(mediaItemIndex, positionMs)
        // Another song: the queue moves and the session sends it. The same song: just a seek.
        if (remote && same) session.remoteSeek(positionMs)
    }

    override fun seekBack() {
        if (!remote) return super.seekBack()
        seekTo((session.remotePosition() - exo.seekBackIncrement).coerceAtLeast(0))
    }

    override fun seekForward() {
        if (!remote) return super.seekForward()
        seekTo(session.remotePosition() + exo.seekForwardIncrement)
    }

    override fun seekToPrevious() {
        if (!remote) return super.seekToPrevious()
        // The ExoPlayer's own position stays where the song started on the computer, so decide here.
        if (session.remotePosition() > exo.maxSeekToPreviousPosition || !exo.hasPreviousMediaItem()) seekTo(0)
        else exo.seekToPreviousMediaItem()
    }

    // ---- Volume: the phone's volume keys turn the computer up and down ---------------

    override fun getDeviceInfo(): DeviceInfo = if (remote) REMOTE_DEVICE else super.getDeviceInfo()
    override fun getDeviceVolume(): Int = if (remote) session.remoteVolumeSteps() else super.getDeviceVolume()
    override fun isDeviceMuted(): Boolean = if (remote) session.remoteVolumeSteps() == 0 else super.isDeviceMuted()

    override fun setDeviceVolume(volume: Int, flags: Int) =
        if (remote) session.setRemoteVolumeSteps(volume) else super.setDeviceVolume(volume, flags)

    override fun increaseDeviceVolume(flags: Int) =
        if (remote) session.setRemoteVolumeSteps(session.remoteVolumeSteps() + 1) else super.increaseDeviceVolume(flags)

    override fun decreaseDeviceVolume(flags: Int) =
        if (remote) session.setRemoteVolumeSteps(session.remoteVolumeSteps() - 1) else super.decreaseDeviceVolume(flags)

    override fun setDeviceMuted(muted: Boolean, flags: Int) =
        if (remote) session.setRemoteVolumeSteps(if (muted) 0 else VOLUME_STEPS / 2) else super.setDeviceMuted(muted, flags)

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun setDeviceVolume(volume: Int) = if (remote) session.setRemoteVolumeSteps(volume) else super.setDeviceVolume(volume)

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun increaseDeviceVolume() = if (remote) session.setRemoteVolumeSteps(session.remoteVolumeSteps() + 1) else super.increaseDeviceVolume()

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun decreaseDeviceVolume() = if (remote) session.setRemoteVolumeSteps(session.remoteVolumeSteps() - 1) else super.decreaseDeviceVolume()

    override fun getAvailableCommands(): Player.Commands =
        if (remote) super.getAvailableCommands().buildUpon().addAll(*VOLUME_COMMANDS).build() else super.getAvailableCommands()

    override fun isCommandAvailable(command: Int): Boolean =
        if (remote && command in VOLUME_COMMANDS) true else super.isCommandAvailable(command)

    // ---- Telling the session's listeners what the computer is doing ----------------

    /** Playing here or on a computer changed: the volume keys and the commands follow. */
    internal fun notifyRemoteChanged() {
        val info = deviceInfo
        val volume = deviceVolume
        val muted = isDeviceMuted
        val commands = availableCommands
        val events = Player.Events(
            FlagSet.Builder().addAll(
                Player.EVENT_DEVICE_INFO_CHANGED,
                Player.EVENT_DEVICE_VOLUME_CHANGED,
                Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
            ).build(),
        )
        for ((l, _) in listeners) {
            l.onDeviceInfoChanged(info)
            l.onDeviceVolumeChanged(volume, muted)
            l.onAvailableCommandsChanged(commands)
            l.onEvents(this, events)
        }
    }

    /** The computer's volume changed. */
    internal fun notifyVolume() {
        val volume = deviceVolume
        val muted = isDeviceMuted
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_VOLUME_CHANGED).build())
        for ((l, _) in listeners) {
            l.onDeviceVolumeChanged(volume, muted)
            l.onEvents(this, events)
        }
    }

    /** The computer started or stopped, or the session began or ended: refresh everything that depends on it. */
    internal fun notifyPlayState() {
        val events = Player.Events(
            FlagSet.Builder().addAll(
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
            ).build(),
        )
        val pwr = playWhenReady
        val state = playbackState
        val playing = isPlaying
        for ((l, _) in listeners) {
            l.onPlayWhenReadyChanged(pwr, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            l.onPlaybackStateChanged(state)
            l.onIsPlayingChanged(playing)
            l.onEvents(this, events)
        }
    }

    /** The computer's position moved other than by playing on (a seek there, or catching up). */
    internal fun notifyPositionJump(fromMs: Long, toMs: Long) {
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return
        val index = exo.currentMediaItemIndex
        val window = timeline.getWindow(index, Timeline.Window())
        val periodIndex = exo.currentPeriodIndex
        val period = timeline.getPeriod(periodIndex, Timeline.Period(), true)
        fun info(pos: Long) = Player.PositionInfo(window.uid, index, window.mediaItem, period.uid, periodIndex, pos, pos, -1, -1)
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_POSITION_DISCONTINUITY).build())
        for ((l, _) in listeners) {
            l.onPositionDiscontinuity(info(fromMs), info(toMs), Player.DISCONTINUITY_REASON_SEEK)
            l.onEvents(this, events)
        }
    }

    /**
     * Passes the ExoPlayer's events on as this player's, except, while the
     * computer plays, the ones about the paused ExoPlayer's own playing state:
     * those would contradict the computer.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class Filtering(private val inner: Player.Listener) : Player.Listener {
        private val me: Player get() = this@ConnectPlayer

        override fun onEvents(player: Player, events: Player.Events) {
            if (remote && events.containsOnly(LOCAL_ONLY_EVENTS)) return
            inner.onEvents(me, events)
        }

        override fun onPlaybackStateChanged(playbackState: Int) { if (!remote) inner.onPlaybackStateChanged(playbackState) }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!remote) inner.onPlayWhenReadyChanged(playWhenReady, reason) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!remote) inner.onIsPlayingChanged(isPlaying) }
        override fun onIsLoadingChanged(isLoading: Boolean) { if (!remote) inner.onIsLoadingChanged(isLoading) }
        override fun onLoadingChanged(isLoading: Boolean) { if (!remote) inner.onLoadingChanged(isLoading) }
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) { if (!remote) inner.onPlayerStateChanged(playWhenReady, playbackState) }
        override fun onPlaybackSuppressionReasonChanged(reason: Int) { if (!remote) inner.onPlaybackSuppressionReasonChanged(reason) }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = inner.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = inner.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = inner.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = inner.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = inner.onPlaylistMetadataChanged(mediaMetadata)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = inner.onAvailableCommandsChanged(availableCommands)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = inner.onTrackSelectionParametersChanged(parameters)
        override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = inner.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = inner.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = inner.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = inner.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = inner.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = inner.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = inner.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = inner.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = inner.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = inner.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) { if (!remote) inner.onDeviceInfoChanged(deviceInfo) }
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) { if (!remote) inner.onDeviceVolumeChanged(volume, muted) }
        override fun onVideoSizeChanged(videoSize: VideoSize) = inner.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = inner.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
        override fun onCues(cues: List<androidx.media3.common.text.Cue>) = inner.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = inner.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = inner.onMetadata(metadata)

        override fun equals(other: Any?): Boolean = other is Filtering && other.inner == inner
        override fun hashCode(): Int = inner.hashCode()
    }

    private companion object {
        /** About the paused phone itself; while the computer plays they'd contradict it. */
        val LOCAL_ONLY_EVENTS = intArrayOf(
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_IS_LOADING_CHANGED,
            Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
            Player.EVENT_DEVICE_INFO_CHANGED,
            Player.EVENT_DEVICE_VOLUME_CHANGED,
        )

        const val VOLUME_STEPS = 25

        val REMOTE_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(VOLUME_STEPS)
            .build()

        @Suppress("DEPRECATION")
        val VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )

        fun Player.Events.containsOnly(allowed: IntArray): Boolean {
            for (i in 0 until size()) if (get(i) !in allowed) return false
            return true
        }
    }
}
