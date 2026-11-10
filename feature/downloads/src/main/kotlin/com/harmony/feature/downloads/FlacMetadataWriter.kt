package com.harmony.feature.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The one step that writes a downloaded FLAC's final tags.
 *
 * A provider (and the runtime, when asked to embed) may have tagged the file
 * already; what Harmony's metadata selection decides is written last, here,
 * and wins: title, artist, album, album artist, ISRC, and for an album its
 * own numbering and date. Only metadata blocks are rewritten. The audio
 * frames after them are copied byte for byte and STREAMINFO (with the MD5 of
 * the decoded audio) is kept as it is, so tagging can never change what the
 * file decodes to.
 */
internal object FlacMetadataWriter {
    private const val FLAC_BLOCK_VORBIS_COMMENT = 4
    private const val FLAC_BLOCK_PICTURE = 6
    private const val FLAC_FRONT_COVER_TYPE = 3
    private const val FLAC_MAX_METADATA_BLOCK_BYTES = 0xFFFFFF
    private const val FLAC_MAX_VORBIS_COMMENTS = 16_384

    private class RawFlacMetadataBlock(
        val type: Int,
        val payload: ByteArray,
    )

    /** A front cover to embed. */
    class Artwork(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val depth: Int,
    )

    /**
     * The tags Harmony's own metadata selection decides. A blank or zero field
     * is left as the file has it.
     */
    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        val isrc: String = "",
        val trackNumber: Int = 0,
        val totalTracks: Int = 0,
        val discNumber: Int = 0,
        val totalDiscs: Int = 0,
        val date: String = "",
    ) {
        val isEmpty: Boolean
            get() = title.isBlank() && artist.isBlank() && album.isBlank() && albumArtist.isBlank() && isrc.isBlank() &&
                trackNumber <= 0 && discNumber <= 0 && date.isBlank()
    }

    /**
     * Rewrites only FLAC metadata blocks. Audio frames after the original
     * metadata boundary are copied byte-for-byte, so album tagging cannot alter
     * the decoded audio or turn a verified lossless file into a transcode.
     */
    fun rewrite(source: File, output: File, tags: Tags, artwork: Artwork?): Boolean {
        val blocks = mutableListOf<RawFlacMetadataBlock>()
        var audioOffset = 0L

        RandomAccessFile(source, "r").use { raf ->
            val signature = ByteArray(4)
            raf.readFully(signature)
            if (!signature.contentEquals(byteArrayOf(0x66, 0x4C, 0x61, 0x43))) return false

            var last = false
            while (!last) {
                if (raf.filePointer + 4L > raf.length()) return false
                val header = raf.readUnsignedByte()
                last = (header and 0x80) != 0
                val type = header and 0x7F
                val length = (raf.readUnsignedByte() shl 16) or
                    (raf.readUnsignedByte() shl 8) or
                    raf.readUnsignedByte()
                if (type == 127 || raf.filePointer + length.toLong() > raf.length()) return false
                val payload = ByteArray(length)
                raf.readFully(payload)
                blocks += RawFlacMetadataBlock(type, payload)
            }
            audioOffset = raf.filePointer
        }

        if (blocks.isEmpty() || blocks.first().type != 0 || blocks.first().payload.size != 34) return false

        val existingComments = mutableListOf<String>()
        var vendor = "Harmony"
        blocks.filter { it.type == FLAC_BLOCK_VORBIS_COMMENT }.forEach { block ->
            parseVorbisCommentBlock(block.payload)?.let { parsed ->
                if (vendor == "Harmony" && parsed.first.isNotBlank()) vendor = parsed.first
                existingComments += parsed.second
            }
        }

        // Each tag Harmony decides replaces every spelling of it the file has;
        // every other tag (genre, ReplayGain, lyrics, the provider's ids…) stays.
        val decided = LinkedHashMap<String, String>()
        val replaceKeys = HashSet<String>()
        fun decide(value: String, key: String, vararg aliases: String) {
            if (value.isBlank()) return
            decided[key] = value.trim()
            replaceKeys += key
            replaceKeys += aliases
        }
        decide(tags.title, "TITLE")
        decide(tags.artist, "ARTIST")
        decide(tags.album, "ALBUM")
        // The album's own artist when it is known (a compilation, a featured
        // guest); the track artist only when nothing better is.
        decide(tags.albumArtist.ifBlank { if (tags.album.isNotBlank()) tags.artist else "" }, "ALBUMARTIST", "ALBUM_ARTIST", "ALBUM ARTIST")
        decide(tags.isrc, "ISRC")
        if (tags.trackNumber > 0) decide(tags.trackNumber.toString(), "TRACKNUMBER", "TRACK")
        if (tags.totalTracks > 0) decide(tags.totalTracks.toString(), "TRACKTOTAL", "TOTALTRACKS")
        if (tags.discNumber > 0) decide(tags.discNumber.toString(), "DISCNUMBER", "DISC")
        if (tags.totalDiscs > 0) decide(tags.totalDiscs.toString(), "DISCTOTAL", "TOTALDISCS")
        decide(tags.date, "DATE")
        val mergedComments = existingComments.filter { comment ->
            comment.substringBefore('=', "").trim().uppercase(Locale.ROOT) !in replaceKeys
        }.toMutableList()
        decided.forEach { (key, value) -> mergedComments += "$key=$value" }

        val commentBlock = RawFlacMetadataBlock(
            FLAC_BLOCK_VORBIS_COMMENT,
            buildVorbisCommentBlock(vendor, mergedComments),
        )

        val rebuilt = mutableListOf<RawFlacMetadataBlock>()
        var commentInserted = false
        for (block in blocks) {
            when {
                block.type == FLAC_BLOCK_VORBIS_COMMENT -> {
                    if (!commentInserted) {
                        rebuilt += commentBlock
                        commentInserted = true
                    }
                }
                block.type == FLAC_BLOCK_PICTURE && artwork != null && flacPictureType(block.payload) == FLAC_FRONT_COVER_TYPE -> {
                    // Replace only the front cover. Back covers/artist images/etc.
                    // remain untouched.
                }
                else -> rebuilt += block
            }
        }
        if (!commentInserted) {
            rebuilt.add(1.coerceAtMost(rebuilt.size), commentBlock)
        }
        if (artwork != null) {
            val picture = RawFlacMetadataBlock(FLAC_BLOCK_PICTURE, buildFlacPictureBlock(artwork))
            val commentIndex = rebuilt.indexOfFirst { it.type == FLAC_BLOCK_VORBIS_COMMENT }
            rebuilt.add((commentIndex + 1).coerceAtLeast(1).coerceAtMost(rebuilt.size), picture)
        }

        if (rebuilt.any { it.payload.size > FLAC_MAX_METADATA_BLOCK_BYTES }) return false

        output.outputStream().buffered().use { out ->
            out.write(byteArrayOf(0x66, 0x4C, 0x61, 0x43))
            rebuilt.forEachIndexed { index, block ->
                val header = block.type or if (index == rebuilt.lastIndex) 0x80 else 0
                out.write(header)
                val length = block.payload.size
                out.write((length ushr 16) and 0xFF)
                out.write((length ushr 8) and 0xFF)
                out.write(length and 0xFF)
                out.write(block.payload)
            }

            RandomAccessFile(source, "r").use { raf ->
                raf.seek(audioOffset)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
        return output.isFile && output.length() > 42L
    }

    internal fun parseVorbisCommentBlock(payload: ByteArray): Pair<String, List<String>>? {
        var offset = 0
        fun readLength(): Int? {
            if (offset + 4 > payload.size) return null
            val value = (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                ((payload[offset + 2].toInt() and 0xFF) shl 16) or
                ((payload[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            return value.takeIf { it >= 0 }
        }

        val vendorLength = readLength() ?: return null
        if (offset + vendorLength > payload.size) return null
        val vendor = String(payload, offset, vendorLength, StandardCharsets.UTF_8)
        offset += vendorLength

        val count = readLength() ?: return null
        if (count > FLAC_MAX_VORBIS_COMMENTS) return null
        val comments = ArrayList<String>(count)
        repeat(count) {
            val length = readLength() ?: return null
            if (offset + length > payload.size) return null
            comments += String(payload, offset, length, StandardCharsets.UTF_8)
            offset += length
        }
        return vendor to comments
    }

    internal fun buildVorbisCommentBlock(vendor: String, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.ifBlank { "Harmony" }.toByteArray(StandardCharsets.UTF_8)
        writeLe32(out, vendorBytes.size)
        out.write(vendorBytes)
        writeLe32(out, comments.size)
        comments.forEach { comment ->
            val bytes = comment.toByteArray(StandardCharsets.UTF_8)
            writeLe32(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun buildFlacPictureBlock(artwork: Artwork): ByteArray {
        val out = ByteArrayOutputStream(artwork.bytes.size + 128)
        val mime = artwork.mimeType.toByteArray(StandardCharsets.US_ASCII)
        val description = "Cover (front)".toByteArray(StandardCharsets.UTF_8)
        writeBe32(out, FLAC_FRONT_COVER_TYPE)
        writeBe32(out, mime.size)
        out.write(mime)
        writeBe32(out, description.size)
        out.write(description)
        writeBe32(out, artwork.width.coerceAtLeast(0))
        writeBe32(out, artwork.height.coerceAtLeast(0))
        writeBe32(out, artwork.depth.coerceAtLeast(0))
        writeBe32(out, 0)
        writeBe32(out, artwork.bytes.size)
        out.write(artwork.bytes)
        return out.toByteArray()
    }

    private fun flacPictureType(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        return ((payload[0].toInt() and 0xFF) shl 24) or
            ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)
    }

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
