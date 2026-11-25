package com.harmony.app.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * The liquid glass navigation bar from Figma (file Bj9Z7C1JD7kaXGK8h5Tw8M,
 * "Navbar · Liquid glass reference" 8:2 and its animated twin 8:4): charcoal
 * glass with a mint rim and two soft reflections, the selected tab carried in
 * a glass pill in a notch cut into the bar's top edge, its icon raised over
 * its name, and the 5.4 s timeline's tab changes, step for step.
 */

/** A tab's glyph, in Figma's 22 dp icon box (stroke 1.8, round). */
enum class GlassGlyph(internal val path: String) {
    Listen(
        "M3.66667 12.8333V10.0833C3.66667 8.13841 4.43928 6.27315 5.81455 4.89788C7.18982 3.52262 9.05508 2.75 11 2.75" +
            "C12.9449 2.75 14.8102 3.52262 16.1855 4.89788C17.5607 6.27315 18.3333 8.13841 18.3333 10.0833V12.8333" +
            "M3.66667 11H6.41667V18.3333H3.66667V11ZM15.5833 11H18.3333V18.3333H15.5833V11Z",
    ),
    Library(
        "M8.25 0.916667V3.66667M0.916667 7.33333V21.0833H13.75M9.16667 11.9167H7.33333C6.97073 11.9167 6.61628 12.0242 6.31479 12.2256" +
            "C6.0133 12.4271 5.77831 12.7134 5.63955 13.0484C5.50079 13.3834 5.46449 13.752 5.53523 14.1077C5.60597 14.4633 5.78057 14.79 6.03697 15.0464" +
            "C6.29337 15.3028 6.62004 15.4774 6.97567 15.5481C7.3313 15.6188 7.69992 15.5825 8.03492 15.4438C8.36992 15.305 8.65625 15.07 8.85769 14.7685" +
            "C9.05914 14.4671 9.16667 14.1126 9.16667 13.75V8.25L13.75 7.33333V11.9167M4.58333 3.66667H17.4167V18.3333H4.58333V3.66667Z",
    ),
    Add("M11 4.58333V17.4167M4.58333 11H17.4167"),
}

/** A destination in the glass bar. */
data class GlassTab(val label: String, val glyph: GlassGlyph)

/** The reference's numbers, in dp, at its 393 dp width. */
private object Glass {
    /** 122 in the frame, of which the gesture inset is 24 (the handle sits 12 above its foot). */
    const val BAR_H = 98f
    const val FIRST_CENTRE = 73f // the first tab's centre: 21 + 104 / 2
    const val LAST_FROM_RIGHT = 72f // the last tab's centre from the right edge: 20 + 104 / 2
    const val PILL_W = 92f
    const val PILL_H = 44f
    const val ICON = 22f
    const val ICON_TOP = 11f // a tab's icon at rest (the tab sits 2 above the bar, the icon 13 into it)
    const val LIFT = 7f // the selected one rises this far, its name under it
    const val LABEL_TOP = 28f
    const val CORNER = 30f
    val Page = Color(0xFF111411)
    val Rest = Color(0xFFA4AFA3)
    val Mint = Color(0xFF99E1BC)
    val Backdrop = arrayOf(0f to Color(0xFF253028), 0.34f to Color(0xFF181D19), 0.68f to Color(0xFF202922), 1f to Color(0xFF2A382F))
    val Shade = arrayOf(0f to Color(17, 20, 17).copy(alpha = 0f), 0.46f to Color(17, 20, 17).copy(alpha = 0.1f), 1f to Color(12, 14, 12).copy(alpha = 0.73f))
    val Surface = arrayOf(
        0f to Color(182, 233, 207).copy(alpha = 0.083f), 0.25f to Color(153, 225, 188).copy(alpha = 0.022f),
        0.7f to Color(29, 36, 30).copy(alpha = 0.044f), 1f to Color(12, 14, 12).copy(alpha = 0.28f),
    )
    val SurfaceRim = Color(182, 233, 207).copy(alpha = 0.4f)
    val Edge = arrayOf(
        0f to Color(0xFFB6E9CF).copy(alpha = 0.48f), 0.26f to Color(0xFF99E1BC).copy(alpha = 0.2f),
        0.62f to Color(0xFFB6E9CF).copy(alpha = 0.15f), 1f to Color(0xFFB6E9CF).copy(alpha = 0.47f),
    )
    val LightGlaze = arrayOf(0f to Color(182, 233, 207).copy(alpha = 0.16f), 0.43f to Color(153, 225, 188).copy(alpha = 0.045f), 1f to Color(12, 14, 12).copy(alpha = 0.32f))
    val LightBody = arrayOf(0.066667f to Color(0xFF384E41), 0.43333f to Color(0xFF2D3D33), 0.71667f to Color(0xFF253028), 0.9f to Color(0xFF0C0E0C))
    val PillFill = arrayOf(
        0f to Color(182, 233, 207).copy(alpha = 0.14f), 0.32f to Color(153, 225, 188).copy(alpha = 0.044f),
        0.73f to Color(29, 36, 30).copy(alpha = 0.065f), 1f to Color(12, 14, 12).copy(alpha = 0.19f),
    )
    val PillRim = Color(182, 233, 207).copy(alpha = 0.7f)
}

/** CSS's ease-in-out and ease-out, as the timeline uses them. */
private val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * One tab change as the 8:4 timeline times it, from the tap: the carrier
 * slides for [duration]; the pill stretches to 1.03 by [peak] and comes back;
 * the old tab's icon drops and its name fades by [leave]; the new one's rises
 * and its name shows from [arrive] to the end.
 */
private class Hop(val duration: Float, val peak: Float, val leave: Float, val arrive: Float)

/** Figma's three changes: Listen → Library, Library → Add, Add → Listen (the same either way). */
private fun hopBetween(a: Int, b: Int): Hop = when (setOf(a, b)) {
    setOf(0, 1) -> Hop(450f, 189f, 94.5f, 346.5f)
    setOf(1, 2) -> Hop(470f, 197.4f, 98.7f, 361.9f)
    else -> if (abs(a - b) == 1) Hop(450f, 189f, 94.5f, 346.5f) else Hop(590f, 247.8f, 94.4f, 489.7f)
}

/** Where everything is at one instant: the carrier's slot, the pill's stretch, and how selected each tab looks. */
private class GlassPose(val carrier: Float, val stretch: Float, val lift: FloatArray)

@Stable
private class GlassLayout(val width: Float, val count: Int) {
    private val step = if (count > 1) (width - Glass.FIRST_CENTRE - Glass.LAST_FROM_RIGHT) / (count - 1) else 0f
    fun centre(slot: Float) = Glass.FIRST_CENTRE + step * slot
    fun rest(selected: Int) = GlassPose(selected.toFloat(), 1f, FloatArray(count) { if (it == selected) 1f else 0f })
}

private fun pose(from: GlassPose, to: Int, t: Float, hop: Hop, layout: GlassLayout): GlassPose {
    fun seg(start: Float, end: Float, e: Easing) = e.transform(((t - start) / (end - start)).coerceIn(0f, 1f))
    fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f
    val stretch = if (t < hop.peak) mix(from.stretch, 1.03f, seg(0f, hop.peak, EaseOut))
    else mix(1.03f, 1f, seg(hop.peak, hop.duration, EaseInOut))
    return GlassPose(
        carrier = mix(from.carrier, to.toFloat(), seg(0f, hop.duration, EaseInOut)),
        stretch = stretch,
        lift = FloatArray(layout.count) { i ->
            if (i == to) mix(from.lift[i], 1f, seg(hop.arrive, hop.duration, EaseInOut))
            else mix(from.lift[i], 0f, seg(0f, hop.leave, EaseInOut))
        },
    )
}

private val InterMedium = FontFamily(Font(com.harmony.feature.player.R.font.inter_medium, FontWeight.Medium))
private const val FRAME_MS = 16f

/**
 * The phone's bottom bar: Listen · Library · Add on the liquid glass of the
 * Figma reference. It reaches the bottom of the screen (its 98 dp plus the
 * gesture inset); the notch and its pill sit in its top edge.
 */
@Composable
fun LiquidGlassNavBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The gesture area the bar covers; the system's navigation inset unless given (the frame's is 24 dp). */
    gestureInset: Dp? = null,
) {
    val selected = selectedIndex.coerceIn(0, tabs.lastIndex)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = InterMedium, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 1.3.em, color = Glass.Mint)
    val density = LocalDensity.current
    val labels = remember(tabs, density) { tabs.map { measurer.measure(it.label, labelStyle) } }
    val glyphs = remember(tabs) { tabs.map { PathParser().parsePathString(it.glyph.path).toPath() } }
    val still = com.harmony.core.ui.theme.reducedMotion

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = remember(maxWidth, tabs.size) { GlassLayout(maxWidth.value, tabs.size) }
        val clock = remember { Animatable(0f) }
        var from by remember(layout) { mutableStateOf(layout.rest(selected)) }
        var target by remember(layout) { mutableStateOf(selected) }
        var hop by remember { mutableStateOf(hopBetween(0, 1)) }
        LaunchedEffect(selected, layout) {
            if (selected != target) {
                // From wherever the bar is now, even mid-change.
                from = pose(from, target, clock.value, hop, layout)
                hop = hopBetween(Math.round(from.carrier), selected)
                target = selected
                // The tap was a frame ago (it took one to get here): start a frame in, as the timeline does at the tap.
                clock.snapTo(FRAME_MS)
            } else if (clock.value == 0f) {
                clock.snapTo(hop.duration)
                return@LaunchedEffect
            }
            if (still) clock.snapTo(hop.duration)
            else clock.animateTo(hop.duration, tween((hop.duration - clock.value).toInt().coerceAtLeast(0), easing = LinearEasing))
        }
        val now = pose(from, target, clock.value, hop, layout)
        Canvas(Modifier.matchParentSize()) { drawGlass(layout, now, glyphs, labels) }
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

/** "Open curved shoulder recess" below the bar's edge, for a pill centred at [c]: down to 58, its floor 44..86 of 130. */
private fun notch(c: Float): Path = Path().apply {
    val x = c - 65f
    moveTo(x - 1f, -1f)
    lineTo(x, 0f)
    cubicTo(x + 11f, 0f, x + 10f, 8f, x + 11f, 22f)
    cubicTo(x + 12f, 43f, x + 22f, 58f, x + 44f, 58f)
    lineTo(x + 86f, 58f)
    cubicTo(x + 108f, 58f, x + 118f, 43f, x + 119f, 22f)
    cubicTo(x + 120f, 8f, x + 119f, 0f, x + 130f, 0f)
    lineTo(x + 131f, -1f)
    close()
}

/** "Fine cutout edge highlight": the notch's outline, 1.5 dp inside the bar. */
private fun edge(c: Float): Path = Path().apply {
    val x = c - 65f
    moveTo(x, 1.5f)
    cubicTo(x + 11f, 1.5f, x + 10f, 9.5f, x + 11f, 23.5f)
    cubicTo(x + 12f, 44.5f, x + 22f, 59.5f, x + 44f, 59.5f)
    lineTo(x + 86f, 59.5f)
    cubicTo(x + 108f, 59.5f, x + 118f, 44.5f, x + 119f, 23.5f)
    cubicTo(x + 120f, 9.5f, x + 119f, 1.5f, x + 130f, 1.5f)
}

/** A blurred paint: CSS blur [cssBlur] (Figma's number) in the dp space the canvas is scaled to. */
private fun blurPaint(color: Color, cssBlur: Float, u: Float) = android.graphics.Paint().apply {
    isAntiAlias = true
    this.color = color.toArgb()
    // sigma = blur / 2; Skia: sigma = 0.57735 r + 0.5 (device px). The canvas is scaled by u, and the blur with it.
    maskFilter = android.graphics.BlurMaskFilter(((cssBlur / 2f * u - 0.5f) / 0.57735f).coerceAtLeast(0.1f) / u, android.graphics.BlurMaskFilter.Blur.NORMAL)
}

/** Inner shadows of [shape]: [dy] offset, CSS [blur], [color], drawn inside it. */
private fun DrawScope.innerShadow(shape: Path, bounds: Rect, dy: Float, blur: Float, color: Color) {
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val ring = android.graphics.Path().apply {
            fillType = android.graphics.Path.FillType.EVEN_ODD
            addRect(bounds.left - 40f, bounds.top - 40f, bounds.right + 40f, bounds.bottom + 40f, android.graphics.Path.Direction.CW)
            addPath(shape.asAndroidPath())
        }
        nc.save()
        nc.clipPath(shape.asAndroidPath())
        nc.translate(0f, dy)
        nc.drawPath(ring, blurPaint(color, blur, density))
        nc.restore()
    }
}

private fun DrawScope.drawGlass(layout: GlassLayout, pose: GlassPose, glyphs: List<Path>, labels: List<TextLayoutResult>) {
    val u = density
    scale(u, u, pivot = Offset.Zero) {
        val w = layout.width
        val h = size.height / u
        val c = layout.centre(pose.carrier)

        // The backdrop fills the whole bar, square at its foot; the rim is rounded inside it.
        clipRect(0f, 0f, w, h) {
            drawRect(Brush.horizontalGradient(*Glass.Backdrop, startX = 0f, endX = w), size = Size(w, h))
            drawRect(Brush.verticalGradient(*Glass.Shade, startY = 0f, endY = h), size = Size(w, h))
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                // "Soft mint reflection" and "Deep jade reflection": blurred ellipses (stdDeviation 13.5 and 15).
                nc.drawOval(-48f, -34f, 182f, 73f, blurPaint(Color(0xFFB6E9CF).copy(alpha = 0.19f), 27f, u))
                val jx = w - 393f + 226f
                nc.drawOval(jx, -32f, jx + 231f, 79f, blurPaint(Color(0xFF76AC90).copy(alpha = 0.21f), 30f, u))
            }
        }
        val surface = Path().apply {
            addRoundRect(
                RoundRect(
                    0f, 0f, w, h,
                    topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = CornerRadius.Zero,
                    bottomLeftCornerRadius = CornerRadius(Glass.CORNER), bottomRightCornerRadius = CornerRadius(Glass.CORNER),
                ),
            )
        }
        drawPath(surface, Brush.verticalGradient(*Glass.Surface, startY = 0f, endY = h))
        innerShadow(surface, Rect(0f, 0f, w, h), -2f, 9f, Color(12, 14, 12).copy(alpha = 0.3f))
        innerShadow(surface, Rect(0f, 0f, w, h), 1f, 2f, Color(182, 233, 207).copy(alpha = 0.16f))
        // The rim: 0.9 inside the rounded shape.
        val rim = Path().apply {
            addRoundRect(
                RoundRect(
                    0.45f, 0.45f, w - 0.45f, h - 0.45f,
                    topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = CornerRadius.Zero,
                    bottomLeftCornerRadius = CornerRadius(Glass.CORNER - 0.45f), bottomRightCornerRadius = CornerRadius(Glass.CORNER - 0.45f),
                ),
            )
        }
        drawPath(rim, Glass.SurfaceRim, style = Stroke(0.9f))

        // The highlight along the notch, then the notch itself in the page's colour (only below the bar's edge).
        drawPath(edge(c), Brush.horizontalGradient(*Glass.Edge, startX = c - 65f, endX = c + 65f), style = Stroke(1f))
        clipRect(0f, 0f, w, h) { drawPath(notch(c), Glass.Page) }

        // The refraction light and the pill, stretched about their centre.
        val left = c - Glass.PILL_W / 2f
        val pill = RoundRect(left, 0f, left + Glass.PILL_W, Glass.PILL_H, CornerRadius(22f))
        val pillPath = Path().apply { addRoundRect(pill) }
        withTransform({ scale(pose.stretch, 1f, pivot = Offset(c, Glass.PILL_H / 2f)) }) {
            // CSS 123.68deg across the 92 x 44 box: from its top-left toward its bottom-right.
            val a = Math.toRadians(123.67685507897633)
            val dx = sin(a).toFloat()
            val dy = -cos(a).toFloat()
            val half = (abs(Glass.PILL_W * dx) + abs(Glass.PILL_H * dy)) / 2f
            val mid = Offset(c, Glass.PILL_H / 2f)
            drawPath(pillPath, Brush.linearGradient(*Glass.LightBody, start = mid - Offset(dx, dy) * half, end = mid + Offset(dx, dy) * half))
            drawPath(pillPath, Brush.verticalGradient(*Glass.LightGlaze, startY = 0f, endY = Glass.PILL_H))
            // Shadow 0 5 10, outside the pill only (CSS box-shadow).
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save()
                nc.clipOutPath(pillPath.asAndroidPath())
                nc.drawRoundRect(pill.left, pill.top + 5f, pill.right, pill.bottom + 5f, 22f, 22f, blurPaint(Color(12, 14, 12).copy(alpha = 0.54f), 10f, u))
                nc.restore()
            }
            drawPath(pillPath, Brush.verticalGradient(*Glass.PillFill, startY = 0f, endY = Glass.PILL_H))
            innerShadow(pillPath, Rect(pill.left, pill.top, pill.right, pill.bottom), -1f, 5f, Color(12, 14, 12).copy(alpha = 0.32f))
            innerShadow(pillPath, Rect(pill.left, pill.top, pill.right, pill.bottom), 1f, 2f, Color(182, 233, 207).copy(alpha = 0.24f))
            drawRoundRect(Glass.PillRim, topLeft = Offset(left + 0.5f, 0.5f), size = Size(Glass.PILL_W - 1f, Glass.PILL_H - 1f), cornerRadius = CornerRadius(21.5f), style = Stroke(1f))
        }

        // The tabs: the selected one's icon raised, mint, with its name under it.
        glyphs.indices.forEach { i ->
            val s = pose.lift[i]
            val centre = layout.centre(i.toFloat())
            translate(centre - Glass.ICON / 2f, Glass.ICON_TOP - Glass.LIFT * s) {
                drawPath(glyphs[i], lerp(Glass.Rest, Glass.Mint, s), style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (s > 0.003f) {
                val text = labels[i]
                scale(1f / u, 1f / u, pivot = Offset.Zero) {
                    drawText(text, topLeft = Offset((centre * u) - text.size.width / 2f, Glass.LABEL_TOP * u), alpha = s)
                }
            }
        }
    }
}
