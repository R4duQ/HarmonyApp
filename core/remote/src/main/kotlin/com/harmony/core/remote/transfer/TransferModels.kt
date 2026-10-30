package com.harmony.core.remote.transfer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sending songs from a computer to a paired phone for good ("Send to phone").
 * Not Harmony Connect playback, not the Universal Queue: the original file is
 * copied, checked and put in the phone's library.
 *
 * The computer offers ([TransferOffer], one per send, to one phone); the phone
 * asks for offers, takes each file with ranges ([TransferPaths.file]), checks
 * size and SHA-256, saves it, and reports each item's state back.
 */
object TransferPaths {
    /** GET: the offers waiting for the asking phone. */
    const val OFFERS = "/transfer/offers"
    /** GET `/transfer/offer/<offer>`: one offer, as it is now. */
    const val OFFER = "/transfer/offer/"
    /** GET `/transfer/file/<offer>/<item>`: the item's bytes (Range, If-Range). */
    const val FILE = "/transfer/file/"
    /** POST `/transfer/report/<offer>`: item states ([ItemReport]). */
    const val REPORT = "/transfer/report/"
    const val PREFIX = "/transfer/"
}

/** Where an item is. The phone moves it along; the computer only cancels or retries. */
enum class ItemState {
    PENDING, TRANSFERRING, INTERRUPTED, VERIFYING, IMPORTING, DONE, CANCELLED, FAILED;

    val final: Boolean get() = this == DONE || this == CANCELLED || this == FAILED
}

/** Why an item failed. */
enum class FailReason { NO_SPACE, DISCONNECTED, PERMISSION, SOURCE_MISSING, CHECKSUM, SOURCE_CHANGED, DECLINED, UNSUPPORTED, OTHER }

/** What else is worth knowing about a finished item. */
enum class ItemNote { NONE, ALREADY_ON_PHONE, IN_LIBRARY, UNSUPPORTED_FORMAT }

/** One file of an offer, as the phone needs to know it. */
data class TransferItem(
    val id: String,
    /** The file name to save it under (no folders). */
    val name: String,
    val sizeBytes: Long,
    /** SHA-256 of the whole file, lower-case hex. */
    val sha256: String,
    /** The library's identity hash (core.model.PartialHash), to spot a song the phone already has. */
    val libraryHash: String,
    val mime: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Whether the phone's player reads this format by itself. */
    val playableOnPhone: Boolean,
    /** Bumped each time the file changed on the computer (a new hash); part of the [etag]. */
    val version: Int = 1,
) {
    /** The validator the file is served with: another version never resumes a part of an older one. */
    val etag: String get() = "\"${sha256.take(32)}-v$version\""

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("size", sizeBytes).put("sha256", sha256).put("libraryHash", libraryHash)
        .put("mime", mime).put("title", title).put("artist", artist).put("album", album)
        .put("durationMs", durationMs).put("playable", playableOnPhone).put("version", version)

    companion object {
        fun fromJson(o: JSONObject) = TransferItem(
            id = o.getString("id"), name = o.getString("name"), sizeBytes = o.getLong("size"),
            sha256 = o.getString("sha256"), libraryHash = o.optString("libraryHash"), mime = o.optString("mime"),
            title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album"),
            durationMs = o.optLong("durationMs"), playableOnPhone = o.optBoolean("playable", true),
            version = o.optInt("version", 1),
        )
    }
}

/** An item's state as the computer knows it (the phone's last report). */
data class ItemStatus(
    val state: ItemState = ItemState.PENDING,
    /** Bytes the phone has of it. */
    val bytes: Long = 0,
    /** Counts the computer's retries: a report for an older attempt is stale. */
    val attempt: Int = 1,
    val reason: FailReason? = null,
    val note: ItemNote = ItemNote.NONE,
    val message: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state.name).put("bytes", bytes).put("attempt", attempt)
        .putOpt("reason", reason?.name).put("note", note.name).putOpt("message", message)

    companion object {
        fun fromJson(o: JSONObject) = ItemStatus(
            state = enumOr(o.optString("state"), ItemState.PENDING),
            bytes = o.optLong("bytes"),
            attempt = o.optInt("attempt", 1),
            reason = o.optString("reason").takeIf { it.isNotEmpty() }?.let { enumOr(it, FailReason.OTHER) },
            note = enumOr(o.optString("note"), ItemNote.NONE),
            message = o.optString("message").takeIf { it.isNotEmpty() },
        )
    }
}

/** What the computer offers one phone in one send. */
data class TransferOffer(
    val id: String,
    /** The computer's id and name, for the phone's prompt and preferences. */
    val fromId: String,
    val fromName: String,
    val createdAt: Long,
    val items: List<TransferItem>,
    /** The computer's view of each item, by item id. */
    val status: Map<String, ItemStatus> = emptyMap(),
    val cancelled: Boolean = false,
) {
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
    fun statusOf(itemId: String): ItemStatus = status[itemId] ?: ItemStatus()

    /** Items the phone still has to do something about. */
    val open: List<TransferItem> get() = if (cancelled) emptyList() else items.filter { !statusOf(it.id).state.final }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("fromId", fromId).put("fromName", fromName).put("createdAt", createdAt)
        .put("cancelled", cancelled)
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        .put("status", JSONObject().apply { status.forEach { (k, v) -> put(k, v.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): TransferOffer {
            val items = o.getJSONArray("items")
            val status = o.optJSONObject("status") ?: JSONObject()
            return TransferOffer(
                id = o.getString("id"), fromId = o.optString("fromId"), fromName = o.optString("fromName"),
                createdAt = o.optLong("createdAt"),
                items = List(items.length()) { TransferItem.fromJson(items.getJSONObject(it)) },
                status = status.keys().asSequence().associateWith { ItemStatus.fromJson(status.getJSONObject(it)) },
                cancelled = o.optBoolean("cancelled"),
            )
        }
    }
}

/** The phone's word on one item. */
data class ItemReport(val itemId: String, val status: ItemStatus) {
    fun toJson(): JSONObject = status.toJson().put("item", itemId)

    companion object {
        fun fromJson(o: JSONObject) = ItemReport(o.getString("item"), ItemStatus.fromJson(o))
        fun listToJson(reports: List<ItemReport>): String =
            JSONObject().put("reports", JSONArray().apply { reports.forEach { put(it.toJson()) } }).toString()
        fun listFromJson(text: String): List<ItemReport>? = runCatching {
            val a = JSONObject(text).getJSONArray("reports")
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.getOrNull()
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(fallback)
