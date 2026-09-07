package com.harmony.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import kotlin.math.log10

/**
 * The averaged spectrum, dB against frequency, with the detected cutoff
 * marked.
 *
 * Frequency is on a LOG axis, matching how hearing works — and, more to the
 * point here, giving the top octaves enough room to see the cliff. On a
 * linear axis everything above 10 kHz is squeezed into the right-hand third,
 * which is exactly where the evidence lives.
 *
 * The cutoff line is the whole reason this chart exists: a lossy encoder
 * leaves a vertical wall, and seeing it is more convincing than any verdict
 * text.
 */
@Composable
fun SpectrumChart(
    spectrum: FloatArray,
    sampleRate: Int,
    cutoffHz: Int,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    if (spectrum.isEmpty()) return

    val minHz = 20f
    val maxHz = (sampleRate / 2f).coerceAtLeast(minHz * 2)
    val minDb = -100f

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp),
        ) {
            val logMin = log10(minHz)
            val logMax = log10(maxHz)
            fun xOf(hz: Float): Float =
                ((log10(hz.coerceIn(minHz, maxHz)) - logMin) / (logMax - logMin)) * size.width
            fun yOf(db: Float): Float =
                (1f - (db.coerceIn(minDb, 0f) - minDb) / -minDb) * size.height

            // Decade gridlines at 100 Hz, 1 kHz, 10 kHz.
            listOf(100f, 1_000f, 10_000f).forEach { hz ->
                if (hz < maxHz) {
                    val x = xOf(hz)
                    drawLine(
                        color = palette.line.copy(alpha = 0.20f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f,
                    )
                }
            }

            val hzPerBin = maxHz / spectrum.size
            val path = Path()
            val fill = Path()
            var started = false
            for (i in spectrum.indices) {
                val hz = (i + 0.5f) * hzPerBin
                if (hz < minHz) continue
                val x = xOf(hz)
                val y = yOf(spectrum[i])
                if (!started) {
                    path.moveTo(x, y)
                    fill.moveTo(x, size.height)
                    fill.lineTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            if (started) {
                fill.lineTo(size.width, size.height)
                fill.close()
                drawPath(
                    fill,
                    Brush.verticalGradient(
                        listOf(palette.accent.copy(alpha = 0.35f), Color.Transparent),
                    ),
                )
                drawPath(path, palette.accent, style = Stroke(width = 2.5f))
            }

            // The cutoff, dashed so it reads as an annotation over the data
            // rather than as part of it.
            if (cutoffHz > 0) {
                val x = xOf(cutoffHz.toFloat())
                drawLine(
                    color = palette.ink,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            listOf("100 Hz", "1 kHz", "10 kHz", "%.0f kHz".format(maxHz / 1000)).forEach {
                Text(it, fontSize = 9.sp, color = palette.muted)
            }
        }

        if (cutoffHz > 0) {
            Text(
                "Dashed line: content stops at %.1f kHz".format(cutoffHz / 1000f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = palette.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
