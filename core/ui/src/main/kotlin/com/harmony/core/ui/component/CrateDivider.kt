package com.harmony.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.Catalog
import java.text.Normalizer

/**
 * The record-crate divider: a catalogue-blue index tab carrying a letter,
 * standing on a blue rule that runs across the list, the way the A, B, C
 * cards stand up out of a record shop's crate. Harmony's library is sorted
 * by title, so the tabs mark where each letter starts.
 *
 * Opaque (it carries the page's ground), so it can also be pinned over the
 * top of a scrolling list as the letter you are in.
 */
@Composable
fun CrateDivider(letter: String, modifier: Modifier = Modifier, gutter: Dp = 16.dp) {
    val c = Catalog
    Box(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(c.ground)
            .semantics { heading() },
    ) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(c.blue),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = gutter)
                .size(width = 44.dp, height = 28.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(c.blue),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, style = MaterialTheme.typography.titleMedium, color = c.onBlue, maxLines = 1)
        }
    }
}

/**
 * The divider a title files under: its first letter, uppercased, accents
 * folded (Ăla files under A, Șarpe under S); digits and symbols under "#".
 * Leading "The " is kept: the library sorts by the title as written.
 */
fun crateLetter(title: String): String {
    val first = title.trimStart().firstOrNull() ?: return "#"
    val folded = Normalizer.normalize(first.toString(), Normalizer.Form.NFD)
        .firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        ?.uppercaseChar() ?: return "#"
    return if (folded in 'A'..'Z') folded.toString() else "#"
}
