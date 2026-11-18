package com.harmony.feature.downloads

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.GlassCard
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel

/**
 * The YT Converter panel: paste a link, identify it, convert it.
 *
 * This exists because the screen used to branch on `soulseekArmed` alone,
 * so anything that wasn't Soulseek fell through to the SpotiFLAC layout —
 * which is why arming the converter produced an "Artist and track" search
 * box and no way to supply a URL at all.
 *
 * The flow is genuinely shorter than the other two engines' and the layout
 * reflects that rather than padding it out to match. There is no result
 * list to choose from and no provider to verify: the link identifies
 * exactly one video, so identification produces a single confirmation card
 * and the only remaining decision is whether to convert it.
 *
 * Identifying is optional. [onConvert] re-validates the URL itself, so the
 * Convert button is live as soon as the field holds a link — identify is
 * there to let you confirm you pasted the right video before spending a
 * conversion on it, not as a gate you must pass first.
 */
@Composable
internal fun YtConverterLayout(
    state: DownloadsViewModel.State,
    palette: EditorialPalette,
    identity: DownloadSourceIdentity,
    onUrlChange: (String) -> Unit,
    onIdentify: () -> Unit,
    onConvert: () -> Unit,
    onToggleErrorDetails: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val busy = state.isConverting
    val hasUrl = state.query.isNotBlank()

    Spacer(Modifier.height(18.dp))
    EditorialSectionLabel(
        identity.searchSectionLabel,
        palette,
        Modifier.padding(start = 22.dp, bottom = 8.dp),
    )
    GlassCard(palette = palette, modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                identity.searchHelp,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.muted,
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                singleLine = true,
                enabled = !busy,
                label = { Text(identity.queryFieldLabel) },
                placeholder = { Text(identity.queryPlaceholder) },
            )
            // A paste button, because this is the one field in the app you
            // never type into — the URL always arrives from the YouTube
            // app's share sheet or the browser's address bar.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorialPill(
                    text = "Paste",
                    icon = Icons.Rounded.ContentPaste,
                    onClick = {
                        if (!busy) {
                            clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let(onUrlChange)
                        }
                    },
                    palette = palette,
                )
                // Checking the title first is optional: a quiet text action.
                TextButton(
                    onClick = { if (!state.isIdentifying && !busy) onIdentify() },
                    enabled = hasUrl && !state.isIdentifying && !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (state.isIdentifying) identity.searchingLabel else "Check the title first", color = palette.accent)
                }
            }
            // The screen's one primary action, under the link it acts on, and
            // only enabled once there is a link (it used to do nothing, silently).
            Button(
                onClick = onConvert,
                enabled = hasUrl && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(min = 52.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(if (busy) "Converting…" else identity.downloadActionLabel, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                identity.queryFooter,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (state.isIdentifying) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
    }

    // What the converter is doing, or what went wrong; nothing while there is
    // nothing to report (it used to say "No link yet" on every visit).
    if (busy || state.identifiedTrack != null || state.error != null || state.message != null) {
        Spacer(Modifier.height(18.dp))
        EditorialSectionLabel(
            "Conversion",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        GlassCard(palette = palette, modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(Modifier.padding(16.dp)) {
                val track = state.identifiedTrack
                when {
                    busy -> {
                        Text(
                            "Converting…",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                        )
                        // yt-dlp's own stage text, passed straight through: the
                        // interesting failures happen inside yt-dlp and FFmpeg,
                        // and paraphrasing them into "Working…" would hide the
                        // only signal there is while a long convert runs.
                        Text(
                            state.conversionStatus.ifBlank { "Starting yt-dlp + FFmpeg…" },
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = palette.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                        LinearProgressIndicator(
                            progress = { state.conversionProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }

                    track != null -> {
                        Text(
                            track.displayName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            identity.resultBadge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    else -> {
                        Text(
                            "Ready to convert",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                        )
                        Text(
                            "Convert it straight away, or check the title first.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }

                state.error?.let { error ->
                    Text(
                        error,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    state.errorDetails?.let { details ->
                        TextButton(onClick = onToggleErrorDetails) {
                            Text(if (state.showErrorDetails) "Hide details" else "Show details")
                        }
                        if (state.showErrorDetails) {
                            Text(
                                details,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = palette.muted,
                            )
                        }
                    }
                }

                state.message?.let { message ->
                    Text(
                        message,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

            }
        }
    }
}
