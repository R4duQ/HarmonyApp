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

/** The computer's songs kept on this phone so they play without waiting on the Wi-Fi. */
data class PhoneCache(
    val usedBytes: Long,
    val limitBytes: Long,
    /** The limits that can be chosen, smallest first. */
    val choices: List<Long>,
)

/**
 * A handoff that ended without knowing where the music is: the computer was
 * told to play, or to pause, and stopped answering. The person decides.
 */
data class UnsureHandoff(
    val computerName: String,
    /** True: it may be playing on the computer (the phone is paused). */
    val mayPlayThere: Boolean,
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
    /** Null until Play on is opened (read then). */
    val phoneCache: PhoneCache? = null,
    /** Asks "Continue here?" after a handoff that ended unsure. */
    val unsure: UnsureHandoff? = null,
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

    /** Brings the music back to the phone: playing on, from the same place, if it was playing there. */
    fun disconnect()

    /** Saves a log of what Connect did, for testing on devices; returns where it went (null: couldn't). Blocking. */
    fun exportDiagnostics(): String? = null

    fun forget(computer: ConnectComputer)

    /** A new limit for the computer's songs kept on this phone (one of [PhoneCache.choices]). */
    fun setPhoneCacheLimit(bytes: Long) {}

    /** Removes the computer's songs kept on this phone (they still play, from the computer). */
    fun emptyPhoneCache() {}

    /** After an unsure handoff: plays on this phone, from where the music was ([UnsureHandoff]). */
    fun continueHere() {}

    /** After an unsure handoff: leaves things as they are. */
    fun dismissUnsure() {}

    fun dismissError()
}
