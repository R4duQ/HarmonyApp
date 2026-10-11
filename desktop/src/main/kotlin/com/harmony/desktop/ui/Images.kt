package com.harmony.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.harmony.desktop.library.CoverCache
import com.harmony.desktop.player.Art
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/** Covers, decoded once and kept while there's room: from the library's files, or from the phone. */
class ImageLoader(private val covers: CoverCache) {
    private val memory = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 300
    })

    fun key(art: Art): String = when (art) {
        is Art.Local -> "f:" + art.track.album + "|" + (art.track.albumArtist ?: art.track.artist) + "|" + art.track.path
        is Art.Url -> "u:" + art.url
    }

    fun cached(art: Art): ImageBitmap? = memory[key(art)]

    suspend fun load(art: Art): ImageBitmap? {
        val k = key(art)
        if (memory.containsKey(k)) return memory[k]
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = when (art) {
                    is Art.Local -> covers.coverFor(art.track)?.readBytes()
                    is Art.Url -> (URL(art.url).openConnection() as HttpURLConnection).run {
                        connectTimeout = 4_000
                        readTimeout = 6_000
                        try {
                            if (responseCode == 200) inputStream.use { it.readBytes() } else null
                        } finally {
                            disconnect()
                        }
                    }
                }
                bytes?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
            }.getOrNull()
        }
        memory[k] = image
        return image
    }
}

/** Gradients for songs without a cover: each album keeps its own, so a library without art still has colour. */
private val Placeholders = listOf(
    listOf(Color(0xFF6D4AFF), Color(0xFF2FB4D8)),
    listOf(Color(0xFFE0578F), Color(0xFF8A4DFF)),
    listOf(Color(0xFFF2994A), Color(0xFFE0578F)),
    listOf(Color(0xFF2BB59A), Color(0xFF3A7BD5)),
    listOf(Color(0xFF5B5FE8), Color(0xFFB26BFF)),
    listOf(Color(0xFFE4A23C), Color(0xFF4CAF7A)),
    listOf(Color(0xFF3A3F8F), Color(0xFF8E54E9)),
    listOf(Color(0xFFDB5A5A), Color(0xFFF2A65A)),
)

private fun placeholderFor(art: Art?): List<Color> {
    val seed = when (art) {
        is Art.Local -> art.track.album + "|" + (art.track.albumArtist ?: art.track.artist)
        is Art.Url -> art.url
        null -> ""
    }
    return Placeholders[(seed.hashCode() and Int.MAX_VALUE) % Placeholders.size]
}

/** A cover, or (while there's none) the album's own gradient with a note. */
@Composable
fun ArtImage(art: Art?, loader: ImageLoader, shape: Shape, modifier: Modifier = Modifier) {
    val image by produceState(art?.let { loader.cached(it) }, art?.let { loader.key(it) }) {
        value = art?.let { loader.load(it) }
    }
    val colors = remember(art) { placeholderFor(art) }
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(colors.map { it.copy(alpha = 0.85f) })),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // A soft light from the top-left, and the note.
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), center = androidx.compose.ui.geometry.Offset.Zero)))
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size((minOf(maxWidth, maxHeight) * 0.34f).coerceIn(14.dp, 64.dp)))
        }
    }
}
