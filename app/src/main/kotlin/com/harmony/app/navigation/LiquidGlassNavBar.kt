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

/**
 * One stroke of a tab's glyph, in Figma's 22 dp icon box: its path, stroke,
 * and the layer's box (its centre is where its landing wobble turns).
 */
class GlassStroke internal constructor(
    internal val path: String,
    internal val width: Float,
    internal val cap: StrokeCap,
    internal val join: StrokeJoin,
    internal val box: Rect,
)

/** A tab's glyph: Figma's vectors, and how each settles when its tab is chosen ([Settle]). */
enum class GlassGlyph(internal val strokes: List<GlassStroke>) {
    Listen(
        listOf(
            GlassStroke(
                "M2.75 12.2V11.1C2.75 6.53998 6.44 2.84998 11 2.84998C15.56 2.84998 19.25 6.53998 19.25 11.1V12.2" +
                    "M2.75 12.2H4.85C5.97 12.2 6.7 13.02 6.7 14.1V17.55C6.7 18.43 6 19.15 5.1 19.15H4.45C3.51 19.15 2.75 18.39 2.75 17.45V12.2Z" +
                    "M19.25 12.2H17.15C16.03 12.2 15.3 13.02 15.3 14.1V17.55C15.3 18.43 16 19.15 16.9 19.15H17.55C18.49 19.15 19.25 18.39 19.25 17.45V12.2Z",
                1.85f, StrokeCap.Round, StrokeJoin.Miter, Rect(2.75f, 2.85f, 19.25f, 19.15f),
            ),
        ),
    ),
    Library(
        listOf(
            // The rear card, then the front one with its note.
            GlassStroke("M2.54999 6.79999V17.05C2.54999 18.43 3.66999 19.55 5.04999 19.55H14.9", 1.7f, StrokeCap.Butt, StrokeJoin.Miter, Rect(2.55f, 6.8f, 14.9f, 19.55f)),
            GlassStroke(
                "M13.5 12.35V6.95001L16.1 6.15001M13.5 12.35C13.5 13.16 12.72 13.7 11.75 13.7C10.81 13.7 10.15 13.19 10.15 12.5" +
                    "C10.15 11.75 10.86 11.2 11.86 11.2C12.8 11.2 13.5 11.67 13.5 12.35Z" +
                    "M8.30001 2.45001H17.1C18.4 2.45001 19.45 3.50001 19.45 4.80001V14.3C19.45 15.6 18.4 16.65 17.1 16.65H8.30001" +
                    "C7.00001 16.65 5.95001 15.6 5.95001 14.3V4.80001C5.95001 3.50001 7.00001 2.45001 8.30001 2.45001Z",
                1.7f, StrokeCap.Butt, StrokeJoin.Miter, Rect(5.95f, 2.45f, 19.45f, 16.65f),
            ),
        ),
    ),
    Add(listOf(GlassStroke("M11 3.54999V18.45M3.54999 11H18.45", 2.04f, StrokeCap.Butt, StrokeJoin.Round, Rect(3.55f, 3.55f, 18.45f, 18.45f)))),
}

/** A destination in the glass bar. */
data class GlassTab(val label: String, val glyph: GlassGlyph)

/** The reference's numbers, in dp, at its 393 dp width. */
private object Glass {
    /** 110 in the frame, of which the gesture inset is 24 (the handle sits 12 above its foot). */
    const val BAR_H = 86f
    const val FIRST_CENTRE = 73f // the first tab's centre: 21 + 104 / 2
    const val LAST_FROM_RIGHT = 72f // the last tab's centre from the right edge: 20 + 104 / 2
    const val PILL_W = 92f
    const val PILL_H = 44f
    const val ICON = 22f
    const val ICON_TOP = 11f // a tab's icon at rest (the tab sits 2 above the bar, the icon 13 into it)
    const val LIFT = 7f // the selected one rises this far, its name under it
    const val LABEL_TOP = 28f
    const val CORNER = 27f
    val Page = Color(0xFF111411)
    val Rest = Color(0xFFA4AFA3)
    val Mint = Color(0xFF99E1BC)
    val Backdrop = arrayOf(0f to Color(0xFF253028), 0.34f to Color(0xFF181D19), 0.68f to Color(0xFF202922), 1f to Color(0xFF2A382F))
    val Shade = arrayOf(0f to Color(17, 20, 17).copy(alpha = 0f), 0.46f to Color(17, 20, 17).copy(alpha = 0.1f), 1f to Color(12, 14, 12).copy(alpha = 0.73f))
    val Surface = arrayOf(
        0f to Color(182, 233, 207).copy(alpha = 0.083f), 0.25f to Color(153, 225, 188).copy(alpha = 0.022f),
        0.7f to Color(29, 36, 30).copy(alpha = 0.044f), 1f to Color(12, 14, 12).copy(alpha = 0.28f),
    )
    /** The bar's 0.9 rim, top to bottom. */
    val SurfaceRim = arrayOf(
        0f to Color(0xFFB6E9CF).copy(alpha = 0.4f), 0.18f to Color(0xFF99E1BC).copy(alpha = 0.19f), 0.55f to Color(0xFFA4AFA3).copy(alpha = 0.1f),
        0.83f to Color(0xFF99E1BC).copy(alpha = 0.17f), 1f to Color(0xFFB6E9CF).copy(alpha = 0.28f),
    )
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
    /** The pill's 1 dp rim, top to bottom. */
    val PillRim = arrayOf(
        0f to Color(0xFFB6E9CF).copy(alpha = 0.7f), 0.22f to Color(0xFF99E1BC).copy(alpha = 0.38f), 0.57f to Color(0xFFA4AFA3).copy(alpha = 0.14f),
        0.82f to Color(0xFF99E1BC).copy(alpha = 0.22f), 1f to Color(0xFFB6E9CF).copy(alpha = 0.37f),
    )
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

/**
 * A property's keys after a tab lands, in ms from the landing: the first
 * segment eases out, the rest ease in and out, as the timeline has them.
 */
private class Keys(val times: FloatArray, val values: FloatArray, val rest: Float) {
    fun at(t: Float): Float {
        if (t <= 0f) return rest
        var from = 0f
        var a = rest
        for (i in times.indices) {
            if (t < times[i]) {
                val f = (t - from) / (times[i] - from)
                return a + (values[i] - a) * (if (i == 0) EaseOut else EaseInOut).transform(f)
            }
            from = times[i]
            a = values[i]
        }
        return values.last()
    }
}

/** How one stroke settles when its tab is chosen: turn (rad), scale and shift, about its box's centre. */
private class Settle(
    val rotate: Keys? = null,
    val scaleX: Keys? = null,
    val scaleY: Keys? = null,
    val shiftX: Keys? = null,
    val shiftY: Keys? = null,
)

private fun keys(times: FloatArray, vararg values: Float, rest: Float = 0f) = Keys(times, values, rest)

/** The 8:4 timeline's landings (Library at 22.037 %, Add at 48.148 %, Listen at 81.667 % of 5.4 s), per stroke. */
private fun settleOf(glyph: GlassGlyph): List<Settle> = when (glyph) {
    // The cards knock against each other and settle.
    GlassGlyph.Library -> {
        val rear = floatArrayOf(160f, 300f, 440f)
        val front = floatArrayOf(140f, 285f, 430f)
        listOf(
            Settle(rotate = keys(rear, -0.095993f, 0.029671f, 0f), shiftX = keys(rear, -1.5f, 0.22f, 0f), shiftY = keys(rear, 0.85f, -0.26f, 0f)),
            Settle(rotate = keys(front, 0.139626f, -0.040143f, 0f), shiftX = keys(front, 0.95f, -0.3f, 0f), shiftY = keys(front, -2.15f, 0.55f, 0f)),
        )
    }
    // The plus swells and turns a quarter, overshooting; turned, it is the same plus.
    GlassGlyph.Add -> {
        val scale = keys(floatArrayOf(135f, 290f, 420f), 1.17f, 0.97f, 1f, rest = 1f)
        listOf(Settle(rotate = keys(floatArrayOf(150f, 270f, 400f), 1.082104f, 1.692969f, 1.570796f), scaleX = scale, scaleY = scale))
    }
    // The headphones tip, narrow and bob.
    GlassGlyph.Listen -> {
        val t = floatArrayOf(190f, 340f, 530f)
        listOf(Settle(rotate = keys(t, 0.139626f, -0.06545f, 0f), scaleX = keys(t, 0.91f, 0.96f, 1f, rest = 1f), shiftY = keys(t, -1.3f, 0.35f, 0f)))
    }
}

/** The longest landing, after which the bar is still. */
private const val SETTLE_MS = 540f

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
    val glyphs = remember(tabs) {
        tabs.map { tab -> tab.glyph.strokes.map { PathParser().parsePathString(it.path).toPath() } }
    }
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
                clock.snapTo(hop.duration + SETTLE_MS)
                return@LaunchedEffect
            }
            // The change, then the new tab's icon settling.
            val end = hop.duration + SETTLE_MS
            if (still) clock.snapTo(end)
            else clock.animateTo(end, tween((end - clock.value).toInt().coerceAtLeast(0), easing = LinearEasing))
        }
        val now = pose(from, target, clock.value, hop, layout)
        val settling = clock.value - hop.duration
        Canvas(Modifier.matchParentSize()) { drawGlass(layout, now, tabs, glyphs, labels, target, if (still) 0f else settling) }
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

private fun DrawScope.drawGlass(
    layout: GlassLayout,
    pose: GlassPose,
    tabs: List<GlassTab>,
    glyphs: List<List<Path>>,
    labels: List<TextLayoutResult>,
    landed: Int,
    settling: Float,
) {
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
        drawPath(rim, Brush.verticalGradient(*Glass.SurfaceRim, startY = 0f, endY = h), style = Stroke(0.9f))

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
            // The pill's Glass effect (refraction 0.67) dims what shows through it by about a third, as Figma renders it.
            drawPath(pillPath, Color.Black.copy(alpha = 0.32f))
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
            drawRoundRect(
                Brush.verticalGradient(*Glass.PillRim, startY = 0f, endY = Glass.PILL_H),
                topLeft = Offset(left + 0.5f, 0.5f), size = Size(Glass.PILL_W - 1f, Glass.PILL_H - 1f), cornerRadius = CornerRadius(21.5f), style = Stroke(1f),
            )
        }

        // The tabs: the selected one's icon raised, mint, with its name under it.
        glyphs.indices.forEach { i ->
            val s = pose.lift[i]
            val centre = layout.centre(i.toFloat())
            translate(centre - Glass.ICON / 2f, Glass.ICON_TOP - Glass.LIFT * s) {
                val strokes = tabs[i].glyph.strokes
                val settles = if (i == landed && settling > 0f) settleOf(tabs[i].glyph) else null
                strokes.forEachIndexed { k, stroke ->
                    val m = settles?.getOrNull(k)
                    val pivot = stroke.box.center
                    withTransform({
                        if (m != null) {
                            translate(m.shiftX?.at(settling) ?: 0f, m.shiftY?.at(settling) ?: 0f)
                            rotate(Math.toDegrees((m.rotate?.at(settling) ?: 0f).toDouble()).toFloat(), pivot)
                            scale(m.scaleX?.at(settling) ?: 1f, m.scaleY?.at(settling) ?: 1f, pivot)
                        }
                    }) {
                        drawPath(glyphs[i][k], lerp(Glass.Rest, Glass.Mint, s), style = Stroke(stroke.width, cap = stroke.cap, join = stroke.join))
                    }
                }
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
