package com.harmony.core.media.metadata

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.harmony.core.media.artwork.ArtworkCache
import com.harmony.core.media.model.MediaCandidate
import com.harmony.core.media.scanner.FileHasher
import com.harmony.core.model.Song
import com.harmony.domain.library.model.ScannedTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [MediaCandidate] into a fully populated [ScannedTrack].
 *
 * Layered extraction, cheapest-first:
 *  1. MediaMetadataRetriever — standard fields, duration, bitrate, sample
 *     rate, channels (API 30+ keys guarded), embedded artwork bytes.
 *  2. TagParsers — the fields MMR can't give us: ReplayGain, embedded
 *     lyrics (USLT / LYRICS comment), FLAC bit depth is derived separately.
 *  3. FileHasher — change-detection hash.
 *
 * A failure at any layer degrades gracefully: a file with unreadable tags
 * still enters the library with filename-derived title.
 *
 * Stable song id: 64-bit FNV-1a of the URI string. Deterministic across
 * rescans (same file -> same id, so favorites/play counts survive), and safe
 * across sources (MediaStore ids and SAF documents live in one id space).
 */
@Singleton
class MetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val artworkCache: ArtworkCache,
    private val fileHasher: FileHasher,
) {

    fun extract(candidate: MediaCandidate): ScannedTrack {
        val uri = Uri.parse(candidate.uri)
        val retriever = MediaMetadataRetriever()
        var mmr: MmrFields = MmrFields()
        try {
            retriever.setDataSource(context, uri)
            mmr = readMmrFields(retriever)
            retriever.embeddedPicture?.let { bytes ->
                mmr = mmr.copy(artworkUri = artworkCache.store(songId(candidate.uri), bytes))
            }
        } catch (_: Exception) {
            // Corrupt/unsupported container: fall through with filename-only data.
        } finally {
            runCatching { retriever.release() }
        }

        val tags = TagParsers.parse(candidate.displayName) {
            context.contentResolver.openInputStream(uri)
        }

        /**
         * MediaMetadataRetriever is not reliable on FLAC.
         *
         * It routinely returns null for artist/album/title on files the tag
         * block describes perfectly well — the codec is supported for
         * playback, the metadata reader just does not pick the Vorbis comment
         * up, and it varies by OEM and by Android version. Every such file
         * then fell through to "<unknown>", and because that is ONE name, the
         * whole untagged half of a library collapsed into a single row in the
         * Artists tab. Which is exactly what "Artists says 8 while Songs is
         * full" looks like.
         *
         * [TagParsers] was already reading the entire tag block here for
         * ReplayGain and lyrics. The values were sitting in this map the whole
         * time; they just were not consulted. MMR still wins when it has an
         * answer — this only fills in what it left empty.
         */
        fun tag(vararg keys: String): String? = keys
            .firstNotNullOfOrNull { key -> tags[key]?.trim()?.takeIf { it.isNotEmpty() } }

        val title = mmr.title
            ?: tag("TITLE", "TIT2")
            ?: candidate.displayName.substringBeforeLast('.')
        val artist = mmr.artist ?: tag("ARTIST", "TPE1") ?: UNKNOWN
        val albumName = mmr.album ?: tag("ALBUM", "TALB") ?: UNKNOWN
        val albumArtist = mmr.albumArtist ?: tag("ALBUMARTIST", "ALBUM ARTIST", "TPE2")
        val genre = mmr.genre ?: tag("GENRE", "TCON")
        val year = mmr.year ?: tag("DATE", "YEAR", "TDRC", "TYER")?.take(4)?.toIntOrNull()
        val trackNumber = mmr.trackNumber
            ?: tag("TRACKNUMBER", "TRCK")?.substringBefore('/')?.trim()?.toIntOrNull()
        val discNumber = mmr.discNumber
            ?: tag("DISCNUMBER", "TPOS")?.substringBefore('/')?.trim()?.toIntOrNull()

        val song = Song(
            id = songId(candidate.uri),
            uri = candidate.uri,
            title = title,
            artist = artist,
            album = albumName,
            // Not albumName: that has already fallen back to "<unknown>", and
            // keying on it would pile unrelated records onto one shelf. Passing
            // null lets albumId() fall back to the folder, which is what it is
            // there for.
            albumId = albumId(album = mmr.album ?: tag("ALBUM", "TALB"), folder = candidate.folder),
            albumArtist = albumArtist,
            composer = mmr.composer ?: tag("COMPOSER", "TCOM"),
            year = year,
            genre = genre,
            discNumber = discNumber,
            trackNumber = trackNumber,
            durationMs = mmr.durationMs ?: 0L,
            bitrateKbps = mmr.bitrateBps?.let { it / 1000 },
            sampleRateHz = mmr.sampleRateHz,
            bitDepth = mmr.bitDepth,
            channels = mmr.channels,
            artworkUri = mmr.artworkUri,
            embeddedLyrics = tags["LYRICS"] ?: tags["UNSYNCEDLYRICS"],
            replayGainTrackDb = ReplayGainTags.trackGainDb(tags),
            replayGainAlbumDb = ReplayGainTags.albumGainDb(tags),
        )

        return ScannedTrack(
            song = song,
            fileSizeBytes = candidate.sizeBytes,
            lastModified = candidate.lastModified,
            fileHash = fileHasher.hash(candidate.uri, candidate.sizeBytes),
            storageVolume = candidate.storageVolume,
        )
    }

    private fun readMmrFields(r: MediaMetadataRetriever): MmrFields {
        fun s(key: Int) = r.extractMetadata(key)?.takeIf { it.isNotBlank() }
        fun i(key: Int) = s(key)?.filter { it.isDigit() }?.toIntOrNull()

        val bitDepth = if (android.os.Build.VERSION.SDK_INT >= 31) {
            i(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
        } else null
        val sampleRate = if (android.os.Build.VERSION.SDK_INT >= 31) {
            i(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
        } else null

        return MmrFields(
            title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            composer = s(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE),
            year = i(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: s(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.toIntOrNull(),
            trackNumber = s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull(),
            discNumber = s(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull(),
            durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            bitrateBps = i(MediaMetadataRetriever.METADATA_KEY_BITRATE),
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            channels = null, // MMR has no channel-count key; DSP pipeline fills this in Phase 5
        )
    }

    private data class MmrFields(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val durationMs: Long? = null,
        val bitrateBps: Int? = null,
        val sampleRateHz: Int? = null,
        val bitDepth: Int? = null,
        val channels: Int? = null,
        val artworkUri: String? = null,
    )

    companion object {
        private const val UNKNOWN = "<unknown>"

        /** FNV-1a 64-bit over the URI; deterministic id shared by all layers. */
        fun songId(uri: String): Long {
            var hash = -0x340d631b7bdddcdbL // FNV offset basis
            for (ch in uri) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L // FNV prime
            }
            return hash
        }

        /**
         * Album identity = the album's NAME, normalised. Nothing else.
         *
         * Two things are deliberately absent from that key.
         *
         * The artist, because keying on it shatters a compilation into one
         * album per performer — precisely what a compilation guarantees will
         * differ. albumArtist is no safer: plenty of rips write it per track
         * rather than per release.
         *
         * The folder, because it only works when a release is stored in one
         * directory. Libraries organised by artist, or by decade, or flat,
         * scatter a compilation's tracks across many folders — and then
         * folder-keying splits the album just as badly as artist-keying did.
         *
         * Normalisation does the rest of the work: case and whitespace are
         * flattened, and trailing disc markers are stripped so a multi-disc
         * set ("Hits 90s CD1", "Hits 90s CD2") becomes one album rather than
         * one per disc. Disc numbers survive on the tracks themselves, so
         * ordering within the album is unaffected.
         *
         * The cost: two genuinely different albums that share a name merge.
         * Untagged files are the dangerous case — every one of them claims
         * the album "Unknown" — so those alone fall back to the folder,
         * which keeps unrelated loose files apart.
         */
        fun albumId(album: String?, folder: String): Long {
            val name = normalizeAlbumName(album)
            return if (name.isEmpty() || name == "unknown") {
                songId("album:unknown:${folder.trim('/').lowercase()}")
            } else {
                songId("album:$name")
            }
        }

        /**
         * Lowercases, collapses whitespace, and strips trailing disc markers
         * — repeatedly, since "Hits (CD 1) [Disc 2]" carries more than one.
         * "Vol"/"Volume" is deliberately NOT stripped: on compilations a
         * volume is usually a separate release, whereas a CD is one part of
         * the same one.
         */
        fun normalizeAlbumName(album: String?): String {
            var name = (album ?: "").trim().lowercase().replace(WHITESPACE, " ")
            while (true) {
                val stripped = name.replace(DISC_SUFFIX, "").trim()
                if (stripped == name) break
                name = stripped
            }
            return name
        }

        private val WHITESPACE = Regex("\\s+")

        private val DISC_SUFFIX = Regex(
            "[\\s\\-\u2013\u2014_]*[\\(\\[]?\\s*(cd|disc|disk)\\s*\\.?\\s*\\d+\\s*[\\)\\]]?\\s*$",
            RegexOption.IGNORE_CASE,
        )
    }
}
