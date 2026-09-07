package com.harmony.app.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import kotlin.math.roundToInt

/** One destination in [EditorialNavBar]. */
data class NavItem(val label: String, val icon: ImageVector)

/**
 * The app's bottom navigation, in the editorial style.
 *
 * Two things distinguish it from NavigationBar. It takes the current
 * section's palette, so the bar belongs to the page above it instead of
 * being a dark slab under four differently-coloured screens. And the whole
 * strip is swipeable: dragging horizontally walks the selection through the
 * destinations, one per item-width of travel, with a haptic tick at each
 * crossing. Tapping still works exactly as before — the swipe is an
 * addition, not a replacement, since a gesture is undiscoverable on its own.
 *
 * A drag can cross several items in one motion (the step count is computed
 * from total travel, not from a single threshold), so a fast flick from
 * Library to Settings works without four separate swipes.
 */
@Composable
fun EditorialNavBar(
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

    Column(
        modifier
            .fillMaxWidth()
            .background(palette.field),
    ) {
        // Hairline, because bar and page share a field color and would
        // otherwise merge into one another.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.line.copy(alpha = 0.25f)),
        )

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
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
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * selectedIndex.coerceIn(0, items.lastIndex),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "nav-indicator",
            )

            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp, vertical = 9.dp)
                    .background(palette.accent, RoundedCornerShape(14.dp)),
            )

            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
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
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (selected) palette.onAccent else palette.muted,
                            modifier = Modifier.size(21.dp),
                        )
                        Text(
                            item.label,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) palette.onAccent else palette.muted,
                            // 2dp, not 3: the label's own line box already
                            // carries leading below the glyphs, so a larger
                            // gap pushes the pair visibly below center.
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
