package com.harmony.feature.downloads.transfer

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.harmony.core.remote.transfer.FailReason
import com.harmony.core.remote.transfer.ItemReport
import com.harmony.core.remote.transfer.ItemState
import com.harmony.core.remote.transfer.ItemStatus
import com.harmony.core.remote.transfer.TransferClient
import com.harmony.core.remote.transfer.TransferOffer
import com.harmony.domain.playback.PairedComputer
import com.harmony.domain.playback.PairedComputers
import com.harmony.feature.downloads.MusicDownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Songs a paired computer wants to send ("Send to phone"): found by asking each
 * paired computer every few seconds while Harmony is open on the phone (or
 * playing on a computer). Nothing listens on the phone: a computer can't reach
 * it on its own, and Android would stop a listener in the background anyway.
 *
 * Each offer is asked about once ([prompt]) unless the listener chose to
 * always accept from that computer; accepted ones go to [TransferWorker].
 */
@Singleton
class TransferInbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val computers: PairedComputers,
    private val downloads: MusicDownloadRepository,
) {
    /** An offer waiting for the listener's answer. */
    data class Prompt(
        val computer: PairedComputer,
        val offer: TransferOffer,
        /** Where the files go, in words. */
        val folder: String,
        /** First send ever: explain where the files go. */
        val firstTime: Boolean,
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    /** Offers already asked about (or handed on) while this process lives. */
    private val handled: MutableSet<String> = Collections.synchronizedSet(HashSet())
    @Volatile private var visible = false
    private var loop: Job? = null

    /** Harmony's screen came up or went away (MainActivity onStart/onStop). */
    fun setVisible(now: Boolean) {
        visible = now
        if (now && loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    if (visible || computers.playingOnComputer) runCatching { look() }.onFailure { Log.w(TAG, "offers: ${it.message}") }
                    delay(if (visible) POLL_MS else IDLE_POLL_MS)
                }
            }
        }
    }

    private fun look() {
        for (pc in computers.paired()) {
            val offers = runCatching { TransferClient(pc.host, pc.port, pc.token, timeoutMs = 2_000).offers() }.getOrNull() ?: continue
            for (offer in offers) consider(pc, offer)
        }
    }

    private fun consider(pc: PairedComputer, offer: TransferOffer) {
        if (offer.id in handled || isRunning(offer.id)) return
        if (alwaysFrom(pc.id)) {
            handled += offer.id
            start(pc, offer)
            return
        }
        if (_prompt.value != null) return
        handled += offer.id
        _prompt.value = Prompt(pc, offer, downloads.transferFolderLabel(), firstTime = !prefs.getBoolean(KEY_EXPLAINED, false))
    }

    private fun isRunning(offerId: String): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(TransferWorker.workName(offerId)).get()
            .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    }.getOrDefault(false)

    /** Yes: the files come now ([always]: from this computer without asking again). */
    fun accept(always: Boolean) {
        val p = _prompt.value ?: return
        _prompt.value = null
        val edit = prefs.edit().putBoolean(KEY_EXPLAINED, true)
        if (always) edit.putBoolean(KEY_ALWAYS + p.computer.id, true)
        edit.apply()
        start(p.computer, p.offer)
    }

    /** No: the computer is told, file by file. */
    fun decline() {
        val p = _prompt.value ?: return
        _prompt.value = null
        prefs.edit().putBoolean(KEY_EXPLAINED, true).apply()
        scope.launch {
            val reports = p.offer.open.map { item ->
                ItemReport(item.id, ItemStatus(ItemState.FAILED, 0, p.offer.statusOf(item.id).attempt, FailReason.DECLINED, message = "Declined on the phone."))
            }
            runCatching { TransferClient(p.computer.host, p.computer.port, p.computer.token).report(p.offer.id, reports) }
        }
    }

    /** Not now: asked again the next time Harmony opens. */
    fun later() {
        _prompt.value = null
    }

    fun alwaysFrom(pcId: String): Boolean = prefs.getBoolean(KEY_ALWAYS + pcId, false)

    /** Stop accepting without asking from [pcId]. */
    fun askAgainFrom(pcId: String) = prefs.edit().remove(KEY_ALWAYS + pcId).apply()

    private fun start(pc: PairedComputer, offer: TransferOffer) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            TransferWorker.workName(offer.id),
            ExistingWorkPolicy.KEEP,
            TransferWorker.request(pc.id, offer.id),
        )
    }

    private companion object {
        const val TAG = "HarmonyTransfer"
        const val PREFS = "harmony_transfers"
        const val KEY_EXPLAINED = "explained"
        const val KEY_ALWAYS = "always:"
        const val POLL_MS = 8_000L
        const val IDLE_POLL_MS = 20_000L
    }
}
