package com.harmony.core.media.metadata

import java.io.InputStream
import java.nio.charset.Charset

/**
 * Why hand-rolled parsers exist here at all:
 *
 * MediaMetadataRetriever covers title/artist/album/track/etc., but has no API
 * for ReplayGain tags, embedded lyrics (USLT), or FLAC bit depth. The usual
 * fixes are JAudioTagger (heavy, java.io.File-centric — awkward with SAF
 * streams) or TagLib over JNI (native build complexity we're saving for the
 * DSP core). Since we only need a handful of fields from two well-specified
 * containers, two compact read-only parsers are the smallest correct tool.
 *
 * Both parsers are defensive: any structural surprise returns what was parsed
 * so far rather than throwing. They only ever read the metadata region, never
 * audio frames.
 *
 * Known limitations (documented, accepted for now):
 *  - ID3v2 unsynchronisation (rare in modern files) is not decoded; affected
 *    frames are skipped.
 *  - Ogg/Opus Vorbis comments (inside Ogg pages, not raw FLAC blocks) are not
 *    yet parsed -> no ReplayGain for .ogg/.opus until the Ogg page walker is
 *    added (tracked as remaining work).
 */
object TagParsers {

    /** Case-insensitive key -> value map of the textual tags we care about. */
    fun parse(displayName: String, open: () -> InputStream?): Map<String, String> {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return try {
            when (ext) {
                "flac" -> open()?.use(::parseFlacVorbisComments) ?: emptyMap()
                "mp3", "aiff", "aif", "wav" -> open()?.use(::parseId3v2) ?: emptyMap()
                else -> emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ------------------------------------------------------------------ FLAC

    /**
     * FLAC layout: "fLaC" magic, then metadata blocks:
     * [1 byte: last-flag(1) + type(7)] [3 bytes: length BE] [payload].
     * Type 4 = VORBIS_COMMENT: [4B vendorLen LE][vendor][4B count LE] then
     * count x ([4B len LE]["KEY=value" UTF-8]).
     */
    internal fun parseFlacVorbisComments(input: InputStream): Map<String, String> {
        val magic = ByteArray(4)
        if (input.read(magic) != 4 || String(magic) != "fLaC") return emptyMap()

        val tags = HashMap<String, String>()
        while (true) {
            val header = ByteArray(4)
            if (input.read(header) != 4) break
            val isLast = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            if (type == 4) { // VORBIS_COMMENT
                val block = readExactly(input, length) ?: break
                var pos = 0
                fun u32le(): Long {
                    if (pos + 4 > block.size) return -1
                    val v = (block[pos].toLong() and 0xFF) or
                        ((block[pos + 1].toLong() and 0xFF) shl 8) or
                        ((block[pos + 2].toLong() and 0xFF) shl 16) or
                        ((block[pos + 3].toLong() and 0xFF) shl 24)
                    pos += 4
                    return v
                }
                val vendorLen = u32le()
                if (vendorLen < 0 || pos + vendorLen > block.size) return tags
                pos += vendorLen.toInt()
                val count = u32le()
                if (count < 0) return tags
                repeat(count.toInt().coerceAtMost(MAX_COMMENTS)) {
                    val len = u32le()
                    if (len < 0 || pos + len > block.size) return tags
                    val comment = String(block, pos, len.toInt(), Charsets.UTF_8)
                    pos += len.toInt()
                    val eq = comment.indexOf('=')
                    if (eq > 0) {
                        tags[comment.substring(0, eq).uppercase()] = comment.substring(eq + 1)
                    }
                }
                return tags // comments found; no need to read further blocks
            } else {
                if (skipExactly(input, length.toLong()) < length) break
            }
            if (isLast) break
        }
        return tags
    }

    // ----------------------------------------------------------------- ID3v2

    /**
     * ID3v2.3/2.4 header: "ID3" [2B version] [1B flags] [4B syncsafe size].
     * Frames: [4B id][4B size (syncsafe in v2.4, plain BE in v2.3)][2B flags].
     * We extract TXXX (user text: RG values live here by convention), USLT
     * (lyrics), and TBPM.
     */
    internal fun parseId3v2(input: InputStream): Map<String, String> {
        val header = readExactly(input, 10) ?: return emptyMap()
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) return emptyMap()
        val majorVersion = header[3].toInt()
        if (majorVersion !in 3..4) return emptyMap()
        val flags = header[5].toInt()
        if (flags and 0x80 != 0) return emptyMap() // unsynchronisation: bail out safely
        val tagSize = syncsafe(header, 6)
        val body = readExactly(input, tagSize) ?: return emptyMap()

        val tags = HashMap<String, String>()
        var pos = 0
        // Skip extended header if present.
        if (flags and 0x40 != 0 && body.size >= 4) {
            val extSize = if (majorVersion == 4) syncsafe(body, 0) else be32(body, 0)
            pos += extSize + if (majorVersion == 3) 4 else 0
        }
        while (pos + 10 <= body.size) {
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break // padding reached
            val frameSize = if (majorVersion == 4) syncsafe(body, pos + 4) else be32(body, pos + 4)
            val frameStart = pos + 10
            if (frameSize <= 0 || frameStart + frameSize > body.size) break
            when (id) {
                "TXXX" -> parseTxxx(body, frameStart, frameSize)?.let { (k, v) ->
                    tags[k.uppercase()] = v
                }
                "USLT" -> parseUslt(body, frameStart, frameSize)?.let { tags["LYRICS"] = it }
                "TBPM" -> decodeTextFrame(body, frameStart, frameSize)?.let { tags["BPM"] = it }
                // The standard text frames, kept under their own frame ids.
                // MediaMetadataRetriever normally reads these, so nothing here
                // is used unless it comes back empty — see MetadataExtractor,
                // which prefers MMR and falls back to this map. The FLAC side
                // has always exposed the equivalent Vorbis comments; this just
                // stops MP3 from being the format without a safety net.
                "TIT2", "TPE1", "TPE2", "TALB", "TCON", "TCOM",
                "TDRC", "TYER", "TRCK", "TPOS",
                -> decodeTextFrame(body, frameStart, frameSize)?.let { tags[id] = it }
            }
            pos = frameStart + frameSize
        }
        return tags
    }

    private fun parseTxxx(body: ByteArray, start: Int, size: Int): Pair<String, String>? {
        val text = decodePair(body, start, size) ?: return null
        return text
    }

    private fun parseUslt(body: ByteArray, start: Int, size: Int): String? {
        if (size < 5) return null
        // [1B encoding][3B language][descriptor \0 lyrics]
        val encoding = body[start].toInt()
        val charset = charsetFor(encoding) ?: return null
        val content = String(body, start + 4, size - 4, charset)
        val sep = content.indexOf('\u0000')
        return if (sep >= 0) content.substring(sep + 1).trimStart('\uFEFF') else content
    }

    private fun decodeTextFrame(body: ByteArray, start: Int, size: Int): String? {
        if (size < 2) return null
        val charset = charsetFor(body[start].toInt()) ?: return null
        return String(body, start + 1, size - 1, charset).trim('\u0000', '\uFEFF')
    }

    /** Decodes "[1B enc] description \0 value" used by TXXX. */
    private fun decodePair(body: ByteArray, start: Int, size: Int): Pair<String, String>? {
        if (size < 2) return null
        val charset = charsetFor(body[start].toInt()) ?: return null
        val text = String(body, start + 1, size - 1, charset)
        val sep = text.indexOf('\u0000')
        if (sep < 0) return null
        val key = text.substring(0, sep).trim('\uFEFF')
        val value = text.substring(sep + 1).trim('\u0000', '\uFEFF')
        return key to value
    }

    private fun charsetFor(encodingByte: Int): Charset? = when (encodingByte) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charset.forName("UTF-16BE")
        3 -> Charsets.UTF_8
        else -> null
    }

    // -------------------------------------------------------------- helpers

    private fun syncsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun be32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        if (count < 0 || count > MAX_TAG_BYTES) return null
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n <= 0) return null
            read += n
        }
        return buffer
    }

    private fun skipExactly(input: InputStream, count: Long): Long {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) break
                remaining -= 1
            } else remaining -= skipped
        }
        return count - remaining
    }

    private const val MAX_COMMENTS = 512
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024 // artwork can inflate ID3 tags
}

/** Interprets ReplayGain values out of a parsed tag map. */
object ReplayGainTags {

    /** e.g. "-6.54 dB" -> -6.54f */
    fun trackGainDb(tags: Map<String, String>): Float? =
        parseDb(tags["REPLAYGAIN_TRACK_GAIN"])

    fun albumGainDb(tags: Map<String, String>): Float? =
        parseDb(tags["REPLAYGAIN_ALBUM_GAIN"])

    private fun parseDb(raw: String?): Float? =
        raw?.trim()
            ?.removeSuffix("dB")?.removeSuffix("DB")?.removeSuffix("db")
            ?.trim()
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?.takeIf { it in -60f..60f } // sanity bound against corrupt tags
}
