package com.harmony.app.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

/*
 * The liquid glass navigation bar from Figma (file Bj9Z7C1JD7kaXGK8h5Tw8M,
 * "Navbar · Liquid glass reference" 8:2 and its animated twin 8:4): a ruby
 * to violet bar with rounded bottom corners, a glass pill carried in a notch
 * cut into its top edge, and the 5.4 s timeline's tab change, step for step.
 */

/** A tab's glyph, in the reference's 19 dp icon box (stroke 1.7, round). */
enum class GlassGlyph(internal val path: String, internal val filled: Boolean) {
    /** The reference's Home: Listen. */
    Home("M2.375 7.91667L9.5 1.58333L16.625 7.91667V16.625H2.375V7.91667Z", true),
    /** The reference's Browse grid: Library. */
    Grid(
        "M3.5625 1.58333H5.14583C6.2389 1.58333 7.125 2.46944 7.125 3.5625V5.14583C7.125 6.2389 6.2389 7.125 5.14583 7.125H3.5625" +
            "C2.46944 7.125 1.58333 6.2389 1.58333 5.14583V3.5625C1.58333 2.46944 2.46944 1.58333 3.5625 1.58333Z" +
            "M13.8542 1.58333H15.4375C16.5306 1.58333 17.4167 2.46944 17.4167 3.5625V5.14583C17.4167 6.2389 16.5306 7.125 15.4375 7.125H13.8542" +
            "C12.7611 7.125 11.875 6.2389 11.875 5.14583V3.5625C11.875 2.46944 12.7611 1.58333 13.8542 1.58333Z" +
            "M3.5625 11.875H5.14583C6.2389 11.875 7.125 12.7611 7.125 13.8542V15.4375C7.125 16.5306 6.2389 17.4167 5.14583 17.4167H3.5625" +
            "C2.46944 17.4167 1.58333 16.5306 1.58333 15.4375V13.8542C1.58333 12.7611 2.46944 11.875 3.5625 11.875Z" +
            "M13.8542 11.875H15.4375C16.5306 11.875 17.4167 12.7611 17.4167 13.8542V15.4375C17.4167 16.5306 16.5306 17.4167 15.4375 17.4167H13.8542" +
            "C12.7611 17.4167 11.875 16.5306 11.875 15.4375V13.8542C11.875 12.7611 12.7611 11.875 13.8542 11.875Z",
        false,
    ),
    /** Add: a plus, drawn in the reference's stroke. */
    Plus("M9.5 2.77083V16.2292M2.77083 9.5H16.2292", false),
    Search("M13.4583 7.91667C13.4583 10.9772 10.9772 13.4583 7.91667 13.4583C4.85609 13.4583 2.375 10.9772 2.375 7.91667" +
        "C2.375 4.85609 4.85609 2.375 7.91667 2.375C10.9772 2.375 13.4583 4.85609 13.4583 7.91667ZM12.2708 12.2708L17.4167 17.4167", false),
    Bolt("M11.0833 0.791667L3.16667 11.0833H8.70833L7.91667 18.2083L15.8333 7.125H10.2917L11.0833 0.791667Z", false),
}

/** A destination in the glass bar. */
data class GlassTab(val label: String, val glyph: GlassGlyph)

/** The reference's numbers, in dp. */
private object Glass {
    const val BAR_H = 72f // 96 in the frame, of which 24 is the gesture inset
    const val PILL_W = 104f
    const val PILL_H = 48f
    const val PILL_TOP = -2f // the pill rides 2 above the bar's edge
    const val LEFT = 32f // pill's left edge at the first tab
    const val RIGHT = 23f // and its right margin at the last
    const val ICON = 19f
    const val ICON_TOP = 18f
    const val LEFT_ICON = 29f // an icon left of the pill sits 29 past its slot
    const val RIGHT_ICON = 55f // one right of it, 55 (pill width − 49)
    const val CORNER = 58f
    val Ink = Color(0xFFEDD3E8)
    val Edge = Color(0xFFC96D80).copy(alpha = 0.11f)
    val Backdrop = listOf(0f to Color(0xFF861C15), 0.32f to Color(0xFF501521), 0.62f to Color(0xFF61205E), 1f to Color(0xFF7B218E))
    val Refraction = listOf(0f to Color(0xFF501812), 0.43f to Color(0xFF521C19), 0.62f to Color(0xFF491719), 1f to Color(0xFF37151B))
    val SurfaceFill = Color(117, 30, 87).copy(alpha = 0.04f)
    val SurfaceRim = Color(233, 155, 200).copy(alpha = 0.16f)
    val PillFill = Color(127, 42, 37).copy(alpha = 0.07f)
    val PillRim = Color(255, 210, 195).copy(alpha = 0.22f)
    val PillGlint = Color(255, 204, 219).copy(alpha = 0.04f)
    val PillShadow = Color.Black.copy(alpha = 0.32f)
}

/** The timeline's curves: the carrier's cubic-bezier(.4, 0, .2, 1), the icons' (.22, 1, .36, 1), CSS ease-out. */
private val Carry: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val Reveal: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Where everything is at one instant: the carrier's slot, the pill's squeeze, each tab's label and icon. */
private class GlassPose(
    val carrier: Float,
    val squeeze: Float,
    val label: FloatArray,
    val iconAlpha: FloatArray,
    val iconX: FloatArray,
)

/**
 * The bar's geometry for [count] tabs across [width] dp, the pill wide
 * enough for the longest label: slots spaced so the pill rests 32 dp from
 * the left at the first tab and 23 dp from the right at the last, as in the
 * reference (whose four tabs land exactly on its 78 dp step).
 */
@Stable
private class GlassLayout(val width: Float, val count: Int, val pill: Float) {
    val step = if (count > 1) (width - Glass.LEFT - Glass.RIGHT - pill) / (count - 1) else 0f
    fun slot(i: Float) = Glass.LEFT + step * i
    fun iconX(i: Int, selected: Int) = slot(i.toFloat()) + if (i <= selected) Glass.LEFT_ICON else pill - 49f
    fun rest(selected: Int) = GlassPose(
        carrier = selected.toFloat(),
        squeeze = 1f,
        label = FloatArray(count) { if (it == selected) 1f else 0f },
        iconAlpha = FloatArray(count) { if (it == selected) 0f else 1f },
        iconX = FloatArray(count) { iconX(it, selected) },
    )
}

/**
 * A change of tab as the 8:4 timeline plays it (400 ms; 500 for a longer
 * jump, like its Live → Home): the carrier slides on (.4, 0, .2, 1); the
 * pill squeezes to 97.5 % in the first 100 ms and recovers by the end
 * (ease-out); the old label fades in 100 ms and the new one in the last
 * 120 ms; the icon the pill covers slides 26 dp left and fades, the one it
 * leaves fades back (.22, 1, .36, 1). From wherever an interrupted change was.
 */
private fun pose(from: GlassPose, to: Int, t: Float, d: Float, layout: GlassLayout): GlassPose {
    fun seg(start: Float, end: Float, e: Easing) = e.transform(((t - start) / (end - start)).coerceIn(0f, 1f))
    fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f
    val carry = seg(0f, d, Carry)
    val squeeze = if (t < 100f) mix(from.squeeze, 0.975f, seg(0f, 100f, EaseOut)) else mix(0.975f, 1f, seg(100f, d, EaseOut))
    return GlassPose(
        carrier = mix(from.carrier, to.toFloat(), carry),
        squeeze = squeeze,
        label = FloatArray(layout.count) { i ->
            if (i == to) mix(from.label[i], 1f, seg(d - 120f, d, EaseOut)) else mix(from.label[i], 0f, seg(0f, 100f, EaseOut))
        },
        iconAlpha = FloatArray(layout.count) { i -> mix(from.iconAlpha[i], if (i == to) 0f else 1f, seg(0f, d, Reveal)) },
        iconX = FloatArray(layout.count) { i -> mix(from.iconX[i], layout.iconX(i, to), carry) },
    )
}

private const val FRAME_MS = 16f

private val InterMedium = FontFamily(Font(com.harmony.feature.player.R.font.inter_medium, FontWeight.Medium))

/**
 * The phone's bottom bar: Listen · Library · Add on the liquid glass of the
 * Figma reference. It reaches the bottom of the screen (its 72 dp plus the
 * gesture inset) and its pill rises 2 dp above its edge.
 */
@Composable
fun LiquidGlassNavBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The gesture area the bar covers; the system's navigation inset unless given (the frame's is 24 dp). */
    gestureInset: androidx.compose.ui.unit.Dp? = null,
) {
    val selected = selectedIndex.coerceIn(0, tabs.lastIndex)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontFamily = InterMedium, fontWeight = FontWeight.Medium, fontSize = 12.5.sp,
        letterSpacing = (-0.1).sp, color = Color.White,
    )
    val density = LocalDensity.current
    val labels = remember(tabs, density) { tabs.map { measurer.measure(it.label, labelStyle) } }
    val longest = with(density) { labels.maxOf { it.size.width }.toDp().value }
    val pillWidth = max(Glass.PILL_W, 45f + longest + 14f)
    val glyphs = remember(tabs) { tabs.map { PathParser().parsePathString(it.glyph.path).toPath() } }
    val still = com.harmony.core.ui.theme.reducedMotion

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = remember(maxWidth, tabs.size, pillWidth) { GlassLayout(maxWidth.value, tabs.size, pillWidth) }
        val clock = remember { Animatable(0f) }
        var from by remember(layout) { mutableStateOf(layout.rest(selected)) }
        var target by remember(layout) { mutableStateOf(selected) }
        var duration by remember { mutableStateOf(400f) }
        LaunchedEffect(selected, layout) {
            if (selected != target) {
                // Start from wherever the bar is now, even mid-change.
                from = pose(from, target, clock.value, duration, layout)
                duration = if (abs(selected - from.carrier) > 1.01f) 500f else 400f
                target = selected
                // The tap was a frame ago (it took one to get here): start a frame in, as the timeline does at the tap.
                clock.snapTo(FRAME_MS)
            }
            if (still) clock.snapTo(duration) else clock.animateTo(duration, tween(((duration - clock.value).toInt()).coerceAtLeast(0), easing = LinearEasing))
        }
        val now = pose(from, target, clock.value, duration, layout)
        // The glass, from the bar's edge to the bottom of the screen.
        Canvas(Modifier.matchParentSize()) { drawGlass(layout, now, glyphs, tabs, labels) }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(Glass.BAR_H.dp).selectableGroup()) {
                tabs.forEachIndexed { i, tab ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = i == selected, role = Role.Tab, onClick = { onSelect(i) })
                            .semantics { contentDescription = tab.label },
                    )
                }
            }
            // The bar runs on under the gesture handle to the screen's edge.
            Spacer(
                Modifier.fillMaxWidth().then(
                    if (gestureInset != null) Modifier.height(gestureInset) else Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars),
                ),
            )
        }
    }
}

private fun notch(x: Float, extra: Float): Path = Path().apply {
    // "Open curved shoulder recess" below the bar's edge, its straight floor widened by [extra] for a wider pill.
    val e = extra
    moveTo(x, 0f)
    cubicTo(x + 16f, 0f, x + 16f, 8f, x + 18f, 23f)
    cubicTo(x + 20f, 44f, x + 29f, 54f, x + 50f, 54f)
    lineTo(x + 102f + e, 54f)
    cubicTo(x + 123f + e, 54f, x + 132f + e, 44f, x + 134f + e, 23f)
    cubicTo(x + 136f + e, 8f, x + 136f + e, 0f, x + 152f + e, 0f)
    lineTo(x + 152f + e, -40f)
    lineTo(x, -40f)
    close()
}

private fun edge(x: Float, extra: Float): Path = Path().apply {
    // "Fine cutout edge highlight": the notch's outline, 1.5 dp inside the bar.
    val e = extra
    moveTo(x, 1.5f)
    cubicTo(x + 16f, 1.5f, x + 16f, 9.5f, x + 18f, 24.5f)
    cubicTo(x + 20f, 45.5f, x + 29f, 55.5f, x + 50f, 55.5f)
    lineTo(x + 102f + e, 55.5f)
    cubicTo(x + 123f + e, 55.5f, x + 132f + e, 45.5f, x + 134f + e, 24.5f)
    cubicTo(x + 136f + e, 9.5f, x + 136f + e, 1.5f, x + 152f + e, 1.5f)
}

private fun DrawScope.drawGlass(
    layout: GlassLayout,
    pose: GlassPose,
    glyphs: List<Path>,
    tabs: List<GlassTab>,
    labels: List<androidx.compose.ui.text.TextLayoutResult>,
) {
    val u = density
    // In dp units from here on.
    scale(u, u, pivot = Offset.Zero) {
        val w = layout.width
        val h = size.height / u
        val extra = layout.pill - Glass.PILL_W
        val pillX = layout.slot(pose.carrier)
        val notchX = pillX - 24f // the recess sits 22 + 2 left of the pill

        val bar = Path().apply {
            addRoundRect(
                RoundRect(
                    0f, 0f, w, h,
                    topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = CornerRadius.Zero,
                    bottomLeftCornerRadius = CornerRadius(Glass.CORNER), bottomRightCornerRadius = CornerRadius(Glass.CORNER),
                ),
            )
        }
        val shape = Path.combine(PathOperation.Difference, bar, notch(notchX, extra))
        clipPath(shape) {
            drawRect(Brush.horizontalGradient(*Glass.Backdrop.toTypedArray(), startX = 0f, endX = w), size = Size(w, h))
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.48f), startY = 0f, endY = h), size = Size(w, h))
            drawPath(bar, Glass.SurfaceFill)
            drawPath(bar, Glass.SurfaceRim, style = Stroke(1.4f)) // 0.7 inside: half of a centred 1.4
        }
        drawPath(edge(notchX, extra), Glass.Edge, style = Stroke(0.65f))

        // The refraction light under the pill, then the pill, squeezed about its centre.
        val pillTop = Glass.PILL_TOP
        drawRoundRect(
            Brush.horizontalGradient(*Glass.Refraction.toTypedArray(), startX = pillX, endX = pillX + layout.pill),
            topLeft = Offset(pillX, pillTop), size = Size(layout.pill, Glass.PILL_H), cornerRadius = CornerRadius(24f),
        )
        withTransform({ scale(pose.squeeze, 1f, pivot = Offset(pillX + layout.pill / 2f, pillTop + Glass.PILL_H / 2f)) }) {
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = Glass.PillShadow.toArgb()
                    // blur 7 (CSS) → sigma 3.5 dp; Skia's radius = (sigma − 0.5) / 0.57735, here in dp units scaled by the canvas.
                    maskFilter = android.graphics.BlurMaskFilter(((3.5f * u - 0.5f) / 0.57735f).coerceAtLeast(0.1f) / u, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                val nc = canvas.nativeCanvas
                // Drop shadow 0 4 7, drawn outside the pill only (the pill is glass).
                nc.save()
                val hole = android.graphics.Path().apply { addRoundRect(pillX, pillTop, pillX + layout.pill, pillTop + Glass.PILL_H, 24f, 24f, android.graphics.Path.Direction.CW) }
                nc.clipOutPath(hole)
                nc.drawRoundRect(pillX, pillTop + 4f, pillX + layout.pill, pillTop + 4f + Glass.PILL_H, 24f, 24f, paint)
                nc.restore()
            }
            drawRoundRect(Glass.PillFill, topLeft = Offset(pillX, pillTop), size = Size(layout.pill, Glass.PILL_H), cornerRadius = CornerRadius(24f))
            // Inner glint: inset 0 1 1 at 4 %.
            drawRoundRect(
                Glass.PillGlint, topLeft = Offset(pillX + 0.4f, pillTop + 1.4f), size = Size(layout.pill - 0.8f, Glass.PILL_H - 1.8f),
                cornerRadius = CornerRadius(23.6f), style = Stroke(1f),
            )
            drawRoundRect(
                Glass.PillRim, topLeft = Offset(pillX + 0.4f, pillTop + 0.4f), size = Size(layout.pill - 0.8f, Glass.PILL_H - 0.8f),
                cornerRadius = CornerRadius(23.6f), style = Stroke(0.8f),
            )
            // The selected content, crossfading tab to tab inside the pill.
            clipPath(Path().apply { addRoundRect(RoundRect(pillX, pillTop, pillX + layout.pill, pillTop + Glass.PILL_H, CornerRadius(24f))) }) {
                tabs.indices.forEach { i ->
                    val a = pose.label[i]
                    if (a <= 0.003f) return@forEach
                    glyph(glyphs[i], tabs[i].glyph.filled, Color.White, Color.White, Offset(pillX + 18.2f, pillTop + 14.2f), a)
                    // Text is measured in px; draw it at dp coordinates through the inverse scale.
                    scale(1f / u, 1f / u, pivot = Offset.Zero) {
                        drawText(labels[i], topLeft = Offset((pillX + 44.2f) * u, (pillTop + 14.7f) * u), alpha = a)
                    }
                }
            }
        }

        // The other tabs' icons.
        tabs.indices.forEach { i ->
            val a = pose.iconAlpha[i]
            if (a <= 0.003f) return@forEach
            glyph(glyphs[i], tabs[i].glyph.filled, Color.White, Glass.Ink, Offset(pose.iconX[i], Glass.ICON_TOP), a)
        }
    }
}

private fun DrawScope.glyph(path: Path, filled: Boolean, fill: Color, stroke: Color, at: Offset, alpha: Float) {
    translate(at.x, at.y) {
        if (filled) drawPath(path, fill, alpha = alpha, style = Fill)
        drawPath(path, stroke, alpha = alpha, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
