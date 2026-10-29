package com.harmony.core.remote

/**
 * The phone session this computer is serving, as its screen shows it.
 *
 * [Connected] only after a request from that session has actually arrived:
 * the phone starts it with a play, and every status read (twice a second)
 * confirms it again. [Reconnecting] when those reads have stopped for a
 * while; the session still holds, the music carries on, and the phone's next
 * read makes it [Connected] again.
 */
sealed interface PhoneLink {
    data object None : PhoneLink

    data class Connected(val phone: String, val session: Long) : PhoneLink

    data class Reconnecting(val phone: String, val session: Long) : PhoneLink
}

/** Why a session ended. */
enum class LinkEnd {
    /** The phone said so ([Connect.PATH_DISCONNECT]). */
    DISCONNECTED_BY_PHONE,

    /** Stop pressed on the computer. */
    STOPPED_ON_COMPUTER,

    /** Another phone, or a new session of the same phone, started playing. */
    REPLACED,

    /** No word from the phone for [PhoneSessions.lostAfterMs]. */
    LOST,

    /** The phone's pairing was removed on the computer. */
    FORGOTTEN,
}

/**
 * Tracks the one phone session the computer serves, from what arrives over
 * Connect. The transport is the phone's requests: one HTTP request per
 * connection, so there is no socket whose closing says the phone has gone.
 * The phone reads [Connect.PATH_STATUS] every half second while it is
 * connected, which makes those reads the heartbeat: [tick] notices when they
 * stop.
 *
 * A session is a pairing token plus the session id the phone sends in
 * [Connect.SESSION_HEADER] (phones from before it send none: their token alone
 * is the session). Requests from a session that has ended, or that isn't the
 * current one, are answered [Verdict.Ended], so a late request from an old
 * connection can't take over again or stop the session after it.
 *
 * Not thread-safe by itself: [ConnectReceiver] calls it under its lock.
 * Times are milliseconds on a monotonic clock.
 */
class PhoneSessions(
    /**
     * Silence before the screen says "Reconnecting". The phone reads every
     * 0.5 s; one read that times out on the phone takes 4 s plus the 0.5 s
     * pause, so a single slow read never reaches this.
     */
    val suspectAfterMs: Long = SUSPECT_AFTER_MS,
    /**
     * Silence before the session ends. The phone gives up after 30 s of
     * failed reads and takes the music back; a little more here, so the
     * computer doesn't drop a session the phone still holds.
     */
    val lostAfterMs: Long = LOST_AFTER_MS,
    private val log: (String) -> Unit = {},
    private val onLink: (PhoneLink) -> Unit = {},
) {
    sealed interface Verdict {
        /** The current session: handle the request. */
        data object Current : Verdict

        /** No session, and none ended for this phone: handle it as before sessions existed. */
        data object NoSession : Verdict

        /** An old session, or not the current one: refuse it. */
        data class Ended(val reason: LinkEnd) : Verdict
    }

    private class Session(
        val id: Long,
        val token: String,
        val phoneSession: String?,
        val phone: String,
        var lastSeen: Long,
        var reconnecting: Boolean = false,
    ) {
        fun matches(token: String, phoneSession: String?) = this.token == token && this.phoneSession == phoneSession
    }

    private var current: Session? = null
    private var nextId = 1L
    private var lastTick = -1L

    /** Sessions ended lately, newest last: their late requests are refused. */
    private val ended = LinkedHashMap<String, LinkEnd>()

    var link: PhoneLink = PhoneLink.None
        private set

    /** The phone of the current session, or null. */
    val phone: String? get() = current?.phone

    /**
     * A play that starts something (not a follow-up). Starts a session unless
     * it comes from the current one; a session of another phone, or an older
     * one of the same phone, ends as [LinkEnd.REPLACED]. Returns the ended
     * session's phone when one was replaced.
     */
    fun begin(token: String, phoneSession: String?, phone: String, now: Long): String? {
        val active = current
        if (active != null && active.matches(token, phoneSession)) {
            seen(active, now)
            return null
        }
        val replaced = active?.let { end(it, LinkEnd.REPLACED, now) }
        ended.remove(key(token, phoneSession))
        val s = Session(nextId++, token, phoneSession, phone, now)
        current = s
        log("session ${s.id} started by ${label(s)}")
        publish(PhoneLink.Connected(phone, s.id))
        return replaced
    }

    /** Any other request from a paired phone: confirms the current session, or says it isn't one. */
    fun check(token: String, phoneSession: String?, now: Long): Verdict {
        val s = current
        if (s != null && s.matches(token, phoneSession)) {
            seen(s, now)
            return Verdict.Current
        }
        ended[key(token, phoneSession)]?.let { return Verdict.Ended(it) }
        // Another phone's request while one is playing here: it doesn't get a say.
        return if (s != null) Verdict.Ended(LinkEnd.REPLACED) else Verdict.NoSession
    }

    /** Ends the current session; returns its phone, or null when there was none. */
    fun end(reason: LinkEnd, now: Long): String? = current?.let { end(it, reason, now) }

    /** Ends the session of [token], if it is the current one (the phone was forgotten). */
    fun endFor(token: String, reason: LinkEnd, now: Long): String? =
        current?.takeIf { it.token == token }?.let { end(it, reason, now) }

    /**
     * Called about once a second. Returns the phone whose session was just
     * ended as [LinkEnd.LOST], or null.
     */
    fun tick(now: Long): String? {
        val s = current
        val gap = if (lastTick < 0) 0 else now - lastTick
        lastTick = now
        if (s == null) return null
        if (gap > SLEEP_GAP_MS) {
            // The computer slept (or the process stood still): the silence isn't the phone's.
            // Count from now, as if it had just gone quiet, so it gets the whole reconnect window.
            log("session ${s.id}: clock jumped ${gap} ms (sleep?); waiting for the phone again")
            s.lastSeen = maxOf(s.lastSeen, now - suspectAfterMs)
        }
        val silent = now - s.lastSeen
        if (silent >= lostAfterMs) {
            end(s, LinkEnd.LOST, now)
            return s.phone
        }
        if (silent >= suspectAfterMs && !s.reconnecting) {
            s.reconnecting = true
            log("session ${s.id}: no word for ${silent} ms -> reconnecting")
            publish(PhoneLink.Reconnecting(s.phone, s.id))
        }
        return null
    }

    private fun seen(s: Session, now: Long) {
        val silent = now - s.lastSeen
        s.lastSeen = now
        if (s.reconnecting) {
            s.reconnecting = false
            log("session ${s.id}: phone answered again after ${silent} ms -> connected")
            publish(PhoneLink.Connected(s.phone, s.id))
        }
    }

    private fun end(s: Session, reason: LinkEnd, now: Long): String {
        if (current === s) current = null
        ended[key(s.token, s.phoneSession)] = reason
        while (ended.size > MAX_ENDED) ended.remove(ended.keys.first())
        log("session ${s.id} ended: ${reason.name.lowercase()} (last word from the phone ${now - s.lastSeen} ms ago)")
        publish(PhoneLink.None)
        return s.phone
    }

    private fun publish(l: PhoneLink) {
        if (l == link) return
        link = l
        onLink(l)
    }

    private fun key(token: String, phoneSession: String?) = token + "|" + (phoneSession ?: "")

    /** For the log: never the token; the phone's own session id is random, so a few characters of it are safe. */
    private fun label(s: Session) = "a phone" + (s.phoneSession?.let { " (session ${it.take(8)})" } ?: " (no session id)")

    companion object {
        const val SUSPECT_AFTER_MS = 6_000L
        const val LOST_AFTER_MS = 35_000L
        /** A [tick] this late means the clock jumped, not that the phone fell silent. */
        const val SLEEP_GAP_MS = 10_000L
        private const val MAX_ENDED = 16
    }
}
