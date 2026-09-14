package com.harmony.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

private val CardTopRadius = 28.dp

/**
 * The shared shell for a detail page — an album, a playlist.
 *
 * The page reads as a single card that starts below the status bar and runs
 * off the bottom of the screen: [header] is the top of that card and the
 * rows from [content] continue inside the same surface, rather than the
 * header being a separate block with a list stacked under it. That's why
 * the card background lives on the LazyColumn itself and not on the header
 * item — a background per item would show seams between rows wherever the
 * list scrolled, and the point of the treatment is that there are none.
 *
 * As the header scrolls away, a compact bar carrying just [title] fades in
 * over the top. It is a crossfade on scroll offset rather than a pinned
 * `stickyHeader`, because the two states show different things (full
 * artwork block vs. one line of text) and a sticky header can only pin the
 * same composable it scrolled. It only reaches full opacity once the header
 * is genuinely gone, so the title is never printed twice on screen at once.
 */
@Composable
fun DetailCardScaffold(
    title: String,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    var headerHeight by remember { mutableIntStateOf(1) }

    // derivedStateOf so this recomputes on scroll without recomposing the
    // whole screen for every pixel — the value only changes the compact
    // bar, and reading listState directly in composition would invalidate
    // far more than that.
    val collapse by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                headerHeight <= 1 -> 0f
                else -> (listState.firstVisibleItemScrollOffset / headerHeight.toFloat())
                    .coerceIn(0f, 1f)
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(topStart = CardTopRadius, topEnd = CardTopRadius))
                .background(
                    // Brightest at the very top, settling within the first
                    // screenful. A gradient down the whole (unbounded) list
                    // would have no fixed end to run to, so it's kept short
                    // and deliberately stops.
                    Brush.verticalGradient(
                        colors = listOf(
                            glassFill(palette, strength = 0.74f),
                            glassFill(palette, strength = 0.58f),
                        ),
                        endY = 900f,
                    ),
                ),
            contentPadding = contentPadding,
        ) {
            item(key = "detail-header") {
                Box(Modifier.onSizeChanged { headerHeight = it.height.coerceAtLeast(1) }) {
                    header()
                }
            }
            content()
        }

        // Compact bar. Drawn last so it sits above the list, and given the
        // same top radius so it looks like the card's own edge rather than a
        // separate strip laid on top of it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .graphicsLayer { alpha = collapse }
                .clip(RoundedCornerShape(topStart = CardTopRadius, topEnd = CardTopRadius))
                .background(glassFill(palette, strength = 0.80f))
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Hairline under the compact bar, appearing only once it's opaque
        // enough to need separating from the rows sliding beneath it.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .statusBarsPadding()
                .padding(top = 52.dp)
                .height(1.dp)
                .graphicsLayer { alpha = collapse }
                .background(palette.line.copy(alpha = 0.35f)),
        )
    }
}
