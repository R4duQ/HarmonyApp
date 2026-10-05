package com.harmony.domain.playback

import kotlinx.coroutines.flow.StateFlow

/** A computer running Harmony for Windows, found on the Wi-Fi or paired before. */
data class ConnectComputer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** Paired already: connecting needs no code. */
    val paired: Boolean,
    /** Answered the last search. */
    val nearby: Boolean,
)

data class ConnectState(
    val searching: Boolean = false,
    val computers: List<ConnectComputer> = emptyList(),
    /** The computer the music is playing on now. */
    val active: ConnectComputer? = null,
    /** The computer being connected to or paired with. */
    val busyWith: String? = null,
    /** A computer that wants its pairing code typed in. */
    val needsCode: ConnectComputer? = null,
    val error: String? = null,
)

/**
 * Harmony Connect from the phone's side: play the queue on a computer
 * running Harmony for Windows, the phone staying the remote.
 */
interface ConnectController {
    val state: StateFlow<ConnectState>

    /** Looks for Harmony computers on the Wi-Fi (a couple of seconds). */
    fun search()

    /** Plays on [computer]: straight away if paired, otherwise asks for its code ([ConnectState.needsCode]). */
    fun connect(computer: ConnectComputer)

    /** Pairs with the code shown on the computer, then plays there. */
    fun pair(computer: ConnectComputer, code: String)

    /** A computer typed in by address, for networks where searching finds nothing. */
    fun addByAddress(host: String)

    /** Brings the music back to the phone, paused where it was. */
    fun disconnect()

    fun forget(computer: ConnectComputer)

    fun dismissError()
}
