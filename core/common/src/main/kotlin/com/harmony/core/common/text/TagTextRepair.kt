package com.harmony.core.common.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Repairs tag text that was decoded with the wrong character set, which is
 * how Romanian titles end up as "ºtefan", "Þara" or "È™tefan".
 *
 * Two mistakes account for nearly all of it:
 *
 *  1. Windows-1250 read as Latin-1. Older Windows taggers wrote Romanian in
 *     the Central European code page but marked the ID3 frame as Latin-1,
 *     so ş ţ ă Ş Ţ Ă come out as º þ ã ª Þ Ã (î and â survive, they share
 *     the same byte in both).
 *  2. UTF-8 read as Latin-1 / Windows-1252. The tagger wrote UTF-8 into a
 *     Latin-1 frame, so ș becomes "È™", ț "È›", ă "Äƒ", î "Ã®".
 *
 * Both are reversible: the text maps back to the original bytes one
 * character per byte, and those bytes are decoded again with the right
 * charset. Text that already holds proper Unicode letters such as ș is never
 * touched, since it can't have come from a one-byte decoding.
 *
 * The output uses the correct comma-below ș and ț rather than the cedilla
 * forms the old code page had.
 */
object TagTextRepair {
    private val windows1250: Charset = Charset.forName("windows-1250")
    private val windows1252: Charset = Charset.forName("windows-1252")

    /** º ª Þ þ: Ş ş Ţ ţ in Windows-1250, almost never meant literally inside a word. */
    private val strongMarkers = setOf('º', 'ª', 'Þ', 'þ')

    /** ã Ã: ă Ă in Windows-1250, but also real Portuguese letters, so they need support. */
    private val weakMarkers = setOf('ã', 'Ã')

    /** Letters a Windows-1250 repair may produce. Any other change means the guess was wrong. */
    private val romanianFromCp1250 = setOf('ş', 'Ş', 'ţ', 'Ţ', 'ă', 'Ă')

    /** Non-ASCII letters that Windows-1250 Romanian read as Latin-1 can produce. */
    private val mojibakeLetters = setOf('ã', 'Ã', 'â', 'Â', 'î', 'Î', 'º', 'ª', 'þ', 'Þ')

    private val portugueseNasal = Regex("[ãÃ][oOeE]")

    /**
     * True when [text] carries an unambiguous sign of Windows-1250 Romanian
     * read as Latin-1. The scanner uses it to treat the other fields of the
     * same file as Romanian too.
     */
    fun hasStrongMarker(text: String?): Boolean =
        text != null && cp1250Candidate(text, romanianHint = false) != null && strongCount(text) > 0

    /**
     * [text] with its charset mistake undone, or unchanged when it shows
     * none. [romanianHint] lets the weaker sign (ã for ă) count: pass it when
     * the phone is in Romanian or another field of the same file was repaired.
     */
    fun repair(text: String?, romanianHint: Boolean = false): String? {
        if (text.isNullOrEmpty()) return text
        utf8Candidate(text)?.let { return modernRomanian(it) }
        cp1250Candidate(text, romanianHint)?.let { return modernRomanian(it) }
        return text
    }

    /** UTF-8 bytes shown as Latin-1/Windows-1252: a lead byte followed by a continuation byte. */
    private fun utf8Candidate(text: String): String? {
        val bytes = originalBytes(text) ?: return null
        if (!hasUtf8Sequence(bytes)) return null
        val decoded = strictDecode(bytes, Charsets.UTF_8) ?: return null
        return decoded.takeIf { it != text }
    }

    private fun cp1250Candidate(text: String, romanianHint: Boolean): String? {
        val strong = strongCount(text)
        val weak = if (portugueseNasal.containsMatchIn(text)) 0 else text.count { it in weakMarkers }
        val convincing = strong > 0 || (weak > 0 && romanianHint)
        if (!convincing) return null
        val bytes = originalBytes(text) ?: return null
        val decoded = strictDecode(bytes, windows1250) ?: return null
        if (decoded == text) return null
        // Only the Romanian letters may change; anything else means it wasn't Windows-1250.
        for (i in text.indices) {
            if (decoded[i] != text[i] && decoded[i] !in romanianFromCp1250) return null
        }
        return decoded
    }

    /**
     * Strong signs, counted carefully:
     *  - º and ª only inside words. After a digit or a lone N they are
     *    ordinals and "número" ("1º", "2ª", "Nº 5").
     *  - þ and Þ only when nothing else in the text is a foreign letter:
     *    in Icelandic ("Þú og ég") they are meant, and come with á é ó ú ð æ.
     */
    private fun strongCount(text: String): Int {
        val foreign = text.any { it.code > 0x7F && it.isLetter() && it !in mojibakeLetters }
        return text.indices.count { i ->
            val c = text[i]
            if (c !in strongMarkers) return@count false
            if (c == 'þ' || c == 'Þ') return@count !foreign
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + 1)
            if (before != null && before.isDigit()) return@count false
            if ((before == 'N' || before == 'n') && text.getOrNull(i - 2)?.isLetter() != true) return@count false
            (before?.isLetter() == true) || (after?.isLetter() == true)
        }
    }

    /** The bytes a one-byte decoding turned into [text], or null if it can't have come from one. */
    private fun originalBytes(text: String): ByteArray? {
        val out = ByteArray(text.length)
        for ((i, c) in text.withIndex()) {
            out[i] = when {
                c.code <= 0xFF -> c.code.toByte()
                else -> {
                    // Windows-1252 puts printable characters (™ › ƒ …) where Latin-1 has controls.
                    val encoded = runCatching { c.toString().toByteArray(windows1252) }.getOrNull()
                    if (encoded == null || encoded.size != 1 || encoded[0] == '?'.code.toByte()) return null
                    encoded[0]
                }
            }
        }
        return out
    }

    private fun hasUtf8Sequence(bytes: ByteArray): Boolean {
        for (i in 0 until bytes.size - 1) {
            val lead = bytes[i].toInt() and 0xFF
            val next = bytes[i + 1].toInt() and 0xFF
            if (lead in 0xC2..0xEF && next in 0x80..0xBF) return true
        }
        return false
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun modernRomanian(text: String): String = text
        .replace('ş', 'ș').replace('Ş', 'Ș')
        .replace('ţ', 'ț').replace('Ţ', 'Ț')
}
