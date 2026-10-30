package com.harmony.core.remote.queue

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Universal Queue on the wire, as JSON (org.json: Android has it, the
 * Windows app bundles it). Readers ignore fields they don't know and refuse
 * what they can't read (null), so a newer device can talk to an older one.
 */
object QueueJson {
    fun media(m: MediaRef): JSONObject = JSONObject()
        .put("key", m.contentKey).put("home", m.home).put("id", m.localId)
        .put("title", m.title).put("artist", m.artist).put("album", m.album).put("durationMs", m.durationMs)

    fun media(o: JSONObject): MediaRef = MediaRef(
        contentKey = o.getString("key"), home = o.getString("home"), localId = o.getString("id"),
        title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album"),
        durationMs = o.optLong("durationMs"),
    )

    fun entry(e: QueueEntry): JSONObject = JSONObject()
        .put("id", e.id).put("media", media(e.media)).put("origin", e.origin.name.lowercase())
        .put("pinned", e.pinned).put("priority", e.priority)

    fun entry(o: JSONObject): QueueEntry = QueueEntry(
        id = o.getString("id"),
        media = media(o.getJSONObject("media")),
        origin = Origin.valueOf(o.getString("origin").uppercase()),
        pinned = o.optBoolean("pinned"),
        priority = o.optBoolean("priority"),
    )

    private fun entries(list: List<QueueEntry>) = JSONArray().apply { list.forEach { put(entry(it)) } }
    private fun entries(a: JSONArray) = List(a.length()) { entry(a.getJSONObject(it)) }
    private fun ids(list: List<String>) = JSONArray().apply { list.forEach { put(it) } }
    private fun ids(a: JSONArray) = List(a.length()) { a.getString(it) }

    fun state(s: QueueState): JSONObject = JSONObject()
        .put("entries", entries(s.entries)).putOpt("current", s.currentId)
        .put("shuffle", s.shuffle).put("repeat", s.repeat.name.lowercase())
        .putOpt("unshuffled", s.unshuffled?.let(::ids)).putOpt("owner", s.owner).put("epoch", s.epoch)

    fun state(o: JSONObject): QueueState = QueueState(
        entries = entries(o.getJSONArray("entries")),
        currentId = o.optString("current").takeIf { it.isNotEmpty() },
        shuffle = o.optBoolean("shuffle"),
        repeat = QueueRepeat.valueOf(o.optString("repeat", "off").uppercase()),
        unshuffled = o.optJSONArray("unshuffled")?.let(::ids),
        owner = o.optString("owner").takeIf { it.isNotEmpty() },
        epoch = o.optLong("epoch"),
    )

    fun op(op: QueueOp): JSONObject = when (op) {
        is QueueOp.PlayNext -> JSONObject().put("op", "playNext").put("entries", entries(op.entries))
        is QueueOp.Add -> JSONObject().put("op", "add").put("entries", entries(op.entries))
        is QueueOp.AppendAuto -> JSONObject().put("op", "appendAuto").put("entries", entries(op.entries))
        is QueueOp.Merge -> JSONObject().put("op", "merge").put("entries", entries(op.entries))
        is QueueOp.StartList -> JSONObject().put("op", "startList").put("entries", entries(op.entries))
            .put("start", op.startId).putOpt("order", op.order?.let(::ids))
        is QueueOp.Move -> JSONObject().put("op", "move").put("entry", op.entryId).putOpt("after", op.afterId)
        is QueueOp.Remove -> JSONObject().put("op", "remove").put("ids", ids(op.ids))
        is QueueOp.SetCurrent -> JSONObject().put("op", "setCurrent").put("entry", op.entryId)
        is QueueOp.SetShuffle -> JSONObject().put("op", "setShuffle").put("on", op.on).putOpt("order", op.order?.let(::ids))
        is QueueOp.SetRepeat -> JSONObject().put("op", "setRepeat").put("mode", op.mode.name.lowercase())
        is QueueOp.SetOwner -> JSONObject().put("op", "setOwner").put("device", op.device)
    }

    /** Null for an op this version doesn't know. */
    fun op(o: JSONObject): QueueOp? = runCatching {
        when (o.getString("op")) {
            "playNext" -> QueueOp.PlayNext(entries(o.getJSONArray("entries")))
            "add" -> QueueOp.Add(entries(o.getJSONArray("entries")))
            "appendAuto" -> QueueOp.AppendAuto(entries(o.getJSONArray("entries")))
            "merge" -> QueueOp.Merge(entries(o.getJSONArray("entries")))
            "startList" -> QueueOp.StartList(entries(o.getJSONArray("entries")), o.getString("start"), o.optJSONArray("order")?.let(::ids))
            "move" -> QueueOp.Move(o.getString("entry"), o.optString("after").takeIf { it.isNotEmpty() })
            "remove" -> QueueOp.Remove(ids(o.getJSONArray("ids")))
            "setCurrent" -> QueueOp.SetCurrent(o.getString("entry"))
            "setShuffle" -> QueueOp.SetShuffle(o.getBoolean("on"), o.optJSONArray("order")?.let(::ids))
            "setRepeat" -> QueueOp.SetRepeat(QueueRepeat.valueOf(o.getString("mode").uppercase()))
            "setOwner" -> QueueOp.SetOwner(o.getString("device"))
            else -> null
        }
    }.getOrNull()

    fun command(c: QueueCommand): JSONObject = JSONObject()
        .put("id", c.id).put("device", c.device).put("epoch", c.epoch).put("change", op(c.op))

    fun command(o: JSONObject): QueueCommand? = runCatching {
        val op = op(o.getJSONObject("change")) ?: return null
        QueueCommand(o.getString("id"), o.getString("device"), op, o.getLong("epoch"))
    }.getOrNull()

    fun outcome(r: CommandOutcome): JSONObject = when (r) {
        is CommandOutcome.Applied -> JSONObject().put("applied", true).put("revision", r.revision)
        is CommandOutcome.Refused -> JSONObject().put("applied", false).put("revision", r.revision)
            .put("why", r.why.name.lowercase())
    }

    fun outcome(o: JSONObject): CommandOutcome? = runCatching {
        if (o.getBoolean("applied")) CommandOutcome.Applied(o.getLong("revision"))
        else CommandOutcome.Refused(Refusal.valueOf(o.getString("why").uppercase()), o.getLong("revision"))
    }.getOrNull()

    fun delta(d: QueueDelta): JSONObject = when (d) {
        is QueueDelta.Ops -> JSONObject().put("from", d.from).put("ops", JSONArray().apply {
            d.ops.forEach { put(JSONObject().put("revision", it.revision).put("command", it.commandId).put("change", op(it.op))) }
        })
        is QueueDelta.Snapshot -> JSONObject().put("revision", d.revision).put("state", state(d.state))
    }

    /** Null when unreadable, or when it holds an op this version doesn't know (then ask for a snapshot). */
    fun delta(o: JSONObject): QueueDelta? = runCatching {
        if (o.has("state")) return QueueDelta.Snapshot(o.getLong("revision"), state(o.getJSONObject("state")))
        val a = o.getJSONArray("ops")
        val ops = List(a.length()) { i ->
            val x = a.getJSONObject(i)
            LoggedOp(x.getLong("revision"), x.getString("command"), op(x.getJSONObject("change")) ?: return null)
        }
        QueueDelta.Ops(o.getLong("from"), ops)
    }.getOrNull()
}
