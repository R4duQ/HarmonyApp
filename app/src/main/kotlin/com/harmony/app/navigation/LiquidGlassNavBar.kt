package com.harmony.app.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import kotlin.math.roundToInt

/**
 * The floating capsule that carries the bar, and the smaller capsule that
 * marks the selected destination inside it. iOS uses a fully-rounded pill
 * for both; a percentage radius keeps that true at any bar height.
 */
private val Capsule = RoundedCornerShape(percent = 50)

/** Height of the bar's interior — icon stack plus breathing room. */
private val BarHeight = 62.dp

/** How far the bar floats in from the screen edges. */
private val BarInset = 16.dp

/**
 * The app's bottom navigation, restyled as an iOS-style floating glass bar.
 *
 * This is a drop-in replacement for [EditorialNavBar]: same parameters, same
 * swipe behaviour, different skin. Three things make it read as iOS rather
 * than Material:
 *
 * The bar floats. Instead of a full-bleed strip welded to the bottom of the
 * screen, it is a capsule inset from all three edges, so the page's field
 * colour runs underneath and around it. That gap is what sells the "glass
 * sitting on top of content" illusion — without it the translucency has
 * nothing to be translucent against.
 *
 * The surface is frosted, not opaque. The bar's fill is the section palette
 * blended toward white and held below full alpha, with a bright hairline rim
 * along the edge. Real backdrop blur needs a library (see the note at the
 * bottom of this file); the blend plus rim gets most of the way there and
 * costs nothing.
 *
 * The selection is a capsule that slides. The indicator travels on a spring
 * between destinations rather than cutting, and the selected item's icon
 * scales up very slightly as it lands — iOS leans on that little bit of
 * physics to make the bar feel responsive under the thumb.
 *
 * The swipe gesture is carried over unchanged: dragging horizontally walks
 * the selection through destinations, one per item-width of travel, with a
 * haptic tick at each crossing, and a fast flick can cross several items in
 * one motion.
 */
@Composable
fun LiquidGlassNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val haptics = LocalHapticFeedback.current

    // The gesture must read the LIVE selection and callback without
    // restarting pointerInput every time selection changes — restarting
    // mid-drag would cancel the gesture the moment it did anything.
    val currentIndex by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState(onSelect)

    // Glass is the section's own field colour pulled toward white. Deriving
    // it from the palette rather than hardcoding a grey means the bar still
    // belongs to whichever screen is above it — amber Library and blue EQ
    // each get their own tint of frost instead of one neutral slab.
    val glass = remember(palette.field) {
        // 0.5 read as too transparent in light mode: against the near-white
        // #F4FDFF field, content sliding underneath competed too much with
        // the bar's own icons and labels for contrast. 0.72 keeps genuine
        // see-through — you can still tell something is scrolling under it
        // — while leaving enough of the frosted white on top that the bar
        // reads as its own surface rather than as a smudge on the page.
        lerp(palette.field, Color.White, 0.62f).copy(alpha = 0.72f)
    }
    // The rim: brighter along the top edge, fading out by the bottom. This
    // is the single cheapest cue that a surface is glass rather than paint.
    val rim = remember(palette.ink) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.75f),
                Color.White.copy(alpha = 0.18f),
                palette.ink.copy(alpha = 0.06f),
            ),
        )
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = BarInset, end = BarInset, top = 6.dp, bottom = 10.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .shadow(
                    elevation = 14.dp,
                    shape = Capsule,
                    clip = false,
                    // Opaque — Compose multiplies these by its own
                    // elevation alpha, so a pre-faded colour cancels the
                    // shadow out almost entirely.
                    ambientColor = palette.ink,
                    spotColor = palette.ink,
                )
                .clip(Capsule)
                .background(glass)
                .border(width = 1.dp, brush = rim, shape = Capsule)
                .pointerInput(items.size) {
                    val step = size.width / items.size.toFloat()
                    var startIndex = 0
                    var emitted = 0
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            startIndex = currentIndex
                            emitted = currentIndex
                            total = 0f
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            total += amount
                            // Dragging RIGHT moves forward, following the
                            // finger: the selection travels the way your
                            // thumb travels, which is what the bar's own
                            // left-to-right ordering implies.
                            val steps = (total / step).roundToInt()
                            val target = (startIndex + steps).coerceIn(0, items.lastIndex)
                            if (target != emitted) {
                                emitted = target
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                select(target)
                            }
                        },
                    )
                },
        ) {
            val itemWidth = maxWidth / items.size
            val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)

            // Low bouncy + medium-low stiffness is the closest match to the
            // iOS pill: it overshoots by a hair and settles, rather than
            // easing in flatly. Stiffer than this reads as Material.
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * safeIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "glass-nav-indicator",
            )

            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .clip(Capsule)
                    // Deliberately faint. On iOS the selected pill is barely
                    // darker than the bar — the colour lift on the icon and
                    // label is what actually signals selection, and a heavy
                    // pill here would fight it.
                    .background(palette.ink.copy(alpha = 0.10f))
                    .border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.45f),
                        shape = Capsule,
                    ),
            )

            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                items.forEachIndexed { index, item ->
                    val selected = index == safeIndex

                    val contentColor by animateColorAsState(
                        targetValue = if (selected) palette.ink else palette.muted,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "glass-nav-content-$index",
                    )
                    // A 4% lift, not more. Large enough to feel under the
                    // thumb, small enough that the row of icons still reads
                    // as one aligned set.
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.04f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        label = "glass-nav-icon-scale-$index",
                    )

                    Column(
                        modifier = Modifier
                            .width(itemWidth)
                            .fillMaxHeight()
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                        )
                        Text(
                            item.label,
                            fontSize = 10.sp,
                            // iOS keeps tab labels at one weight and lets
                            // colour carry the state. Swapping weight on
                            // selection also reflows the label's width,
                            // which makes the row twitch as you swipe.
                            fontWeight = FontWeight.Medium,
                            color = contentColor,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            // 2dp, not 3: the label's own line box already
                            // carries leading below the glyphs, so a larger
                            // gap pushes the pair visibly below center.
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
