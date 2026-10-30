package com.harmony.core.remote.queue

import java.util.UUID

/**
 * One request to change the queue. [id] is unique to the request and stays
 * the same when it is sent again, so a resend after a lost answer is
 * recognised and never applied twice. [epoch] is the playback epoch the
 * sender knew: playback commands ([QueueOp.SetCurrent], [QueueOp.SetOwner])
 * from a device that missed a handoff are refused.
 */
data class QueueCommand(val id: String, val device: String, val op: QueueOp, val epoch: Long)

/** An applied change, numbered: revision [revision] is the queue after it. */
data class LoggedOp(val revision: Long, val commandId: String, val op: QueueOp)

/** The answer to a [QueueCommand]. */
sealed interface CommandOutcome {
    /** The coordinator's revision when it answered. */
    val revision: Long

    data class Applied(override val revision: Long) : CommandOutcome
    data class Refused(val why: Refusal, override val revision: Long) : CommandOutcome
}

/** What a device needs to catch up from its revision: the ops it missed, or the whole queue. */
sealed interface QueueDelta {
    val revision: Long

    /** The ops after [from], up to [revision]. Empty when nothing changed. */
    data class Ops(val from: Long, val ops: List<LoggedOp>) : QueueDelta {
        override val revision: Long get() = ops.lastOrNull()?.revision ?: from
    }

    /** Too far behind for the log (or never in step): the whole queue at [revision]. */
    data class Snapshot(override val revision: Long, val state: QueueState) : QueueDelta
}

/**
 * The one place the queue changes while devices are connected (the computer,
 * in the first version). Commands are applied one at a time in the order they
 * arrive; each applied op gets the next revision and goes into a bounded log
 * that devices read to catch up ([since]). Every command's outcome is
 * remembered (bounded), so a resent command gets its first answer back.
 *
 * Randomness lives only here: a shuffle is turned into the explicit order
 * every device then applies ([QueueOp.SetShuffle.order]).
 */
class QueueCoordinator(
    initial: QueueState = QueueState(),
    initialRevision: Long = 0,
    private val shuffler: (List<String>) -> List<String> = { it.shuffled() },
    private val logLimit: Int = 256,
    private val rememberedCommands: Int = 2_048,
) {
    var state: QueueState = initial
        private set
    var revision: Long = initialRevision
        private set

    private val log = ArrayDeque<LoggedOp>()
    private val outcomes = object : LinkedHashMap<String, CommandOutcome>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CommandOutcome>?) = size > rememberedCommands
    }

    /** Applies [command] once; a command seen before gets its first answer again. */
    @Synchronized
    fun submit(command: QueueCommand): CommandOutcome {
        outcomes[command.id]?.let { return it }
        val outcome = decide(command)
        outcomes[command.id] = outcome
        return outcome
    }

    private fun decide(command: QueueCommand): CommandOutcome {
        val playback = command.op is QueueOp.SetCurrent || command.op is QueueOp.SetOwner
        if (playback && command.epoch != state.epoch) return CommandOutcome.Refused(Refusal.STALE_EPOCH, revision)
        val op = resolve(command.op)
        return when (val result = state.apply(op)) {
            is ApplyResult.Refused -> CommandOutcome.Refused(result.why, revision)
            is ApplyResult.Applied -> {
                state = result.state
                revision++
                log.addLast(LoggedOp(revision, command.id, op))
                while (log.size > logLimit) log.removeFirst()
                CommandOutcome.Applied(revision)
            }
        }
    }

    /** Fills in what only the coordinator decides: the shuffled order. */
    private fun resolve(op: QueueOp): QueueOp = when {
        op is QueueOp.SetShuffle && op.on && op.order == null -> op.copy(order = shuffler(state.freeUpcoming.map { it.id }))
        op is QueueOp.StartList && state.shuffle && op.order == null -> {
            val laidOut = (state.copy(shuffle = false).apply(op) as? ApplyResult.Applied)?.state
            if (laidOut == null) op else op.copy(order = shuffler(laidOut.freeUpcoming.map { it.id }))
        }
        else -> op
    }

    /** What a device at revision [from] missed. */
    @Synchronized
    fun since(from: Long): QueueDelta {
        if (from == revision) return QueueDelta.Ops(from, emptyList())
        val first = log.firstOrNull()?.revision
        if (from > revision || first == null || from < first - 1) return QueueDelta.Snapshot(revision, state)
        return QueueDelta.Ops(from, log.filter { it.revision > from })
    }
}

/**
 * A device's copy of the queue: [confirmed] as of the coordinator's
 * [revision], and the commands sent but not yet answered ([outbox]), which
 * are shown already applied ([view]) so an edit appears at once.
 *
 * Until a command is answered it is sent again, with the same id, every time
 * the device talks to the coordinator; the coordinator applies it once. A
 * command is settled by its answer, or by seeing it in the log (the answer
 * was lost). Offline, commands simply wait here and go out on reconnection,
 * where, being addressed by entry ids, they still mean what the user meant.
 */
class QueueReplica(
    val device: String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var confirmed: QueueState = QueueState()
        private set

    /** The coordinator's revision [confirmed] is at; -1 before the first snapshot. */
    var revision: Long = -1
        private set

    private val pending = ArrayList<QueueCommand>()

    /** The queue as this device shows it: [confirmed] with its own unanswered edits on top. */
    val view: QueueState
        @Synchronized get() = pending.fold(confirmed) { s, c -> (s.apply(c.op) as? ApplyResult.Applied)?.state ?: s }

    /** The commands to send (again), in order. */
    val outbox: List<QueueCommand> @Synchronized get() = pending.toList()

    /** A change made on this device: shown at once, sent until answered. */
    @Synchronized
    fun issue(op: QueueOp): QueueCommand {
        // The epoch this device believes in (its own handoff, still unanswered, included).
        val command = QueueCommand(newId(), device, op, view.epoch)
        pending += command
        return command
    }

    @Synchronized
    fun onOutcome(commandId: String, outcome: CommandOutcome) {
        pending.removeAll { it.id == commandId }
    }

    /**
     * Brings [confirmed] up to date. False when [delta] doesn't follow on from
     * [revision] (a gap: ask for [QueueCoordinator.since] again, or a snapshot).
     */
    @Synchronized
    fun onDelta(delta: QueueDelta): Boolean {
        when (delta) {
            is QueueDelta.Snapshot -> {
                if (delta.revision < revision) return true // older than what we have: nothing to learn
                confirmed = delta.state
                revision = delta.revision
            }
            is QueueDelta.Ops -> {
                if (delta.from != revision) return delta.revision <= revision
                for (logged in delta.ops) {
                    // The coordinator applied it; the same state and op give the same result here.
                    confirmed = (confirmed.apply(logged.op) as? ApplyResult.Applied)?.state ?: confirmed
                    revision = logged.revision
                    pending.removeAll { it.id == logged.commandId }
                }
            }
        }
        return true
    }
}
