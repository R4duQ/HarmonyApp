package com.harmony.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.remote.transfer.FailReason
import com.harmony.core.remote.transfer.ItemNote
import com.harmony.core.remote.transfer.ItemState
import com.harmony.core.remote.transfer.OutboxEntry
import com.harmony.desktop.DesktopApp
import com.harmony.desktop.connect.PcMedia
import com.harmony.desktop.library.LocalTrack
import java.io.File

/** Sizes as people read them. */
internal fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

/**
 * "Send to phone": which paired phone, how many files and how big, and which
 * of them the phone can't play (sent only if asked). The files go as they
 * are; the phone saves them in its library.
 */
@Composable
fun SendToPhoneDialog(app: DesktopApp, tracks: List<LocalTrack>, onDismiss: () -> Unit, onSent: () -> Unit = {}) {
    val c = LocalHarmonyColors.current
    val phones = remember { app.pairedPhones() }
    var chosen by remember { mutableStateOf(phones.keys.firstOrNull()) }
    var includeUnsupported by remember { mutableStateOf(false) }
    val unique = remember(tracks) { tracks.distinctBy { it.path } }
    val unsupported = remember(unique) { unique.filter { !PcMedia.phoneCanPlay(it) } }
    val going = if (includeUnsupported) unique else unique - unsupported.toSet()
    val bytes = remember(going) { going.sumOf { File(it.path).length() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Send to phone", color = c.ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "${count(going.size, "song")} · ${sizeText(bytes)}. The original files are copied to the phone and added to its library, checked so nothing arrives damaged. Open Harmony on the phone to receive them.",
                    fontSize = 13.sp, color = c.muted,
                )
                if (phones.isEmpty()) {
                    Text(
                        "No phone is paired with this computer yet. On the phone: Play on → this computer, and type the code shown in Connect.",
                        fontSize = 13.sp, color = Accent.Pink, modifier = Modifier.padding(top = 12.dp),
                    )
                }
                phones.forEach { (token, name) ->
                    val on = token == chosen
                    Row(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (on) Accent.Purple else c.line, RoundedCornerShape(12.dp))
                            .background(if (on) Accent.Purple.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { chosen = token }
                            .padding(12.dp)
                            .testTag("send_phone_$name"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = if (on) Accent.PurpleLight else c.muted, modifier = Modifier.size(20.dp))
                        Text(name, fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                if (unsupported.isNotEmpty()) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeUnsupported, { includeUnsupported = it })
                        Text(
                            "${count(unsupported.size, "song")} in a format the phone can't play (${unsupported.mapNotNull { File(it.path).extension.uppercase().ifEmpty { null } }.distinct().joinToString()}). Send anyway, to keep the files.",
                            fontSize = 12.sp, color = c.muted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val token = chosen ?: return@TextButton
                    app.sendToPhone(token, unique, includeUnsupported)
                    onSent()
                    onDismiss()
                },
                enabled = chosen != null && going.isNotEmpty(),
                modifier = Modifier.testTag("send_confirm"),
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What was sent to phones and how far each file got: on the Connect screen. */
@Composable
fun SentToPhonesCard(app: DesktopApp, modifier: Modifier = Modifier) {
    val c = LocalHarmonyColors.current
    val sends by app.sends.collectAsState()
    // The bytes taken so far move between the phone's reports: looked at twice a second.
    var live by remember { mutableStateOf(app.transfers?.entries().orEmpty()) }
    LaunchedEffect(app) {
        while (true) {
            live = app.transfers?.entries().orEmpty()
            kotlinx.coroutines.delay(500)
        }
    }
    val shown = if (live.size == sends.size) live else sends
    if (shown.isEmpty()) return
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
        Text("Sent to phones", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
        shown.asReversed().forEach { SendRow(app, it) }
    }
}

@Composable
private fun SendRow(app: DesktopApp, e: OutboxEntry) {
    val c = LocalHarmonyColors.current
    val o = e.offer
    val states = o.items.map { o.statusOf(it.id) }
    val done = states.count { it.state == ItemState.DONE }
    val failed = states.count { it.state == ItemState.FAILED }
    val cancelled = o.cancelled || states.all { it.state == ItemState.CANCELLED }
    val total = o.totalBytes.coerceAtLeast(1)
    val have = o.items.sumOf { item ->
        val s = o.statusOf(item.id)
        if (s.state == ItemState.DONE) item.sizeBytes else maxOf(s.bytes, e.served[item.id] ?: 0L).coerceAtMost(item.sizeBytes)
    }
    val waiting = states.all { it.state == ItemState.PENDING } && e.served.isEmpty()
    val summary = when {
        e.preparing -> "Getting ${count(o.items.size, "file")} ready (${e.prepared}/${o.items.size})…"
        cancelled -> "Cancelled · $done of ${o.items.size} saved"
        done + failed == o.items.size -> if (failed == 0) "All ${o.items.size} saved on the phone" else "$done saved · $failed failed"
        waiting -> "Waiting for the phone: open Harmony on it to receive"
        else -> "$done of ${o.items.size} saved · ${sizeText(have)} of ${sizeText(total)}"
    }
    Column(Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${count(o.items.size, "song")} to ${e.phoneName} · ${sizeText(o.totalBytes)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(summary, fontSize = 12.sp, color = if (failed > 0) Accent.Pink else c.muted, modifier = Modifier.padding(top = 2.dp).testTag("send_summary"))
            }
            val active = !cancelled && done + failed < o.items.size
            if (active) SmallAction("Cancel") { app.cancelSend(o.id) }
            if (failed > 0 || cancelled || states.any { it.state == ItemState.INTERRUPTED }) SmallAction("Retry") { app.retrySend(o.id) }
            if (!active) SmallAction("Clear") { app.removeSend(o.id) }
        }
        if (!cancelled && done + failed < o.items.size && !e.preparing) {
            LinearProgressIndicator(
                progress = { (have.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp),
                color = Accent.Purple,
                trackColor = c.line,
            )
        }
        // What went wrong, file by file.
        o.items.forEach { item ->
            val s = o.statusOf(item.id)
            val line = when {
                s.state == ItemState.FAILED -> "${item.name}: ${s.message ?: reasonText(s.reason)}"
                s.note == ItemNote.ALREADY_ON_PHONE -> "${item.name}: already on the phone"
                s.note == ItemNote.IN_LIBRARY -> "${item.name}: already in the phone's library"
                s.note == ItemNote.UNSUPPORTED_FORMAT -> "${item.name}: saved, but the phone can't play this format"
                else -> null
            }
            if (line != null) Text(line, fontSize = 11.5.sp, color = if (s.state == ItemState.FAILED) Accent.Pink else c.muted, modifier = Modifier.padding(top = 4.dp))
        }
        e.error?.let { Text(it, fontSize = 11.5.sp, color = Accent.Pink, modifier = Modifier.padding(top = 4.dp)) }
    }
}

private fun reasonText(r: FailReason?) = when (r) {
    FailReason.NO_SPACE -> "not enough space on the phone"
    FailReason.DISCONNECTED -> "the phone lost the connection"
    FailReason.PERMISSION -> "the phone couldn't write to its music folder"
    FailReason.SOURCE_MISSING -> "the file is no longer on this computer"
    FailReason.CHECKSUM -> "arrived damaged twice"
    FailReason.SOURCE_CHANGED -> "the file kept changing on this computer"
    FailReason.DECLINED -> "declined on the phone"
    FailReason.UNSUPPORTED -> "the phone can't play this format"
    FailReason.OTHER, null -> "failed"
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, color = Accent.PurpleLight,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("send_" + label.lowercase()),
    )
}

/** A small round "Send to phone" button for song rows. */
@Composable
internal fun SendToPhoneButton(onClick: () -> Unit) {
    val c = LocalHarmonyColors.current
    Box(
        Modifier.padding(end = 4.dp).size(30.dp).clip(RoundedCornerShape(15.dp)).clickable(onClickLabel = "Send to phone", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PhoneAndroid, contentDescription = "Send to phone", tint = c.muted, modifier = Modifier.size(18.dp))
    }
}
