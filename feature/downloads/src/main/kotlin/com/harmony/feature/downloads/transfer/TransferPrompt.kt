package com.harmony.feature.downloads.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Asks whether to save the songs a paired computer is sending. The first time,
 * it says where they go and what happens to them.
 */
@Composable
fun TransferPromptHost(inbox: TransferInbox) {
    val p by inbox.prompt.collectAsState()
    val prompt = p ?: return
    var always by remember(prompt.offer.id) { mutableStateOf(false) }
    val n = prompt.offer.open.size
    val mb = (prompt.offer.open.sumOf { it.sizeBytes } + (1 shl 20) - 1) / (1 shl 20)
    val unsupported = prompt.offer.open.count { !it.playableOnPhone }
    AlertDialog(
        onDismissRequest = { inbox.later() },
        title = { Text("Songs from ${prompt.computer.name}") },
        text = {
            Column {
                Text("${prompt.computer.name} wants to send $n song${if (n == 1) "" else "s"} ($mb MB) to this phone.")
                if (prompt.firstTime) {
                    Text(
                        "They're saved in ${prompt.folder} and added to your library. The files are copied as they are, checked on arrival, and stay on the phone. Songs you already have aren't copied twice.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Text("Saved in ${prompt.folder}.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (unsupported > 0) {
                    Text(
                        "$unsupported of them are in a format this phone can't play; they're kept as files only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(always, { always = it })
                    Text("Always accept from ${prompt.computer.name}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { inbox.accept(always) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { inbox.decline() }) { Text("Decline") } },
    )
}
