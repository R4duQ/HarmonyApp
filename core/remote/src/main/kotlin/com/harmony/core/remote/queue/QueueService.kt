package com.harmony.core.remote.queue

import com.harmony.core.remote.HttpResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * The coordinator's side of the Universal Queue on the network (the computer,
 * in the first version), behind [com.harmony.core.remote.ConnectReceiver]:
 *
 *  - [com.harmony.core.remote.Connect.PATH_QUEUE_COMMANDS]: a batch of commands, answered one by one
 *    (a resent command gets its first answer back, see [QueueCoordinator]);
 *  - [com.harmony.core.remote.Connect.PATH_QUEUE_SINCE]: what changed after a revision. When nothing
 *    has, the request is held until something does or [maxWaitMs] passes
 *    (long-poll), so a device hears of a change at once without asking twice
 *    a second.
 *
 * The computer's own edits go straight to [submit]; [onChange] tells its
 * player and screen.
 */
class QueueService(
    val coordinator: QueueCoordinator = QueueCoordinator(),
    /** Shorter than the phone session's "reconnecting" delay, so a held request never looks like silence. */
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val onChange: (QueueState, Long) -> Unit = { _, _ -> },
) {
    private val lock = Object()

    val state: QueueState get() = synchronized(lock) { coordinator.state }
    val revision: Long get() = synchronized(lock) { coordinator.revision }

    /** One command, from the computer itself or from a device. */
    fun submit(command: QueueCommand): CommandOutcome {
        val (outcome, changed) = synchronized(lock) {
            val before = coordinator.revision
            val outcome = coordinator.submit(command)
            val changed = coordinator.revision != before
            if (changed) lock.notifyAll()
            outcome to (if (changed) coordinator.state to coordinator.revision else null)
        }
        changed?.let { (s, r) -> onChange(s, r) }
        return outcome
    }

    /** A JSON batch of commands: `{"commands":[...]}` → `{"outcomes":[{"id":..,"outcome":..}]}`. */
    fun commands(body: String): HttpResponse {
        val list = runCatching { JSONObject(body).getJSONArray("commands") }.getOrNull()
            ?: return HttpResponse.text(400, "bad queue commands")
        if (list.length() > MAX_BATCH) return HttpResponse.text(413, "too many commands at once")
        val answers = JSONArray()
        for (i in 0 until list.length()) {
            val raw = list.optJSONObject(i)
            val command = raw?.let(QueueJson::command)
            val id = raw?.optString("id").orEmpty()
            val outcome = if (command == null) {
                // Unreadable (or an op this version doesn't know): refused, so the sender stops resending it.
                CommandOutcome.Refused(Refusal.BAD_REQUEST, revision)
            } else {
                submit(command)
            }
            answers.put(JSONObject().put("id", id).put("outcome", QueueJson.outcome(outcome)))
        }
        return HttpResponse.json(JSONObject().put("outcomes", answers).toString())
    }

    /** What changed after [from]; held up to [waitMs] (at most [maxWaitMs]) while nothing has. */
    fun since(from: Long, waitMs: Long): HttpResponse {
        val wait = waitMs.coerceIn(0, maxWaitMs)
        val deadline = System.nanoTime() / 1_000_000 + wait
        val delta = synchronized(lock) {
            while (from == coordinator.revision) {
                val left = deadline - System.nanoTime() / 1_000_000
                if (left <= 0) break
                lock.wait(left)
            }
            coordinator.since(from)
        }
        return HttpResponse.json(QueueJson.delta(delta).toString())
    }

    companion object {
        const val MAX_WAIT_MS = 4_000L
        const val MAX_BATCH = 256
    }
}
