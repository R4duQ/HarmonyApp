package com.harmony.core.remote

import java.security.SecureRandom
import java.util.UUID

/** What the computer does with what the phone asks: the player behind [ConnectReceiver]. */
interface RemoteRenderer {
    fun play(request: PlayRequest)
    fun control(request: ControlRequest)
    fun setVolume(volume: Float)
    /** Now, including anything pressed on the computer since the last call. */
    fun status(): RemoteStatus
    /** The phone let go (it took the music back, or it's going away, or it was lost). */
    fun disconnect(phoneName: String?)

    /**
     * Whether the phone of the current session answers ([PhoneLink.Connected]),
     * has gone quiet ([PhoneLink.Reconnecting]) or is gone ([PhoneLink.None]).
     */
    fun linkChanged(link: PhoneLink) {}
}

/**
 * Who may play on this computer. A phone pairs once with the four-digit
 * [code] on screen and gets a token; [tokens] are kept by the app between
 * runs (see [onTokensChanged]). The code changes after each pairing.
 */
class Pairing(initialTokens: Map<String, String> = emptyMap(), private val onTokensChanged: (Map<String, String>) -> Unit = {}) {
    private val random = SecureRandom()

    /** token -> phone name. */
    private val tokens = LinkedHashMap(initialTokens)

    @Volatile var code: String = newCode()
        private set

    val phones: Map<String, String> @Synchronized get() = LinkedHashMap(tokens)

    @Synchronized
    fun pair(request: PairRequest): String? {
        if (request.code.trim() != code) return null
        val token = UUID.randomUUID().toString()
        tokens[token] = request.phoneName
        code = newCode()
        onTokensChanged(LinkedHashMap(tokens))
        return token
    }

    @Synchronized
    fun phoneFor(token: String?): String? = token?.let { tokens[it] }

    @Synchronized
    fun forget(token: String) {
        if (tokens.remove(token) != null) onTokensChanged(LinkedHashMap(tokens))
    }

    @Synchronized
    fun forgetAll() {
        tokens.clear()
        code = newCode()
        onTokensChanged(emptyMap())
    }

    private fun newCode(): String = String.format("%04d", random.nextInt(10_000))
}

/**
 * The computer's Connect endpoints, routed to a [RemoteRenderer]. Everything
 * but [Connect.PATH_INFO] and [Connect.PATH_PAIR] needs a paired token.
 *
 * It also keeps the phone session ([PhoneSessions]): which phone plays here,
 * and whether it still answers. [tick] (about once a second) notices a phone
 * that has gone quiet. Requests and [tick] go one at a time under one lock, so
 * a late request can't slip in between a session ending and the next one.
 */
class ConnectReceiver(
    private val info: () -> PcInfo,
    private val pairing: Pairing,
    private val renderer: RemoteRenderer,
    /** A phone has just paired or started playing, by name: for the computer's screen. */
    private val onPhone: (String) -> Unit = {},
    /** The session's state, for the computer's screen. Called under the lock: keep it quick. */
    onLink: (PhoneLink) -> Unit = {},
    log: (String) -> Unit = {},
    suspectAfterMs: Long = PhoneSessions.SUSPECT_AFTER_MS,
    lostAfterMs: Long = PhoneSessions.LOST_AFTER_MS,
    /** Milliseconds, monotonic. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val sessions = PhoneSessions(suspectAfterMs, lostAfterMs, log) { link ->
        renderer.linkChanged(link)
        onLink(link)
    }

    val link: PhoneLink get() = synchronized(lock) { sessions.link }

    fun handle(request: HttpRequest): HttpResponse {
        val token = request.header(Connect.TOKEN_HEADER)
        return when (request.path) {
            Connect.PATH_INFO -> HttpResponse.json(info().toJson())
            Connect.PATH_PAIR -> {
                val pair = PairRequest.fromJson(request.text) ?: return HttpResponse.text(400, "bad pairing request")
                val issued = pairing.pair(pair) ?: return HttpResponse.text(403, "wrong code")
                onPhone(pair.phoneName)
                HttpResponse.json(org.json.JSONObject().put("token", issued).put("name", info().name).toString())
            }
            else -> {
                val phone = pairing.phoneFor(token) ?: return HttpResponse.text(403, "not paired")
                val phoneSession = request.header(Connect.SESSION_HEADER)?.trim()?.take(MAX_SESSION_ID)?.takeIf { it.isNotEmpty() }
                synchronized(lock) { paired(request, token!!, phoneSession, phone) }
            }
        }
    }

    private fun paired(request: HttpRequest, token: String, phoneSession: String?, phone: String): HttpResponse {
        val now = clock()
        if (request.path == Connect.PATH_PLAY) {
            val play = PlayRequest.fromJson(request.text) ?: return HttpResponse.text(400, "bad play request")
            if (play.followUp) {
                // A follow-up only catches up with a session; it never starts one over another.
                when (val v = sessions.check(token, phoneSession, now)) {
                    PhoneSessions.Verdict.Current -> Unit
                    PhoneSessions.Verdict.NoSession -> sessions.begin(token, phoneSession, phone, now)
                    is PhoneSessions.Verdict.Ended -> return ended(v.reason)
                }
            } else {
                sessions.begin(token, phoneSession, phone, now)
            }
            onPhone(phone)
            renderer.play(play)
            return HttpResponse.json(renderer.status().toJson())
        }
        val verdict = sessions.check(token, phoneSession, now)
        if (request.path == Connect.PATH_DISCONNECT) {
            // A late disconnect from an old session must not end the one after it.
            if (verdict is PhoneSessions.Verdict.Ended) return HttpResponse.empty()
            sessions.end(LinkEnd.DISCONNECTED_BY_PHONE, now)
            renderer.disconnect(phone)
            return HttpResponse.empty()
        }
        if (verdict is PhoneSessions.Verdict.Ended) {
            // A phone that let go itself already knows: reading the status changes nothing, so it
            // gets one. Anything that could start, stop or change something stays refused.
            val readOnly = request.path == Connect.PATH_STATUS && verdict.reason == LinkEnd.DISCONNECTED_BY_PHONE
            if (!readOnly) return ended(verdict.reason)
        }
        return when (request.path) {
            Connect.PATH_STATUS -> HttpResponse.json(renderer.status().toJson())
            Connect.PATH_CONTROL -> {
                val control = ControlRequest.fromJson(request.text) ?: return HttpResponse.text(400, "bad control request")
                renderer.control(control)
                HttpResponse.json(renderer.status().toJson())
            }
            Connect.PATH_VOLUME -> {
                val volume = VolumeRequest.fromJson(request.text) ?: return HttpResponse.text(400, "bad volume request")
                renderer.setVolume(volume.volume)
                HttpResponse.empty()
            }
            else -> HttpResponse.text(404, "no such thing")
        }
    }

    /** About once a second: a phone silent for too long is let go. */
    fun tick() = synchronized(lock) {
        val lost = sessions.tick(clock())
        if (lost != null) renderer.disconnect(lost)
    }

    /** Stop pressed on the computer: the session ends, and the phone is told when it next asks. */
    fun stopFromComputer() = synchronized(lock) {
        val phone = sessions.end(LinkEnd.STOPPED_ON_COMPUTER, clock())
        renderer.disconnect(phone)
    }

    /** The phone of [token] was forgotten: its session ends here too. */
    fun forgotten(token: String) = synchronized(lock) {
        val phone = sessions.endFor(token, LinkEnd.FORGOTTEN, clock()) ?: return@synchronized
        renderer.disconnect(phone)
    }

    private fun ended(reason: LinkEnd) = HttpResponse.text(Connect.SESSION_ENDED, reason.name.lowercase())

    private companion object {
        const val MAX_SESSION_ID = 64
    }
}
