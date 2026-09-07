package com.harmony.feature.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.stonePalette
import com.harmony.domain.analysis.model.SpectralReport

/**
 * "Check a FLAC": pick a file, see its spectrum, and get a judgement on
 * whether a lossless container actually holds lossless audio.
 *
 * The verdict is stated as likelihood and always shown with its reasoning,
 * because no spectrum can prove provenance — a genuinely quiet or muffled
 * recording can look like a transcode, and saying otherwise would be lying
 * with a confident-looking chart.
 */
@Composable
fun FlacCheckScreen(viewModel: FlacCheckViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = stonePalette()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Name and size come from the document provider, not the path:
            // a content:// URI has no filename, and SAF is the only source
            // that knows either for a file we were just handed.
            var name = uri.lastPathSegment ?: "audio file"
            var size = 0L
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0) name = c.getString(n) ?: name
                        if (s >= 0) size = c.getLong(s)
                    }
                }
            }
            viewModel.inspect(uri.toString(), name, size)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Check a file",
            fontSize = 34.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.9).sp,
            color = palette.ink,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp),
        )
        Text(
            "See whether a FLAC really is lossless, or an MP3 that was re-encoded " +
                "and renamed.",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = palette.muted,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp),
        )

        Box(Modifier.padding(start = 22.dp, top = 16.dp)) {
            EditorialPill(
                text = if (state.report == null) "Choose a file" else "Check another",
                icon = Icons.Rounded.GraphicEq,
                onClick = {
                    picker.launch(arrayOf("audio/*", "application/octet-stream"))
                },
                palette = palette,
            )
        }

        AnimatedVisibility(visible = state.isRunning) {
            Row(
                Modifier.padding(start = 22.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = palette.ink,
                )
                Text(
                    "Decoding and analysing…",
                    fontSize = 13.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        state.error?.let { message ->
            EditorialCard(
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    message,
                    fontSize = 13.sp,
                    color = palette.ink,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        state.report?.let { report ->
            VerdictCard(report, palette)

            EditorialSectionLabel(
                "Spectrum",
                palette,
                Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp),
            )
            EditorialCard(
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Box(Modifier.padding(14.dp)) {
                    SpectrumChart(
                        spectrum = report.spectrum,
                        sampleRate = report.sampleRate,
                        cutoffHz = report.cutoffHz,
                        palette = palette,
                    )
                }
            }

            EditorialSectionLabel(
                "Details",
                palette,
                Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp),
            )
            EditorialCard(
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    DetailRow("File", report.fileName, palette)
                    DetailRow("Format", report.mimeType ?: "unknown", palette)
                    DetailRow("Sample rate", "${report.sampleRate} Hz", palette)
                    DetailRow(
                        "Bit depth",
                        if (report.bitDepth > 0) "${report.bitDepth}-bit" else "not declared",
                        palette,
                    )
                    DetailRow("Channels", channelLabel(report.channelCount), palette)
                    DetailRow("Bitrate", "${report.bitrateKbps} kbps (average)", palette)
                    DetailRow(
                        "Frequency ceiling",
                        "%.1f kHz".format(report.sampleRate / 2000f),
                        palette,
                    )
                    DetailRow(
                        "Content reaches",
                        "%.1f kHz".format(report.cutoffHz / 1000f),
                        palette,
                    )
                    DetailRow(
                        "Cutoff steepness",
                        "%.0f dB".format(report.cutoffSharpnessDb),
                        palette,
                    )
                    DetailRow(
                        "Size",
                        "%.1f MB".format(report.fileSizeBytes / 1_000_000f),
                        palette,
                        last = true,
                    )
                }
            }

            Text(
                "Analysis covers up to the first 90 seconds. A spectrum can't prove " +
                    "where a file came from — treat this as strong evidence, not proof.",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            )
        }

        Box(Modifier.size(24.dp))
    }
}

@Composable
private fun VerdictCard(report: SpectralReport, palette: EditorialPalette) {
    val (title, accent) = when (report.verdict) {
        SpectralReport.Verdict.GENUINE_LOSSLESS ->
            "Looks genuinely lossless" to Color(0xFF2E7D4F)
        SpectralReport.Verdict.LIKELY_TRANSCODE ->
            "Probably a transcode" to Color(0xFFB3261E)
        SpectralReport.Verdict.LOSSY_AS_LABELLED ->
            "Lossy, as labelled" to Color(0xFF8A6D1F)
        SpectralReport.Verdict.INCONCLUSIVE ->
            "Inconclusive" to palette.muted
    }

    EditorialCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, RoundedCornerShape(50)),
                )
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = palette.ink,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                report.explanation,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    palette: EditorialPalette,
    last: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = palette.muted)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
    if (!last) {
        Box(
            Modifier
                .fillMaxWidth()
                .size(1.dp)
                .background(palette.line.copy(alpha = 0.25f)),
        )
    }
}

private fun channelLabel(count: Int): String = when (count) {
    0 -> "unknown"
    1 -> "mono"
    2 -> "stereo"
    else -> "$count channels"
}
