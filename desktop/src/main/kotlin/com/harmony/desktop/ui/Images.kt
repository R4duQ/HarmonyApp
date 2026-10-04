package com.harmony.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
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

/** A cover, or a soft gradient with a note while there's none. */
@Composable
fun ArtImage(art: Art?, loader: ImageLoader, shape: Shape, modifier: Modifier = Modifier) {
    val image by produceState(art?.let { loader.cached(it) }, art?.let { loader.key(it) }) {
        value = art?.let { loader.load(it) }
    }
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Accent.PurpleDeep.copy(alpha = 0.55f), Color(0xFF39B9D3).copy(alpha = 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
        }
    }
}
