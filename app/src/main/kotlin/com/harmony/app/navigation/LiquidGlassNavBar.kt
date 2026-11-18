package com.harmony.app.navigation

import androidx.compose.material3.MaterialTheme
import com.harmony.core.ui.theme.Catalog
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

/** Height of the bar's interior: the indicator, the label, and their padding. */
private val BarHeight = 68.dp

/** Material 3's active indicator: a 64 x 32 pill behind the selected icon. */
private val IndicatorWidth = 56.dp
private val IndicatorHeight = 30.dp

/**
 * The app's bottom navigation: a Material 3 navigation bar in the catalogue's
 * colours. Flat and opaque, part of the dock the shell draws under the mini
 * player; the selected destination carries the standard active indicator, a
 * catalogue-blue pill behind its icon, and its label in full ink.
 *
 * The swipe gesture is kept: dragging horizontally walks the selection
 * through destinations, one per item-width of travel, with a haptic tick at
 * each crossing. [palette] supplies the ink and structure colour.
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
    val catalog = Catalog

    // The gesture must read the LIVE selection and callback without
    // restarting pointerInput every time selection changes: restarting
    // mid-drag would cancel the gesture the moment it did anything.
    val currentIndex by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState(onSelect)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(BarHeight)
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
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEachIndexed { index, item ->
                val selected = index == safeIndex
                val indicator by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "nav-indicator-$index",
                )
                val iconTint by animateColorAsState(
                    targetValue = if (selected) catalog.onBlueContainer else palette.muted,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "nav-icon-$index",
                )
                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(IndicatorWidth, IndicatorHeight), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // The pill grows out from the icon, Material's way.
                                    scaleX = 0.5f + 0.5f * indicator
                                    alpha = indicator
                                }
                                .clip(RoundedCornerShape(percent = 50))
                                .background(catalog.blueContainer),
                        )
                        Icon(item.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                    }
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) palette.ink else palette.muted,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}
