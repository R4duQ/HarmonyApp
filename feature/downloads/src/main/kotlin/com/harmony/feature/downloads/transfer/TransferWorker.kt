package com.harmony.feature.downloads.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.harmony.core.database.dao.SongDao
import com.harmony.core.remote.transfer.FailReason
import com.harmony.core.remote.transfer.ItemNote
import com.harmony.core.remote.transfer.ItemState
import com.harmony.core.remote.transfer.RunOutcome
import com.harmony.core.remote.transfer.RunProgress
import com.harmony.core.remote.transfer.TransferClient
import com.harmony.core.remote.transfer.TransferLedger
import com.harmony.core.remote.transfer.TransferRunner
import com.harmony.domain.playback.PairedComputers
import com.harmony.domain.playback.PlaybackController
import com.harmony.feature.downloads.MusicDownloadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Receives one send from a paired computer ("Send to phone"), started only
 * after the listener accepted it. Runs as WorkManager work with a dataSync
 * foreground notification (progress, Cancel), so it carries on with the
 * screen off within what Android allows: on Android 15 that kind of work has
 * six hours a day, reset when the app is opened.
 *
 * A computer that can't be reached is looked for again (its address may have
 * changed) and the work retried with backoff, at most [MAX_RUNS] times; the
 * file part already here is kept and resumed.
 */
@HiltWorker
class TransferWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val computers: PairedComputers,
    private val downloads: MusicDownloadRepository,
    private val songs: SongDao,
    private val playback: PlaybackController,
) : CoroutineWorker(context, parameters) {
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        val pcId = inputData.getString(KEY_PC) ?: return Result.failure()
        val offerId = inputData.getString(KEY_OFFER) ?: return Result.failure()
        val pc = computers.paired().firstOrNull { it.id == pcId } ?: return Result.failure()
        channels()
        runCatching { setForeground(foreground(pc.name, "Starting…", null)) }
            .onFailure { Log.w(TAG, "transfer runs without its notification (${it.javaClass.simpleName})") }

        val base = File(applicationContext.filesDir, "transfers")
        val staging = File(base, "staging")
        TransferRunner.cleanStaging(staging, keep = setOf(offerId), maxAgeMs = STAGING_MAX_AGE_MS)
        val runner = TransferRunner(
            client = TransferClient(pc.host, pc.port, pc.token),
            staging = staging,
            ledger = TransferLedger(File(base, "ledger.json")),
            library = PhoneTransferLibrary(downloads, songs, staging),
            bytesPerSecond = ::pace,
            cancelled = { isStopped },
            onProgress = { showProgress(pc.name, it) },
            log = { note(applicationContext, it) },
        )
        val began = System.currentTimeMillis()
        note(applicationContext, "transfer ${offerId.take(8)}: run $runAttemptCount started")
        val result = withContext(Dispatchers.IO) { runner.run(offerId) }
        val counts = result.statuses.values.groupingBy { s -> s.state.name.lowercase() + (s.reason?.let { "/" + it.name.lowercase() } ?: "") }.eachCount()
        val mb = result.statuses.values.sumOf { it.bytes } / 1_048_576.0
        note(
            applicationContext,
            "transfer ${offerId.take(8)}: ${result.outcome.name.lowercase()} after ${(System.currentTimeMillis() - began) / 1000} s · " +
                "${"%.1f".format(java.util.Locale.US, mb)} MB on the phone · $counts",
        )
        return when (result.outcome) {
            RunOutcome.FINISHED -> {
                finished(offerId, pc.name, result.statuses.values.toList())
                Result.success()
            }
            RunOutcome.CANCELLED -> {
                if (!isStopped) notifyResult(offerId, "Transfer from ${pc.name} cancelled", "Cancelled on the computer. Nothing partial was kept.")
                Result.success()
            }
            RunOutcome.RETRY_LATER -> {
                // The computer may have a new address on the network.
                withContext(Dispatchers.IO) { runCatching { computers.relocate(pcId) } }
                if (runAttemptCount + 1 < MAX_RUNS) Result.retry() else {
                    notifyResult(offerId, "Transfer from ${pc.name} paused", "The computer can't be reached. Open Harmony on both, on the same Wi-Fi, and send again: it carries on where it stopped.")
                    Result.failure()
                }
            }
        }
    }

    /** The music first: slower while the phone plays, slower still while it plays on a computer. */
    private fun pace(): Long = when {
        computers.playingOnComputer -> 2L * 1024 * 1024
        playback.playerState.value.isPlaying -> 4L * 1024 * 1024
        else -> 8L * 1024 * 1024
    }

    private fun showProgress(pcName: String, p: RunProgress) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 700) return
        lastNotified = now
        val total = p.offer.items.size
        val item = p.item ?: return
        val pct = if (item.sizeBytes > 0) (p.itemBytes * 100 / item.sizeBytes).toInt() else 0
        val text = "${p.index + 1} of $total · ${item.title.ifBlank { item.name }} · $pct%"
        notifications.notify(NOTIFICATION_ID, foreground(pcName, text, (p.index * 100 + pct) / total.coerceAtLeast(1)).notification)
    }

    private fun finished(offerId: String, pcName: String, statuses: List<com.harmony.core.remote.transfer.ItemStatus>) {
        val saved = statuses.count { it.state == ItemState.DONE && (it.note == ItemNote.NONE || it.note == ItemNote.UNSUPPORTED_FORMAT) }
        val had = statuses.count { it.state == ItemState.DONE && (it.note == ItemNote.ALREADY_ON_PHONE || it.note == ItemNote.IN_LIBRARY) }
        val failed = statuses.filter { it.state == ItemState.FAILED }
        val title = when {
            failed.isEmpty() -> "${count(saved, "song")} saved from $pcName"
            else -> "${count(saved, "song")} saved, ${failed.size} failed"
        }
        val details = buildList {
            if (had > 0) add("${count(had, "song")} you already had")
            failed.groupBy { it.reason }.forEach { (reason, list) -> add("${list.size} ${reasonText(reason)}") }
            if (statuses.any { it.note == ItemNote.UNSUPPORTED_FORMAT }) add("some are in a format this phone can't play")
        }.joinToString(" · ").ifEmpty { "In your library now." }
        notifyResult(offerId, title, details)
    }

    private fun reasonText(r: FailReason?) = when (r) {
        FailReason.NO_SPACE -> "not enough space"
        FailReason.DISCONNECTED -> "connection lost"
        FailReason.PERMISSION -> "couldn't write to the music folder"
        FailReason.SOURCE_MISSING -> "no longer on the computer"
        FailReason.CHECKSUM -> "arrived damaged"
        FailReason.SOURCE_CHANGED -> "kept changing on the computer"
        FailReason.DECLINED -> "declined"
        FailReason.UNSUPPORTED -> "format not supported"
        FailReason.OTHER, null -> "failed"
    }

    private fun count(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"

    private fun channels() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Songs from your computer", NotificationManager.IMPORTANCE_LOW))
    }

    private fun launchIntent(): PendingIntent? =
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun foreground(pcName: String, text: String, percent: Int?): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Receiving songs from $pcName")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(launchIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyResult(offerId: String, title: String, text: String) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        runCatching { notifications.notify(RESULT_ID_BASE + (offerId.hashCode() and 0xFFFF), n) }
    }

    companion object {
        const val KEY_PC = "pc"
        const val KEY_OFFER = "offer"
        private const val TAG = "HarmonyTransfer"
        private const val CHANNEL = "harmony_transfers"
        private const val NOTIFICATION_ID = 7_901
        private const val RESULT_ID_BASE = 80_000
        private const val MAX_RUNS = 10
        private const val STAGING_MAX_AGE_MS = 7L * 24 * 3600 * 1000

        /**
         * Logs a transfer step to logcat and to files/transfer-log.txt (kept
         * under 256 KB), which "Save diagnostics" on the Play on sheet includes.
         * Ids, sizes and timings only: never tokens or addresses with them.
         */
        fun note(context: Context, message: String) {
            Log.i(TAG, message)
            runCatching {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = File(dir, "transfer-log.txt")
                synchronized(LOG_LOCK) {
                    if (file.length() > LOG_MAX_BYTES) file.writeText(file.readText().takeLast((LOG_MAX_BYTES / 2).toInt()))
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                    file.appendText("$stamp $message\n")
                }
            }
        }

        private val LOG_LOCK = Any()
        private const val LOG_MAX_BYTES = 256 * 1024L

        fun workName(offerId: String) = "harmony-transfer-$offerId"

        fun request(pcId: String, offerId: String): OneTimeWorkRequest = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(Data.Builder().putString(KEY_PC, pcId).putString(KEY_OFFER, offerId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("harmony-transfer")
            .build()
    }
}
