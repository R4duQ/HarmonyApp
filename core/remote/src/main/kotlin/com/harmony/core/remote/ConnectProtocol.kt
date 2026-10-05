package com.harmony.core.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Harmony Connect: the phone plays its music on a computer running Harmony
 * for Windows, over the home Wi-Fi.
 *
 *  - **Finding the computer.** The phone broadcasts [DISCOVER_MESSAGE] on UDP
 *    [DISCOVERY_PORT]; every Harmony computer on the network answers with a
 *    [PcInfo].
 *  - **Pairing.** The computer shows a four-digit code. The phone sends it
 *    once ([PairRequest]) and gets a token back, which it keeps and sends
 *    with every request after that ([TOKEN_HEADER]).
 *  - **Playing.** The phone is the remote and keeps the queue; the computer
 *    is the speaker. The phone serves the song file over HTTP (with byte
 *    ranges, so the computer can seek) and tells the computer to play it
 *    ([PlayRequest]); play, pause and seek follow as [ControlRequest]s.
 *  - **Staying in step.** The phone reads the computer's [RemoteStatus] a
 *    couple of times a second: where it is in the song, whether it finished,
 *    and whether someone pressed next or previous on the computer.
 */
object Connect {
    const val VERSION = 1
    const val HTTP_PORT = 47800
    const val DISCOVERY_PORT = 47801
    const val DISCOVER_MESSAGE = "HARMONY-DISCOVER 1"
    const val TOKEN_HEADER = "X-Harmony-Token"

    const val PATH_INFO = "/info"
    const val PATH_PAIR = "/pair"
    const val PATH_PLAY = "/play"
    const val PATH_CONTROL = "/control"
    const val PATH_VOLUME = "/volume"
    const val PATH_STATUS = "/status"
    const val PATH_DISCONNECT = "/disconnect"
}

/** A Harmony computer, as it answers discovery and [Connect.PATH_INFO]. */
data class PcInfo(
    val id: String,
    val name: String,
    val port: Int,
    val version: Int = Connect.VERSION,
    /** Filled in by the phone from where the answer came from. */
    val host: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("type", TYPE)
        .put("id", id)
        .put("name", name)
        .put("port", port)
        .put("version", version)
        .toString()

    companion object {
        const val TYPE = "harmony-pc"

        /** Null when [json] isn't a Harmony computer's answer. */
        fun fromJson(json: String, host: String = ""): PcInfo? = runCatching {
            val o = JSONObject(json)
            if (o.optString("type") != TYPE) return null
            PcInfo(o.getString("id"), o.getString("name"), o.getInt("port"), o.optInt("version", 1), host)
        }.getOrNull()
    }
}

data class PairRequest(val code: String, val phoneName: String) {
    fun toJson(): String = JSONObject().put("code", code).put("phone", phoneName).toString()

    companion object {
        fun fromJson(json: String): PairRequest? = runCatching {
            val o = JSONObject(json)
            PairRequest(o.getString("code"), o.optString("phone", "Phone"))
        }.getOrNull()
    }
}

/** A song as the computer needs it: what to show, and where to fetch the audio. */
data class RemoteTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** The audio file, served by the phone, with ranges. */
    val url: String,
    /** The cover, served by the phone; null when the song has none. */
    val artUrl: String? = null,
    /** Lossless, bit depth, sample rate: shown as is ("FLAC 24/96"). */
    val quality: String? = null,
) {
    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationMs", durationMs)
        .put("url", url)
        .putOpt("artUrl", artUrl)
        .putOpt("quality", quality)

    companion object {
        fun fromJson(o: JSONObject): RemoteTrack = RemoteTrack(
            id = o.getString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            durationMs = o.optLong("durationMs"),
            url = o.getString("url"),
            artUrl = o.optString("artUrl").takeIf { it.isNotEmpty() },
            quality = o.optString("quality").takeIf { it.isNotEmpty() },
        )
    }
}

/** Play [track] from [positionMs]; [upNext] is shown on the computer, the phone still decides what comes next. */
data class PlayRequest(
    val track: RemoteTrack,
    val positionMs: Long,
    val playing: Boolean,
    val phoneName: String,
    val upNext: List<RemoteTrack> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("track", track.toJsonObject())
        .put("positionMs", positionMs)
        .put("playing", playing)
        .put("phone", phoneName)
        .put("upNext", JSONArray().apply { upNext.forEach { put(it.toJsonObject()) } })
        .toString()

    companion object {
        fun fromJson(json: String): PlayRequest? = runCatching {
            val o = JSONObject(json)
            val next = o.optJSONArray("upNext")
            PlayRequest(
                track = RemoteTrack.fromJson(o.getJSONObject("track")),
                positionMs = o.optLong("positionMs"),
                playing = o.optBoolean("playing", true),
                phoneName = o.optString("phone", "Phone"),
                upNext = if (next == null) emptyList() else List(next.length()) { RemoteTrack.fromJson(next.getJSONObject(it)) },
            )
        }.getOrNull()
    }
}

enum class ControlAction { PLAY, PAUSE, SEEK, STOP }

data class ControlRequest(val action: ControlAction, val positionMs: Long? = null) {
    fun toJson(): String = JSONObject().put("action", action.name.lowercase()).putOpt("positionMs", positionMs).toString()

    companion object {
        fun fromJson(json: String): ControlRequest? = runCatching {
            val o = JSONObject(json)
            val action = ControlAction.valueOf(o.getString("action").uppercase())
            ControlRequest(action, if (o.has("positionMs")) o.getLong("positionMs") else null)
        }.getOrNull()
    }
}

enum class RemoteState { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

/** Something pressed on the computer that the phone, which owns the queue, has to act on. */
enum class RemoteRequest { NEXT, PREVIOUS }

data class RemoteStatus(
    val state: RemoteState,
    val trackId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val error: String? = null,
    /** Pressed on the computer since the phone last asked. */
    val requests: List<RemoteRequest> = emptyList(),
) {
    val playing: Boolean get() = state == RemoteState.PLAYING || state == RemoteState.LOADING

    fun toJson(): String = JSONObject()
        .put("state", state.name.lowercase())
        .putOpt("trackId", trackId)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("volume", volume.toDouble())
        .putOpt("error", error)
        .put("requests", JSONArray().apply { requests.forEach { put(it.name.lowercase()) } })
        .toString()

    companion object {
        fun fromJson(json: String): RemoteStatus? = runCatching {
            val o = JSONObject(json)
            val req = o.optJSONArray("requests")
            RemoteStatus(
                state = RemoteState.valueOf(o.getString("state").uppercase()),
                trackId = o.optString("trackId").takeIf { it.isNotEmpty() },
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                volume = o.optDouble("volume", 1.0).toFloat(),
                error = o.optString("error").takeIf { it.isNotEmpty() },
                requests = if (req == null) emptyList() else List(req.length()) {
                    runCatching { RemoteRequest.valueOf(req.getString(it).uppercase()) }.getOrNull()
                }.filterNotNull(),
            )
        }.getOrNull()
    }
}

data class VolumeRequest(val volume: Float) {
    fun toJson(): String = JSONObject().put("volume", volume.toDouble()).toString()

    companion object {
        fun fromJson(json: String): VolumeRequest? = runCatching {
            VolumeRequest(JSONObject(json).getDouble("volume").toFloat().coerceIn(0f, 1f))
        }.getOrNull()
    }
}
