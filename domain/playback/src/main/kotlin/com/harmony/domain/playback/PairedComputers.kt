package com.harmony.domain.playback

/** A computer this phone is paired with (Harmony Connect), with what it takes to ask it things. */
data class PairedComputer(val id: String, val name: String, val host: String, val port: Int, val token: String)

/**
 * The computers this phone is paired with, for "Send to phone" (songs sent
 * from a computer for good), which uses the pairing but not the playback
 * session. Implemented by the Connect session; the token never leaves the app.
 */
interface PairedComputers {
    fun paired(): List<PairedComputer>

    /**
     * Looks for the computer [id] on the network again (its address may have
     * changed), saves the address it answers from and returns it. Blocking (a
     * second or two): call it off the main thread.
     */
    fun relocate(id: String): PairedComputer?

    /** Whether the phone is playing on a computer now (transfers then go slower, the music first). */
    val playingOnComputer: Boolean
}
