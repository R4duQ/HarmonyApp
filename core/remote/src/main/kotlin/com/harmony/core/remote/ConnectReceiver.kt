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
    /** The phone let go (it took the music back, or it's going away). */
    fun disconnect(phoneName: String?)
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
 */
class ConnectReceiver(
    private val info: () -> PcInfo,
    private val pairing: Pairing,
    private val renderer: RemoteRenderer,
    /** A phone has just paired or started playing, by name: for the computer's screen. */
    private val onPhone: (String) -> Unit = {},
) {
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
                when (request.path) {
                    Connect.PATH_STATUS -> HttpResponse.json(renderer.status().toJson())
                    Connect.PATH_PLAY -> {
                        val play = PlayRequest.fromJson(request.text) ?: return HttpResponse.text(400, "bad play request")
                        onPhone(phone)
                        renderer.play(play)
                        HttpResponse.json(renderer.status().toJson())
                    }
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
                    Connect.PATH_DISCONNECT -> {
                        renderer.disconnect(phone)
                        HttpResponse.empty()
                    }
                    else -> HttpResponse.text(404, "no such thing")
                }
            }
        }
    }
}
