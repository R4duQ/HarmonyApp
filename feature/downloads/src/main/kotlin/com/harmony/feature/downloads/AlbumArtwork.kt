package com.harmony.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.harmony.core.ui.theme.Room

/** An album cover from the web, with a quiet record glyph while it loads or when there is none. */
@Composable
fun AlbumArtwork(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val shape = RoundedCornerShape(corner)
    val placeholder = @Composable {
        Box(Modifier.size(size).background(Room.raised), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Album, contentDescription = null, tint = Room.faint, modifier = Modifier.size(size / 2.5f))
        }
    }
    Box(modifier.size(size).clip(shape)) {
        if (url.isNullOrBlank()) placeholder()
        else SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
            loading = { placeholder() },
            error = { placeholder() },
        )
    }
}
