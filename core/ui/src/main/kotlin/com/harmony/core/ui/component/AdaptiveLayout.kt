package com.harmony.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Harmony's screens were designed as single phone columns. On a tablet they
 * work, but stretched across 800 to 1300dp a row of text becomes hard to
 * follow and the bottom bar sits far from the hands. These helpers decide when
 * the tablet layout applies and keep columns at a readable width.
 */

/** Material's "medium" width class starts here. */
private const val WIDE_MIN_WIDTH_DP = 600

/**
 * A phone turned sideways is also 600dp+ wide but only ~360-410dp tall: too
 * short for a side rail of five destinations, and its own landscape layouts
 * already cover it. Requiring some height keeps this to tablets.
 */
private const val WIDE_MIN_HEIGHT_DP = 480

/** Whether the window is tablet-sized: side rail, bounded columns. */
@Composable
fun isWideWindow(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= WIDE_MIN_WIDTH_DP &&
        configuration.screenHeightDp >= WIDE_MIN_HEIGHT_DP
}

/** The widest a single column of content grows on a tablet. */
val ReadableContentMaxWidth: Dp = 880.dp

/**
 * Centres [content] in a column no wider than [maxWidth]. The page around it
 * keeps the shell's field colour, so the margins read as the same surface.
 * On a phone the column is narrower than [maxWidth] and nothing changes.
 */
@Composable
fun ReadableWidth(
    maxWidth: Dp = ReadableContentMaxWidth,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .fillMaxHeight()
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
        ) {
            content()
        }
    }
}
