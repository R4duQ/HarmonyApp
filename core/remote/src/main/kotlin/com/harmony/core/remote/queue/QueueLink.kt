package com.harmony.core.remote.queue

import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.ConnectRefused
import com.harmony.core.remote.ConnectSessionEnded
import java.io.IOException
import kotlin.random.Random

/**
 * Waits between attempts after a failure: exponential, capped, with full
 * jitter (a random wait up to the exponential bound), so two devices that lost
 * the network together don't hammer it back in step. Never below [floorMs], so
 * a connection refused at once can't spin.
 */
class Backoff(
    private val floorMs: Long = 100,
    private val baseMs: Long = 250,
    private val capMs: Long = 4_000,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    @Synchronized
    fun next(): Long {
        val bound = (baseMs shl attempt.coerceAtMost(16)).coerceAtMost(capMs)
        attempt++
        return floorMs + random.nextLong(bound.coerceAtLeast(1))
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }
}

/**
 * A device's live connection to the coordinator's queue (the phone's, to the
 * computer). Two threads: one sends this device's edits as soon as they are
 * made, and keeps resending what is unanswered; the other holds a request
 * open for the coordinator's changes (long-poll), so an edit on the computer
 * shows here at once while an idle queue costs one request every few seconds.
 *
 * On a failure each side backs off ([Backoff]) and tries again; edits made
 * meanwhile wait in the [replica] and keep their meaning (they are addressed
 * by entry ids). The link stops for good when the computer ends the session
 * or forgets this phone ([State.ENDED]), or doesn't offer the queue at all
 * ([State.UNSUPPORTED]): an older computer, where the phone keeps its own queue.
 */
class QueueLink(
    private val client: ConnectClient,
    val replica: QueueReplica,
    private val waitMs: Long = QueueService.MAX_WAIT_MS,
    private val sendBackoff: Backoff = Backoff(),
    private val pollBackoff: Backoff = Backoff(),
    /** The queue as this device should show it now. Called on the link's threads. */
    private val onChange: (QueueState) -> Unit = {},
    private val onState: (State) -> Unit = {},
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    enum class State { CONNECTING, CONNECTED, RECONNECTING, ENDED, UNSUPPORTED }

    @Volatile var state: State = State.CONNECTING
        private set

    @Volatile private var open = true
    private val wake = Object()
    private var shown: QueueState? = null

    private val sender = Thread(::sendLoop, "harmony-queue-send").apply { isDaemon = true }
    private val poller = Thread(::pollLoop, "harmony-queue-poll").apply { isDaemon = true }

    fun start(): QueueLink {
        sender.start()
        poller.start()
        return this
    }

    /** An edit on this device: shown at once, sent now, resent until answered. */
    fun issue(op: QueueOp): QueueCommand {
        val command = replica.issue(op)
        publish()
        synchronized(wake) { wake.notifyAll() }
        return command
    }

    private fun sendLoop() {
        while (open) {
            val outbox = replica.outbox
            if (outbox.isEmpty()) {
                synchronized(wake) { if (open && replica.outbox.isEmpty()) wake.wait(RESEND_IDLE_MS) }
                continue
            }
            try {
                val answers = client.queueCommands(outbox.take(QueueService.MAX_BATCH))
                answers.forEach { (id, outcome) -> replica.onOutcome(id, outcome) }
                sendBackoff.reset()
                reachable()
                publish()
            } catch (e: Exception) {
                if (!failed(e)) return
                pause(sendBackoff.next())
            }
        }
    }

    private fun pollLoop() {
        while (open) {
            try {
                val delta = client.queueSince(replica.revision, if (state == State.CONNECTED) waitMs else 0)
                    ?: client.queueSince(-1, 0) // something this version can't read: take the whole queue
                    ?: throw IOException("unreadable queue")
                if (!replica.onDelta(delta)) {
                    // A gap: ask again from where this device is (the coordinator sends a snapshot if needed).
                    replica.onDelta(client.queueSince(replica.revision, 0) ?: client.queueSince(-1, 0) ?: throw IOException("unreadable queue"))
                }
                pollBackoff.reset()
                reachable()
                publish()
            } catch (e: Exception) {
                if (!failed(e)) return
                pause(pollBackoff.next())
            }
        }
    }

    /** False when the link must stop. */
    private fun failed(e: Exception): Boolean {
        if (!open) return false
        when {
            e is ConnectSessionEnded || e is ConnectRefused -> {
                log("queue link ended: ${e.message}")
                setState(State.ENDED)
                close()
                return false
            }
            e.message?.startsWith("HTTP 404") == true -> {
                log("this computer has no shared queue")
                setState(State.UNSUPPORTED)
                close()
                return false
            }
        }
        if (state == State.CONNECTED || state == State.CONNECTING) {
            log("queue link: ${e.message}; retrying")
            setState(State.RECONNECTING)
        }
        return true
    }

    private fun reachable() {
        if (state != State.CONNECTED) setState(State.CONNECTED)
    }

    @Synchronized
    private fun setState(s: State) {
        if (state == s || state == State.ENDED || state == State.UNSUPPORTED) return
        state = s
        onState(s)
    }

    @Synchronized
    private fun publish() {
        val view = replica.view
        if (view == shown) return
        shown = view
        onChange(view)
    }

    private fun pause(ms: Long) {
        try {
            synchronized(wake) { if (open) wake.wait(ms) }
        } catch (_: InterruptedException) {
            // Closing.
        }
    }

    override fun close() {
        open = false
        synchronized(wake) { wake.notifyAll() }
        poller.interrupt()
    }

    private companion object {
        /** How often unanswered edits are looked at again when nothing wakes the sender. */
        const val RESEND_IDLE_MS = 1_000L
    }
}
