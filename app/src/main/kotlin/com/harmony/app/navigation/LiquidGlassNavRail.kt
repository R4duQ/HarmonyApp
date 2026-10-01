package com.harmony.app.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette

/** Width of the rail's glass capsule. */
val NavRailWidth: Dp = 80.dp

/** Gap between the rail and the screen edge, and between the rail and the page. */
val NavRailInset: Dp = 12.dp

/** Horizontal space the page leaves free for the rail. */
val NavRailClearance: Dp = NavRailWidth + NavRailInset * 2

private val RailShape = RoundedCornerShape(32.dp)
private val PillShape = RoundedCornerShape(22.dp)
private val ItemHeight = 68.dp

/**
 * The tablet form of [LiquidGlassNavBar]: the same frosted glass and sliding
 * selection pill, stood upright at the left edge where a hand holding a
 * tablet can reach it. Phones keep the bottom bar.
 *
 * [selectedIndex] may be -1 on pages that aren't one of the destinations
 * (an album, a playlist, Settings). The rail stays so you can jump straight
 * to another section, and no pill is drawn.
 */
@Composable
fun LiquidGlassNavRail(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    // Same derivations as the bottom bar, so switching a device between
    // the two layouts never changes the look of the glass.
    val glass = remember(palette.field) {
        lerp(palette.field, Color.White, 0.62f).copy(alpha = 0.55f)
    }
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
            .padding(NavRailInset)
            .width(NavRailWidth)
            .shadow(
                elevation = 14.dp,
                shape = RailShape,
                clip = false,
                ambientColor = palette.ink,
                spotColor = palette.ink,
            )
            .clip(RailShape)
            .background(glass)
            .border(width = 1.dp, brush = rim, shape = RailShape)
            .padding(vertical = 8.dp),
    ) {
        if (selectedIndex in items.indices) {
            val indicatorY by animateDpAsState(
                targetValue = ItemHeight * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "glass-rail-indicator",
            )
            Box(
                Modifier
                    .offset(y = indicatorY)
                    .fillMaxWidth()
                    .height(ItemHeight)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(PillShape)
                    .background(palette.ink.copy(alpha = 0.10f))
                    .border(width = 0.5.dp, color = Color.White.copy(alpha = 0.45f), shape = PillShape),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val contentColor by animateColorAsState(
                    targetValue = if (selected) palette.ink else palette.muted,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "glass-rail-content-$index",
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.04f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "glass-rail-icon-scale-$index",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ItemHeight)
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
                            .size(24.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            },
                    )
                    Text(
                        item.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}
