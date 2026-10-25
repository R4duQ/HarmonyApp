import AVFoundation
import Foundation
import UIKit

/// What a download or import already knows about a song (from Spotify, Deezer,
/// a search result). It wins over the file's own tags when both exist.
struct KnownTrackInfo: Hashable, Codable {
    var title: String?
    var artist: String?
    var album: String?
    var isrc: String?
    var durationMs: Int?
    var coverArtURL: URL?
}

/// Adds audio files to the library: reads their tags and artwork, stores the
/// song, and measures its 24-D Smart Shuffle vector straight away.
@MainActor
final class LibraryIngest: ObservableObject {
    static let shared = LibraryIngest()

    let store = HarmonyLibraryStore.shared
    /// Bulk indexing of songs without a vector (thermal- and battery-aware).
    let scanner = AcousticScanCoordinator()

    @Published private(set) var trackCount = 0

    private init() {
        scanner.onVector = { [weak self] trackId, vector in
            self?.store.saveVector(trackId: trackId, vector: vector)
            NotificationCenter.default.post(name: .harmonyLibraryChanged, object: nil)
        }
        trackCount = store.trackCount
    }

    /// Re-reads the song count (after songs were removed).
    func refreshCount() {
        trackCount = store.trackCount
    }

    /// Queues every song still missing its vector (on launch, after a big import).
    func indexMissingVectors() {
        let missing = store.tracksWithoutVectors().map { AcousticScanCoordinator.Item(trackId: $0.id, url: $0.url) }
        scanner.enqueue(missing)
    }

    /**
     * Stores the file at [url] and returns the song's id. The file must
     * already be in its final place under Documents, unless [relativePath]
     * says where it is stored ("@<folder>/…" for a folder read in place).
     * [measureNow]: measure its 24-D vector straight away (one download)
     * rather than leaving it to the background indexer (a folder of
     * thousands). [onIndexing] is called when that measurement starts.
     */
    func ingest(fileAt url: URL, source: String, known: KnownTrackInfo? = nil, relativePath: String? = nil,
                measureNow: Bool = true, onIndexing: (() -> Void)? = nil) async throws -> Int64 {
        guard let relative = relativePath ?? HarmonyPaths.relativePath(for: url) else {
            throw IngestError.outsideLibrary
        }
        // Tags, format and artwork off the main thread: a folder scan reads thousands.
        let (draft, artworkJPEG) = await Task.detached(priority: .utility) {
            await Self.describe(fileAt: url, relativePath: relative, source: source, known: known)
        }.value
        let id = try store.upsert(draft)

        if let artworkJPEG {
            let name = "\(id).jpg"
            let artworkFile = HarmonyPaths.artwork.appendingPathComponent(name)
            try? artworkJPEG.write(to: artworkFile, options: .atomic)
            ArtworkCache.shared.forget(artworkFile)
            store.setArtwork(trackId: id, fileName: name)
        }
        trackCount = store.trackCount
        NotificationCenter.default.post(name: .harmonyLibraryChanged, object: nil)
        guard measureNow else { return id }

        // The 24-D vector, now: a single scan is a few tens of milliseconds.
        onIndexing?()
        let result = await Task.detached(priority: .utility) {
            HarmonyFastAcousticScanner.scanFile(at: url, isCancelled: nil)
        }.value
        if let data = result.vector {
            let vector = data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
            store.saveVector(trackId: id, vector: vector)
            NotificationCenter.default.post(name: .harmonyLibraryChanged, object: nil)
        }
        return id
    }

    /// Everything stored about a file, and its artwork as JPEG. Runs on any thread.
    nonisolated static func describe(fileAt url: URL, relativePath relative: String, source: String,
                                     known: KnownTrackInfo?) async -> (TrackDraft, Data?) {
        let tags = await readTags(url)
        let info = HarmonyTranscoder.inspectFile(at: url)
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        let fallbackTitle = url.deletingPathExtension().lastPathComponent
        let (parsedArtist, parsedTitle) = splitArtistTitle(fallbackTitle)

        let draft = TrackDraft(
            relativePath: relative,
            title: firstNonEmpty(known?.title, tags.title, parsedTitle, fallbackTitle) ?? fallbackTitle,
            artist: firstNonEmpty(known?.artist, tags.artist, parsedArtist) ?? "Unknown artist",
            album: firstNonEmpty(known?.album, tags.album) ?? "",
            albumArtist: tags.albumArtist,
            trackNumber: tags.trackNumber,
            discNumber: nil,
            durationMs: info.map { Int(($0.durationSeconds * 1000).rounded()) } ?? tags.durationMs ?? known?.durationMs ?? 0,
            sampleRate: Int(info?.sampleRate ?? 0),
            bitDepth: info?.bitDepth ?? 0,
            channels: info?.channels ?? 0,
            codec: info?.codec ?? url.pathExtension.uppercased(),
            lossless: info?.lossless ?? false,
            fileSize: size,
            source: source,
            isrc: known?.isrc
        )

        // Artwork: the one we were given, else the file's own.
        var artwork = tags.artwork
        if artwork == nil, let coverURL = known?.coverArtURL {
            artwork = try? await URLSession.shared.data(from: coverURL).0
        }
        let jpeg = artwork.flatMap { UIImage(data: $0) }.flatMap { $0.jpegData(compressionQuality: 0.88) }
        return (draft, jpeg)
    }

    enum IngestError: LocalizedError {
        case outsideLibrary

        var errorDescription: String? {
            "The file must be inside Harmony's folder before it can join the library."
        }
    }

    // MARK: - Tags

    struct FileTags {
        var title: String?
        var artist: String?
        var album: String?
        var albumArtist: String?
        var trackNumber: Int?
        var durationMs: Int?
        var artwork: Data?
    }

    /// Reads the tags AVFoundation understands (ID3, iTunes, Vorbis comments in FLAC).
    nonisolated static func readTags(_ url: URL) async -> FileTags {
        let asset = AVURLAsset(url: url)
        var tags = FileTags()
        if let duration = try? await asset.load(.duration), duration.isNumeric {
            tags.durationMs = Int((CMTimeGetSeconds(duration) * 1000).rounded())
        }
        var items: [AVMetadataItem] = (try? await asset.load(.commonMetadata)) ?? []
        for format in (try? await asset.load(.availableMetadataFormats)) ?? [] {
            items += (try? await asset.loadMetadata(for: format)) ?? []
        }
        for item in items {
            let key = item.commonKey?.rawValue ?? (item.identifier?.rawValue ?? "")
            switch key {
            case AVMetadataKey.commonKeyTitle.rawValue:
                if tags.title == nil { tags.title = try? await item.load(.stringValue) }
            case AVMetadataKey.commonKeyArtist.rawValue:
                if tags.artist == nil { tags.artist = try? await item.load(.stringValue) }
            case AVMetadataKey.commonKeyAlbumName.rawValue:
                if tags.album == nil { tags.album = try? await item.load(.stringValue) }
            case AVMetadataKey.commonKeyArtwork.rawValue:
                if tags.artwork == nil { tags.artwork = try? await item.load(.dataValue) }
            default:
                let id = item.identifier?.rawValue.lowercased() ?? ""
                if tags.albumArtist == nil, id.hasSuffix("albumartist") || id.hasSuffix("tpe2") || id.hasSuffix("aart") {
                    tags.albumArtist = try? await item.load(.stringValue)
                }
                if tags.trackNumber == nil, id.hasSuffix("tracknumber") || id.hasSuffix("trck") {
                    if let s = try? await item.load(.stringValue) {
                        tags.trackNumber = Int(s.split(separator: "/").first ?? "")
                    }
                }
            }
        }
        return tags
    }

    nonisolated static func firstNonEmpty(_ values: String?...) -> String? {
        values.compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty }
    }

    /// "Artist - Title" file names, as most downloads are named.
    nonisolated static func splitArtistTitle(_ name: String) -> (String?, String?) {
        let cleaned = name.replacingOccurrences(of: #"^\s*\d{1,3}\s*[-._)]\s*"#, with: "", options: .regularExpression)
        for dash in [" - ", " – ", " — "] {
            if let range = cleaned.range(of: dash) {
                let artist = String(cleaned[..<range.lowerBound]).trimmingCharacters(in: .whitespaces)
                let title = String(cleaned[range.upperBound...]).trimmingCharacters(in: .whitespaces)
                if !artist.isEmpty, !title.isEmpty { return (artist, title) }
            }
        }
        return (nil, cleaned.isEmpty ? nil : cleaned)
    }
}
