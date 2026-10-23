package com.harmony.playback.service.player

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import com.harmony.core.eq.AudioOutputMonitor
import com.harmony.core.eq.CustomEqAudioProcessor
import com.harmony.core.eq.HiResEqAudioSink
import com.harmony.core.eq.OutputDeviceType
import com.harmony.core.eq.OutputRoute
import com.harmony.core.media.artwork.ArtworkCache
import com.harmony.core.media.artwork.ArtworkProvider
import com.harmony.core.model.EqAuto
import com.harmony.core.model.EqAutoCurve
import com.harmony.core.model.EqSettings
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.Song
import com.harmony.domain.playback.AudioLevels
import com.harmony.domain.playback.EqLive
import com.harmony.domain.playback.EqOutput
import com.harmony.domain.playback.SpectrumSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Owner of the two [ExoPlayer]s behind [player] and everything that must be
 * configured at construction time (audio sink, renderers, attributes).
 *
 * Responsibilities:
 *  - Gapless playback: ExoPlayer plays adjacent items in a playlist gaplessly
 *    by default for formats carrying gapless metadata (MP3 LAME/iTunes tags,
 *    AAC) and inherently sample-accurate formats (FLAC/WAV/ALAC). We keep a
 *    single playlist-based queue (never one-item-at-a-time) so this works.
 *  - Speed & pitch: [setSpeed]. With pitch correction ON we set pitch=1.0 and
 *    only vary speed. With correction OFF we set pitch=speed for a "tape
 *    speed" effect. (On the EQ's float path AudioTrack applies both.)
 *  - The equalizer: the native float engine from core:eq-engine
 *    ([CustomEqAudioProcessor]) running in a [HiResEqAudioSink], so 24-bit and
 *    hi-res files reach it whole. It also carries ReplayGain (as a level gain
 *    ahead of its limiter), Auto (it listens to each song, see [EqAuto]), the
 *    output-device protection ([AudioOutputMonitor]) and the analyser the
 *    screens poll through [AudioLevels].
 *  - Crossfade: two players taking turns, see [TwinPlayer]. Each has its own
 *    engine, so the song coming in sounds exactly as it will once it plays
 *    alone; only the one playing reports to the screen.
 *
 * This class deliberately does NOT know about MediaSession, notifications,
 * Android Auto, or Smart Shuffle. It is a pure audio engine.
 */
@OptIn(UnstableApi::class)
class HarmonyPlayer(
    private val context: Context,
    private val artworkCache: ArtworkCache,
) {

    /** One ExoPlayer and the equalizer engine in its sink. */
    private class Engine(
        val exo: ExoPlayer,
        val equalizer: CustomEqAudioProcessor,
    ) {
        /** The song Auto is listening to on this engine, and whether it was heard before. */
        var songId: String? = null
        var remembered = false
    }

    private val engines: List<Engine>

    /** The player everything uses: the session, the queue, the settings. */
    val player: TwinPlayer

    private var pitchCorrection: Boolean = true
    private var replayGainMode: ReplayGainMode = ReplayGainMode.OFF
    private var eq = EqSettings()

    /**
     * What Auto heard of each recently played song (octave-band levels), so a
     * song played again is corrected from its first second. Shared by both
     * engines; main thread only.
     */
    private val toneMemory = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > TONE_MEMORY_SONGS
    }

    /** Song metadata for the current queue, keyed by mediaId, for RG lookup. */
    private val songsById = mutableMapOf<String, Song>()

    /** Error-recovery budgets. Touched only on the player's application thread. */
    private var retriesForCurrentItem = 0
    private var failedItemsInARow = 0

    /** Follows the output (speaker, wired, Bluetooth, USB DAC) for the engines. */
    private val outputMonitor: AudioOutputMonitor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        engines = listOf(buildEngine(handleAudioFocus = true), buildEngine(handleAudioFocus = false))
        player = TwinPlayer(engines[0].exo, engines[1].exo)

        for (engine in engines) {
            // Each player sets its own song's level and starts Auto listening
            // to it, the waiting one too when it loads the next song, so it is
            // ready before it is heard.
            engine.exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    engine.equalizer.setLevelGainDb(replayGainDbFor(engine.exo.currentMediaItem))
                    startAutoTrack(engine, mediaItem?.mediaId)
                }
            })
        }

        outputMonitor = AudioOutputMonitor(
            context,
            onBitPerfectChanged = ::onBitPerfectChanged,
            onRouteChanged = ::onRouteChanged,
        )
        outputMonitor.start()
        onRouteChanged(outputMonitor.route.value)
        startAnalyser()
        startEqStatus()

        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                // A MediaController changes speed directly on the player.
                // Keep its pitch consistent without resetting that speed.
                applyPlaybackParameters()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A new item gets a fresh retry budget. Retrying the SAME
                // item calls prepare() without a transition, so the counter
                // only resets when we genuinely move on.
                retriesForCurrentItem = 0
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Audio is flowing again, so whatever went wrong is behind
                // us and the queue-level budget can be handed back.
                if (playbackState == Player.STATE_READY) failedItemsInARow = 0
            }

            override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) = outputMonitor.refresh()

            override fun onPlayerError(error: PlaybackException) = recoverFrom(error)
        })
    }

    private fun buildEngine(handleAudioFocus: Boolean): Engine {
        val equalizer = CustomEqAudioProcessor().apply {
            setSpectrumBarCount(AudioLevels.SPECTRUM_BARS)
            setAnalyzerEnabled(false)
            setEqEnabled(false)
        }
        val exo = ExoPlayer.Builder(context, renderersFactory(equalizer))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        return Engine(exo, equalizer)
    }

    private val activeEngine: Engine get() = engines.firstOrNull { it.exo === player.active } ?: engines[0]

    /**
     * Puts the player back to work after an error instead of leaving it dead.
     *
     * Without this the player parked in STATE_IDLE the moment anything went
     * wrong — a bad frame in a FLAC, a decoder giving up, a file replaced
     * underneath us by a download, an audio-sink write failing across a
     * Bluetooth route change. Nothing ever called prepare() again, so the
     * song simply stopped. Media3 then dropped the media notification, the
     * service left the foreground, and Android reclaimed the process: from
     * the outside, the app "closed" on its own.
     *
     * Recovery is deliberately budgeted in two directions. One retry of the
     * same item handles the transient case (route changes, a momentary sink
     * failure) where re-preparing at the same position is exactly right.
     * Past that the item is treated as genuinely broken and skipped. And if
     * items keep failing one after another we stop rather than sprint
     * through the whole queue burning CPU on a problem that clearly isn't
     * per-file — a queue of unreadable files should go quiet, not spin.
     */
    private fun recoverFrom(error: PlaybackException) {
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0)
        Log.e(
            TAG,
            "Playback error on item $index (${error.errorCodeName}); " +
                "retries=$retriesForCurrentItem failedInARow=$failedItemsInARow",
            error,
        )

        if (failedItemsInARow >= MAX_FAILED_ITEMS_IN_A_ROW) {
            Log.e(TAG, "Giving up: $failedItemsInARow items failed in a row.")
            return
        }

        if (retriesForCurrentItem < MAX_RETRIES_PER_ITEM) {
            retriesForCurrentItem++
            // Seeking is legal in STATE_IDLE; the position is applied when
            // prepare() re-acquires the source, so a transient failure
            // resumes where it stopped rather than at the top of the track.
            player.seekTo(index, position)
            player.prepare()
            return
        }

        retriesForCurrentItem = 0
        failedItemsInARow++
        if (player.hasNextMediaItem()) {
            Log.w(TAG, "Item $index unplayable after $MAX_RETRIES_PER_ITEM retries; skipping.")
            player.seekToNextMediaItem()
            player.prepare()
        } else {
            Log.e(TAG, "Item $index unplayable and nothing follows it; stopping.")
        }
    }

    // -- Queue ---------------------------------------------------------------

    fun setQueue(songs: List<Song>, startIndex: Int, playWhenReady: Boolean) {
        songsById.clear()
        songs.forEach { songsById[it.id.toString()] = it }
        player.setMediaItems(songs.map { it.toMediaItem() }, startIndex, C.TIME_UNSET)
        player.playWhenReady = playWhenReady
        player.prepare()
        applyReplayGainForCurrentItem()
    }

    fun addToQueue(song: Song) {
        songsById[song.id.toString()] = song
        player.addMediaItem(song.toMediaItem())
    }

    fun addNext(song: Song) {
        songsById[song.id.toString()] = song
        val insertAt = (player.currentMediaItemIndex + 1)
            .coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, song.toMediaItem())
    }

    // -- Speed / pitch -------------------------------------------------------

    fun setSpeed(newSpeed: Float) {
        val speed = newSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3.0f) ?: 1f
        player.playbackParameters = PlaybackParameters(speed, if (pitchCorrection) 1f else speed)
    }

    fun setPitchCorrection(enabled: Boolean) {
        pitchCorrection = enabled
        applyPlaybackParameters()
    }

    private fun applyPlaybackParameters() {
        val speed = player.playbackParameters.speed
        val pitch = if (pitchCorrection) 1.0f else speed
        if (player.playbackParameters.pitch != pitch) {
            player.playbackParameters = PlaybackParameters(speed, pitch)
        }
    }

    // -- ReplayGain ----------------------------------------------------------

    fun setReplayGainMode(mode: ReplayGainMode) {
        replayGainMode = mode
        applyReplayGainForCurrentItem()
    }

    private fun applyReplayGainForCurrentItem() {
        for (engine in engines) engine.equalizer.setLevelGainDb(replayGainDbFor(engine.exo.currentMediaItem))
    }

    private fun replayGainDbFor(item: MediaItem?): Float {
        val song = item?.mediaId?.let(songsById::get)
        val extras = item?.mediaMetadata?.extras
        val track = ReplayGainMetadata.gain(extras, ReplayGainMetadata.TRACK) ?: song?.replayGainTrackDb
        val album = ReplayGainMetadata.gain(extras, ReplayGainMetadata.ALBUM) ?: song?.replayGainAlbumDb
        val gainDb = when (replayGainMode) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> track
            ReplayGainMode.ALBUM -> album ?: track
        }
        return gainDb ?: 0f
    }

    // -- Equalizer -------------------------------------------------------------

    fun setEqualizer(settings: EqSettings) {
        val before = eq
        eq = settings
        val gains = EqSettings.sanitize(settings.bandGainsDb).toFloatArray()
        for (engine in engines) {
            engine.equalizer.setBandGains(gains)
            // Under Auto the listener's curve stays the base; listening adds to it.
            engine.equalizer.setAutoTargetGains(gains)
            engine.equalizer.setPreampDb(settings.preampDb)
            engine.equalizer.setEqEnabled(settings.enabled)
            engine.equalizer.setAutoMode(settings.enabled && settings.auto)
        }
        if (settings.bitPerfectUsb != before.bitPerfectUsb) applyBitPerfect()
    }

    /**
     * [engine] moved to song [id]: remember what Auto heard of the last one,
     * and have it listen to this one, from what it learned before if it has
     * heard it.
     */
    private fun startAutoTrack(engine: Engine, id: String?) {
        val levels = FloatArray(EqSettings.BAND_COUNT)
        val heard = engine.equalizer.songLevels(levels)
        val previous = engine.songId
        if (previous != null && heard >= EqAuto.REMEMBER_AFTER_SECONDS && levels.any { it.isFinite() }) {
            toneMemory[previous] = levels
        }
        val known = id?.let(toneMemory::get)
        engine.songId = id
        engine.remembered = known != null
        engine.equalizer.startTrack(known)
    }

    /** Shows the screen what Auto plays for the song playing, a few times a second. */
    private fun startEqStatus() {
        scope.launch {
            val curve = FloatArray(EqSettings.BAND_COUNT)
            val correction = FloatArray(EqSettings.BAND_COUNT)
            val levels = FloatArray(EqSettings.BAND_COUNT)
            while (true) {
                val engine = activeEngine
                val songId = player.active.currentMediaItem?.mediaId?.toLongOrNull()
                val auto = if (eq.enabled && eq.auto && songId != null) {
                    engine.equalizer.autoCurve(curve)
                    engine.equalizer.listenCorrection(correction)
                    val heard = engine.equalizer.songLevels(levels)
                    EqAutoCurve(
                        gainsDb = curve.map { (it * 10f).roundToInt() / 10f },
                        correctionDb = correction.map { (it * 10f).roundToInt() / 10f },
                        heardSeconds = heard.toInt().toFloat(),
                        remembered = engine.remembered,
                    )
                } else {
                    null
                }
                EqLive.update { it.copy(songId = songId, autoCurve = auto) }
                delay(STATUS_EVERY_MS)
            }
        }
    }

    // -- Output routing and bit-perfect USB -------------------------------------

    private fun onRouteChanged(route: OutputRoute) {
        for (engine in engines) engine.equalizer.setOutputDeviceType(route.type)
        EqLive.update {
            it.copy(
                output = when (route.type) {
                    OutputDeviceType.PHONE_SPEAKER -> EqOutput.PHONE_SPEAKER
                    OutputDeviceType.WIRED_HEADPHONES -> EqOutput.WIRED_HEADPHONES
                    OutputDeviceType.BLUETOOTH_A2DP -> EqOutput.BLUETOOTH
                    OutputDeviceType.USB_DAC_HIRES -> EqOutput.USB_DAC
                },
                outputName = route.name,
                bitPerfectAvailable = route.type == OutputDeviceType.USB_DAC_HIRES &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            )
        }
        applyBitPerfect()
    }

    /** Asks for bit-perfect USB when the listener wants it and a DAC is there; lets it go otherwise. */
    private fun applyBitPerfect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val want = eq.bitPerfectUsb && outputMonitor.route.value.type == OutputDeviceType.USB_DAC_HIRES
        if (want == outputMonitor.bitPerfectActive.value) return
        val result = runCatching { outputMonitor.setBitPerfectUsbMode(want) }
        Log.i(TAG, "Bit-perfect USB ${if (want) "on" else "off"}: ${result.getOrElse { it.toString() }}")
    }

    /** Bit-perfect switched: every engine steps aside (or back in), from a fresh AudioTrack. */
    private fun onBitPerfectChanged(active: Boolean) {
        for (engine in engines) engine.equalizer.bitPerfectPassthrough = active
        EqLive.update { it.copy(bitPerfect = active) }
        // The sink picks the path when it is configured: rebuild it where the song is.
        if (player.playbackState != Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.stop()
            player.prepare()
        }
    }

    // -- Analyser ----------------------------------------------------------------

    /**
     * Hands the screens the playing engine's analyser. They poll it once per
     * frame on the main thread (AudioLevels.poll); the engines only feed it
     * while a screen is watching.
     */
    private fun startAnalyser() {
        AudioLevels.source = SpectrumSource { bars, peaks -> activeEngine.equalizer.pollSpectrum(bars, peaks) }
        scope.launch {
            AudioLevels.watchers.collect { count ->
                for (engine in engines) engine.equalizer.setAnalyzerEnabled(count > 0)
            }
        }
    }

    private fun renderersFactory(equalizer: CustomEqAudioProcessor): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                // The EQ engine runs ahead of a float-output DefaultAudioSink:
                // Media3's own sink skips custom processors with float output,
                // and truncates to 16 bits before them without it.
                HiResEqAudioSink.create(context, equalizer)
        }.apply {
            // Prefer Media3's own decoders (FLAC, Opus, etc. via extensions if
            // present) before falling back to MediaCodec, for consistent
            // gapless behaviour across OEM codec implementations.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

    fun release() {
        scope.cancel()
        AudioLevels.source = null
        outputMonitor.stop()
        player.release()
        // The players are released, so their audio threads no longer touch the engines.
        for (engine in engines) engine.equalizer.release()
    }

    /**
     * These are the items the player actually holds, so this metadata is
     * what every controller sees — the in-app UI, the notification, and the
     * Android Auto host.
     *
     * Artwork therefore cannot use `artworkUri` directly: that's a file://
     * path into this app's private storage. Coil reads it fine in-process,
     * which is why the phone looks right, but every out-of-process
     * controller sees an unreadable path and renders a blank square.
     *
     * So artwork goes out two ways at once. A content:// URI from
     * ArtworkProvider, which any process can resolve; and the bytes
     * themselves, which travel inside the binder transaction and need no
     * permission at all. Controllers take whichever they prefer, and the
     * bytes make the common case work even where the URI is refused.
     *
     * Bytes are scaled and attached per item because a queue is set one
     * item at a time here, not marshalled as one giant list — so the
     * transaction limit that rules bytes out for browse lists doesn't
     * apply.
     */
    private fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setExtras(ReplayGainMetadata.from(this))
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setArtworkUri(outOfProcessArtworkUri(this))
                .setArtworkData(
                    artworkUri?.let { artworkCache.bytes(id, userArtwork = it.endsWith("-user.img")) },
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                )
                .setTrackNumber(trackNumber)
                .setDiscNumber(discNumber)
                .build(),
        )
        .build()

    private fun outOfProcessArtworkUri(song: Song): android.net.Uri? = when {
        song.artworkUri != null ->
            android.net.Uri.parse(ArtworkProvider.artUri(song.id, song.artworkUri))
        // No embedded art extracted, but MediaStore may still hold a cover
        // for the album — and that one is public.
        song.albumId > 0 -> android.net.Uri.parse(
            "content://media/external/audio/albumart/${song.albumId}"
        )
        else -> null
    }

    private companion object {
        const val TAG = "HarmonyPlayback"
        const val STATUS_EVERY_MS = 250L
        const val TONE_MEMORY_SONGS = 2_000
        const val MAX_RETRIES_PER_ITEM = 1
        const val MAX_FAILED_ITEMS_IN_A_ROW = 3
    }
}
