import Foundation

/**
 * What happens to every finished download, whatever its source:
 *
 *  1. The file is already in Documents/Downloads (Files › On My iPhone ›
 *     Harmony › Downloads) and was checked to be real audio of its format.
 *  2. Tags: what the download knew (a playlist's title, artist, album, ISRC
 *     and cover) is written into the file (Vorbis comments in FLAC, ID3 in
 *     MP3, iTunes atoms in M4A). A file without a title tag gets one from
 *     its name ("Artist - Title"), so the library never shows "Unknown".
 *  3. Library: the song is stored with its format and artwork.
 *  4. Smart Shuffle: its 24-D acoustic vector is measured straight away
 *     (FastAcousticScanner), so it joins Smart Shuffle at once.
 */
@MainActor
enum DownloadPostProcessor {
    /// Returns the new song's id. [onIndexing] fires when the 24-D measurement starts.
    static func finish(file: URL, source: String, known: KnownTrackInfo?, fallbackName: String?,
                       onIndexing: (() -> Void)? = nil) async throws -> Int64 {
        let existing = await LibraryIngest.readTags(file)
        var tags = AudioTagValues()
        if let known {
            tags.title = known.title
            tags.artist = known.artist
            tags.album = known.album
            tags.isrc = known.isrc
            if let cover = known.coverArtURL, existing.artwork == nil {
                tags.cover = try? await URLSession.shared.data(from: cover).0
            }
        } else if existing.title == nil, let fallbackName, !fallbackName.isEmpty {
            let (artist, title) = LibraryIngest.splitArtistTitle(fallbackName)
            tags.title = title
            tags.artist = existing.artist == nil ? artist : nil
        }
        if !tags.isEmpty {
            tags.comment = "Downloaded with Harmony"
            try? await IosNativeAudioTranscoder.shared.writeTags(tags, to: file)
        }
        return try await LibraryIngest.shared.ingest(fileAt: file, source: source, known: known, onIndexing: onIndexing)
    }
}
