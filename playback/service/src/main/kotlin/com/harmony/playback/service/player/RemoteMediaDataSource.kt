package com.harmony.playback.service.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.harmony.core.remote.cache.FetchResponse
import com.harmony.core.remote.cache.SocketFetcher
import com.harmony.domain.playback.RemoteSongs
import com.harmony.playback.service.connect.RemoteMedia
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Reads a computer's song in the phone's queue ([RemoteSongs.SCHEME]):
 * from the phone's own copy when it is whole, else straight from the computer
 * with a byte range (seeking works either way). Anything else goes to
 * [upstream] as before.
 *
 * Plain sockets, as the computer's own downloads use: Android refuses
 * unencrypted HTTP to the usual clients unless the whole app allows it, and
 * the computer is on the home network. While the computer is still
 * converting the song to FLAC (503) it is asked again, for up to
 * [PREPARING_WAIT_MS]; a computer that can't be reached is an error, which
 * the player answers as for any song that won't load (one retry, then on to
 * the next).
 */
@OptIn(UnstableApi::class)
class RemoteMediaDataSource(
    private val remote: RemoteMedia,
    private val upstream: DataSource,
) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var passThrough = false
    private var opened = false
    private var file: RandomAccessFile? = null
    private var response: FetchResponse? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val key = RemoteSongs.keyOf(dataSpec.uri.toString())
        if (key == null) {
            passThrough = true
            return upstream.open(dataSpec)
        }
        transferInitializing(dataSpec)
        val wanted = dataSpec.length
        val local = remote.localFor(key)
        if (local != null) {
            val f = RandomAccessFile(local, "r")
            file = f
            val left = f.length() - dataSpec.position
            if (left < 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            f.seek(dataSpec.position)
            remaining = if (wanted != C.LENGTH_UNSET.toLong()) minOf(wanted, left) else left
        } else {
            val url = remote.urlFor(key) ?: throw IOException("no address for $key")
            val r = fetch(url, dataSpec.position)
            response = r
            input = r.body
            val length = r.headers["content-length"]?.toLongOrNull()
            var left = length ?: C.LENGTH_UNSET.toLong()
            if (r.status == 200 && dataSpec.position > 0) {
                // The whole file came (no ranges): step over what isn't wanted.
                skipFully(r.body, dataSpec.position)
                if (length != null) left = length - dataSpec.position
            }
            remaining = if (wanted != C.LENGTH_UNSET.toLong()) {
                if (left == C.LENGTH_UNSET.toLong()) wanted else minOf(wanted, left)
            } else left
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    /** The song from [offset], waiting while the computer converts it. */
    private fun fetch(url: String, offset: Long): FetchResponse {
        val until = System.currentTimeMillis() + PREPARING_WAIT_MS
        while (true) {
            val r = SocketFetcher().get(url, offset, null)
            when (r.status) {
                200, 206 -> return r
                503 -> {
                    val wait = (r.headers["retry-after"]?.toLongOrNull() ?: 2).coerceIn(1, 10) * 1000
                    r.close()
                    if (System.currentTimeMillis() + wait > until) throw IOException("the computer is still preparing the song")
                    Thread.sleep(wait)
                }
                else -> {
                    r.close()
                    throw IOException("the computer answered ${r.status}")
                }
            }
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        val buf = ByteArray(16 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException()
            left -= n
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passThrough) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val max = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = file?.read(buffer, offset, max) ?: input?.read(buffer, offset, max) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) {
            if (remaining == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
            throw EOFException("the song ended early")
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = if (passThrough) upstream.uri else uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (passThrough) upstream.responseHeaders else emptyMap()

    override fun close() {
        if (passThrough) {
            passThrough = false
            upstream.close()
            return
        }
        runCatching { file?.close() }
        runCatching { response?.close() }
        file = null
        response = null
        input = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val remote: RemoteMedia, private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RemoteMediaDataSource(remote, upstream.createDataSource())
    }

    private companion object {
        const val PREPARING_WAIT_MS = 30_000L
    }
}
