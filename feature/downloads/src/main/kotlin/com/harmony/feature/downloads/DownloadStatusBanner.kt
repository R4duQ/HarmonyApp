package com.harmony.feature.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.component.EditorialPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Reads the app-scoped [DownloadStatusCenter] for the shell's banner. */
@HiltViewModel
class DownloadBannerViewModel @Inject constructor(
    private val statusCenter: DownloadStatusCenter,
) : ViewModel() {
    val active = statusCenter.active
    val outcome = statusCenter.outcome
    fun consumeOutcome(id: Long) = statusCenter.consumeOutcome(id)
}

/** How long a finished-download banner stays up before dismissing itself. */
private const val OUTCOME_VISIBLE_MS = 4_000L

/**
 * The download banner that rides above the mini player on every screen except
 * Downloads itself.
 *
 * Two states, one strip:
 *  - a download is running: title, source, live progress, tappable to jump
 *    back to Downloads;
 *  - a download just finished: a success or failure line that dismisses itself
 *    after a few seconds.
 *
 * [suppressed] is passed true while the Downloads screen is on top, because
 * that screen already renders richer progress and two progress bars for the
 * same transfer reads as two downloads.
 */
@Composable
fun DownloadStatusBanner(
    palette: EditorialPalette,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    suppressed: Boolean = false,
    viewModel: DownloadBannerViewModel = hiltViewModel(),
) {
    val active by viewModel.active.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()

    // Keyed on the outcome's id, not the outcome itself: two downloads of the
    // same track would otherwise be one unchanged value and the timer would
    // never restart.
    LaunchedEffect(outcome?.id, active != null) {
        val id = outcome?.id
        if (id != null && active == null) {
            delay(OUTCOME_VISIBLE_MS)
            viewModel.consumeOutcome(id)
        }
    }

    val showActive = active != null && !suppressed
    val showOutcome = outcome != null && active == null && !suppressed

    AnimatedVisibility(
        visible = showActive || showOutcome,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val finished = outcome
        val running = active
        Surface(
            onClick = onOpenDownloads,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .heightIn(min = 56.dp)
                // A banner that appears on its own should announce itself
                // rather than waiting to be swiped to.
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = bannerDescription(active, outcome, showOutcome)
                },
            shape = RoundedCornerShape(16.dp),
            color = palette.field,
            contentColor = palette.ink,
            border = BorderStroke(1.dp, palette.line),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when {
                    showOutcome && finished?.succeeded == true -> Icons.Rounded.CheckCircle
                    showOutcome -> Icons.Rounded.ErrorOutline
                    else -> Icons.Rounded.Download
                }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier.size(22.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    val title: String
                    val detail: String?
                    if (showOutcome && finished != null) {
                        title = finished.title
                        detail = if (finished.succeeded) {
                            "Download complete"
                        } else {
                            finished.message ?: "Download failed"
                        }
                    } else {
                        title = running?.title.orEmpty()
                        detail = listOfNotNull(running?.source, running?.detail)
                            .joinToString(" • ")
                            .takeIf { it.isNotBlank() }
                    }
                    Text(
                        title,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail != null) {
                        Text(
                            detail,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showActive && running != null) {
                        val fraction = running.fraction
                        Box(Modifier.padding(top = 7.dp)) {
                            if (fraction != null) {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    color = palette.accentOrInk(),
                                    trackColor = palette.line.copy(alpha = 0.35f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                // Size unknown yet — an indeterminate bar is
                                // honest, a fake percentage is not.
                                LinearProgressIndicator(
                                    color = palette.accentOrInk(),
                                    trackColor = palette.line.copy(alpha = 0.35f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                if (showActive && running?.fraction != null) {
                    Text(
                        "${(running.fraction * 100).toInt()}%",
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * The accent reads as a filled button colour on some palettes and as ink on
 * others; for a thin progress line the ink colour is the readable one on every
 * palette in the set.
 */
private fun EditorialPalette.accentOrInk() = ink

private fun bannerDescription(
    active: ActiveDownload?,
    outcome: DownloadOutcome?,
    showOutcome: Boolean,
): String = when {
    showOutcome && outcome != null && outcome.succeeded ->
        "${outcome.title} finished downloading."
    showOutcome && outcome != null ->
        "${outcome.title} failed to download. ${outcome.message.orEmpty()}"
    active != null -> {
        val percent = active.fraction?.let { " ${(it * 100).toInt()} percent." }.orEmpty()
        "Downloading ${active.title} from ${active.source}.$percent Opens Downloads."
    }
    else -> "Download status"
}
