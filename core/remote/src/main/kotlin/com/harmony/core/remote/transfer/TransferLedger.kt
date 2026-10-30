package com.harmony.core.remote.transfer

import org.json.JSONObject
import java.io.File

/**
 * What the phone has saved from computers, kept across restarts: each
 * offer item's saved address, and each file's SHA-256 with its address. It is
 * what makes finishing an item idempotent: an item already saved is never
 * saved twice, and the same file sent again is recognised.
 */
class TransferLedger(private val file: File) {
    private val lock = Any()
    private val items = LinkedHashMap<String, String>()
    private val hashes = LinkedHashMap<String, String>()

    init {
        runCatching {
            if (file.isFile) {
                val o = JSONObject(file.readText())
                o.optJSONObject("items")?.let { m -> m.keys().forEach { items[it] = m.getString(it) } }
                o.optJSONObject("hashes")?.let { m -> m.keys().forEach { hashes[it] = m.getString(it) } }
            }
        }
    }

    fun savedItem(offerId: String, itemId: String): String? = synchronized(lock) { items["$offerId/$itemId"] }
    fun savedHash(sha256: String): String? = synchronized(lock) { hashes[sha256] }

    /** Written before the staging file goes: whatever happens next, the item counts as saved. */
    fun record(offerId: String, itemId: String, sha256: String, address: String) = synchronized(lock) {
        items["$offerId/$itemId"] = address
        hashes[sha256] = address
        while (items.size > MAX) items.remove(items.keys.first())
        while (hashes.size > MAX) hashes.remove(hashes.keys.first())
        save()
    }

    /** A saved file that is no longer there (deleted by the user): forgotten, so it can come again. */
    fun forget(address: String) = synchronized(lock) {
        val a = items.values.removeAll { it == address }
        val b = hashes.values.removeAll { it == address }
        if (a || b) save()
    }

    private fun save() {
        val text = JSONObject().put("items", JSONObject(items as Map<*, *>)).put("hashes", JSONObject(hashes as Map<*, *>)).toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val MAX = 20_000
    }
}
