package com.harmony.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType

/**
 * A destination in the bar or the rail. [selectedIcon] is drawn filled when
 * the destination is selected, so the selection never rests on the amber
 * colour alone (accessibility A23).
 */
data class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** The width the rail takes from the page on a tablet. */
val NavRailClearance: Dp = 88.dp

/**
 * The bottom bar on a phone: Listen · Library · Add. Each item is a 48 dp+
 * tab with its label always shown; the selected one has a soft amber pill,
 * a filled icon and a semibold amber label.
 */
@Composable
fun RoomNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val r = Room
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(r.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            NavCell(item, index == selectedIndex, Modifier.weight(1f)) { onSelect(index) }
        }
    }
}

/**
 * The rail on a tablet (600 dp and wider): the same destinations down the
 * left edge, with the utilities (Sound, Settings) at its foot.
 */
@Composable
fun RoomNavRail(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    val r = Room
    Column(
        modifier
            .width(NavRailClearance)
            .fillMaxHeight()
            .background(r.surface)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEachIndexed { index, item ->
                NavCell(item, index == selectedIndex, Modifier.width(80.dp)) { onSelect(index) }
            }
        }
        Spacer(Modifier.weight(1f))
        footer()
    }
}

@Composable
private fun NavCell(item: NavItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val r = Room
    Column(
        modifier
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 32.dp)
                .clip(CircleShape)
                .background(if (selected) r.amberSoft else r.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (selected) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = if (selected) r.onAmberSoft else r.muted,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.label,
            style = RoomType.micro.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = RoomType.bodySmall.letterSpacing,
            ),
            color = if (selected) r.amberText else r.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
