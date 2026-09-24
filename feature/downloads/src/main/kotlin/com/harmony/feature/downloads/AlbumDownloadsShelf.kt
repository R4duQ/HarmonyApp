package com.harmony.feature.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSectionLabel

/**
 * The Discover albums you've started, as notification pills.
 *
 * Two things were wrong before. The rows were bare TextButtons with a
 * Column inside, so they rendered in the Material theme's own primary
 * colour — teal — while every other section of this screen used the
 * editorial palette; the shelf looked like it belonged to a different app.
 * It also took no palette parameter at all, so passing one was impossible
 * rather than merely forgotten. And an album shelf with no covers makes
 * you read titles to find the record you want, when the cover is the thing
 * you'd actually recognise.
 *
 * Now it takes [palette] like every sibling section, uses the same
 * [NotificationPill] as Recent downloads and the edition chooser, and
 * shows the real artwork the journey already carries.
 */
@Composable
fun AlbumDownloadsShelf(
    onOpenAlbum: (String) -> Unit,
    palette: EditorialPalette,
    viewModel: AlbumDownloadViewModel = hiltViewModel(),
) {
    val albums by viewModel.journeys.collectAsStateWithLifecycle()
    val work by viewModel.jobs.collectAsStateWithLifecycle()
    if (albums.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        EditorialSectionLabel(
            "Your album downloads",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        albums.asReversed().forEach { album ->
            val inProgress = work.any { !it.state.isFinished && album.id in it.tags }
            val total = album.tracks.size
            // "In progress" leads when it applies: it is the only part of
            // this line that is about right now rather than a running
            // total, so burying it at the end behind two ratios was
            // exactly backwards.
            val detail = buildList {
                if (inProgress) add("In progress")
                add("${album.availableCount}/$total downloaded")
                add("${album.heardCount}/$total heard")
            }.joinToString(" · ")

            NotificationPill(
                title = album.title,
                detail = detail,
                palette = palette,
                modifier = Modifier.padding(horizontal = 20.dp),
                coverUrl = album.coverUrl,
                onClick = { onOpenAlbum(album.id) },
                action = {
                    PillAction(
                        icon = Icons.Rounded.ChevronRight,
                        contentDescription = "Open ${album.title}",
                        palette = palette,
                        onClick = { onOpenAlbum(album.id) },
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
