package com.harmony.playback.service.player

/**
 * One crossfade timed on a monotonic clock (elapsedRealtime), from the moment
 * the waiting player starts loading the next song to the moment listeners
 * (the session, so the notification and the screen) are told it plays.
 *
 * Each crossfade, whether it ended in a turn or was called off, becomes one
 * Logcat line under [TAG], so a run of natural transitions can be compared
 * line by line on a phone:
 *
 *     adb logcat -s HarmonyXfade
 *
 * What the numbers separate: [Report.loadToReadyMs] is the wait for the
 * source (open the file, first audio decoded, muted and paused);
 * [Report.dueToPlayingMs] and [Report.playingToSoundMs] are time lost in the
 * transition itself; [Report.overlapMs] is the overlap actually heard, to
 * compare with [Report.configuredMs]. A player's "ready" or "playing" alone
 * doesn't prove sound: the first sound is when its AudioTrack position starts
 * advancing (Media3's onAudioPositionAdvancing).
 */
internal class CrossfadeTrace(
    private val now: () -> Long,
    private val emit: (String) -> Unit,
) {

    data class Report(
        /** turn, gapless (the next song wasn't playing yet: the outgoing player went on into it), or off. */
        val outcome: String,
        val reason: String?,
        val configuredMs: Long,
        /** How far from the end of the song the next one started loading. */
        val loadAtRemainingMs: Long,
        /** Loading → decoded, ready to start. */
        val loadToReadyMs: Long?,
        /** How far from the end the fade was due. */
        val dueAtRemainingMs: Long?,
        /** Fade due → the incoming player playing. */
        val dueToPlayingMs: Long?,
        /** Incoming playing → its first sound (AudioTrack position advancing), when the device reported it. */
        val playingToSoundMs: Long?,
        /** First sound (or playing, without a report) → the outgoing song's end. */
        val overlapMs: Long?,
        /** The outgoing song's end → listeners told the next song plays. */
        val endToAnnouncedMs: Long?,
        val underrunsIncoming: Int,
        val underrunsOutgoing: Int,
        /** Times the outgoing song stopped sounding (rebuffered, flushed) during the fade. */
        val outgoingStalls: Int,
        /** The longest the main thread kept a fade step waiting beyond its slot. */
        val worstMainLagMs: Long,
    ) {
        fun line(): String = buildString {
            append("XFADE ").append(outcome)
            reason?.let { append(" (").append(it).append(')') }
            append(" cfg=").append(configuredMs).append("ms")
            append(" load@-").append(loadAtRemainingMs).append("ms")
            append(" load->ready=").append(ms(loadToReadyMs))
            append(" due@-").append(ms(dueAtRemainingMs))
            append(" due->playing=").append(ms(dueToPlayingMs))
            append(" playing->sound=").append(ms(playingToSoundMs))
            append(" overlap=").append(ms(overlapMs))
            append(" end->announced=").append(ms(endToAnnouncedMs))
            append(" underruns in/out=").append(underrunsIncoming).append('/').append(underrunsOutgoing)
            append(" outgoingStalls=").append(outgoingStalls)
            append(" mainLagMax=").append(worstMainLagMs).append("ms")
        }

        private fun ms(v: Long?) = if (v == null) "-" else "${v}ms"
    }

    /** The last report, for tests and diagnostics. */
    var last: Report? = null
        private set

    private var open = false
    private var configuredMs = 0L
    private var loadAt = 0L
    private var loadAtRemaining = 0L
    private var readyAt: Long? = null
    private var dueAt: Long? = null
    private var dueAtRemaining: Long? = null
    private var playingAt: Long? = null
    private var soundAt: Long? = null
    private var endedAt: Long? = null
    private var underrunsIn = 0
    private var underrunsOut = 0
    private var outgoingStalls = 0
    private var worstLag = 0L

    fun loading(remainingMs: Long, configuredMs: Long) {
        open = true
        this.configuredMs = configuredMs
        loadAt = now()
        loadAtRemaining = remainingMs
        readyAt = null
        dueAt = null
        dueAtRemaining = null
        playingAt = null
        soundAt = null
        endedAt = null
        underrunsIn = 0
        underrunsOut = 0
        outgoingStalls = 0
        worstLag = 0
    }

    fun ready() {
        if (open && readyAt == null) readyAt = now()
    }

    fun due(remainingMs: Long) {
        if (!open) return
        dueAt = now()
        dueAtRemaining = remainingMs
    }

    fun playing() {
        if (open && playingAt == null) playingAt = now()
    }

    /** [msAgo]: how long ago, by the device's report, the incoming player's first sample played out. */
    fun sound(msAgo: Long) {
        if (open && soundAt == null) soundAt = now() - msAgo.coerceAtLeast(0)
    }

    fun underrun(incoming: Boolean) {
        if (!open) return
        if (incoming) underrunsIn++ else underrunsOut++
    }

    fun outgoingStalled() {
        if (open) outgoingStalls++
    }

    fun mainLag(ms: Long) {
        if (open && ms > worstLag) worstLag = ms
    }

    fun ended() {
        if (open && endedAt == null) endedAt = now()
    }

    /** The turn: listeners have just been told the next song plays. */
    fun announced() = finish("turn", null)

    /** The incoming player wasn't playing when the song ended: the outgoing one went on into it, gaplessly. */
    fun gapless() = finish("gapless", "next song not playing in time")

    fun off(reason: String) = finish("off", reason)

    private fun finish(outcome: String, reason: String?) {
        if (!open) return
        open = false
        val end = endedAt
        val start = soundAt ?: playingAt
        val report = Report(
            outcome = outcome,
            reason = reason,
            configuredMs = configuredMs,
            loadAtRemainingMs = loadAtRemaining,
            loadToReadyMs = readyAt?.let { it - loadAt },
            dueAtRemainingMs = dueAtRemaining,
            dueToPlayingMs = playingAt?.let { p -> dueAt?.let { p - it } },
            playingToSoundMs = soundAt?.let { s -> playingAt?.let { s - it } },
            overlapMs = if (end != null && start != null) (end - start).coerceAtLeast(0) else null,
            endToAnnouncedMs = if (outcome == "turn" && end != null) now() - end else null,
            underrunsIncoming = underrunsIn,
            underrunsOutgoing = underrunsOut,
            outgoingStalls = outgoingStalls,
            worstMainLagMs = worstLag,
        )
        last = report
        emit(report.line())
    }

    companion object {
        const val TAG = "HarmonyXfade"
    }
}
