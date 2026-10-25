import AVFoundation
import Foundation

/// Which lossless format uncompressed PCM (WAV, AIFF) is stored as.
enum LosslessStorage: String, CaseIterable, Identifiable {
    case flac
    case alac

    var id: String { rawValue }
    var label: String { self == .flac ? "FLAC" : "ALAC (.m4a)" }
}

/// Tags to write into a file.
struct AudioTagValues: Hashable {
    var title: String?
    var artist: String?
    var album: String?
    var albumArtist: String?
    var trackNumber: String?
    var date: String?
    var genre: String?
    var isrc: String?
    var comment: String?
    var cover: Data?

    var isEmpty: Bool {
        [title, artist, album, albumArtist, trackNumber, date, genre, isrc, comment].allSatisfy { ($0 ?? "").isEmpty } && cover == nil
    }
}

enum TranscodeError: LocalizedError {
    case notAudio
    case unsupported(String)
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .notAudio:
            return "This is not an audio file (a web page or another kind of file was downloaded)."
        case .unsupported(let what):
            return what
        case .failed(let why):
            return why
        }
    }
}

/// The result of preparing a file for the library.
struct PreparedAudio {
    let url: URL
    /// "flac", "mp3", "m4a"…
    let container: String
    /// True when the audio was re-encoded (WAV/AIFF to FLAC, say).
    let converted: Bool
    let info: HarmonySourceInfo?
}

/**
 * Harmony's on-device transcoder and tagger, with Apple's codecs only.
 *
 * The rule is the Android app's: nothing is made to look better than it is.
 *
 *   FLAC, ALAC              kept as they are (no re-encode, no loss)
 *   WAV, AIFF, PCM in CAF   compressed losslessly to FLAC or ALAC (half the size)
 *   MP3, AAC                kept as they are; a lossy file is never written as
 *                           FLAC, which would only make it bigger
 *   anything else Core      AAC 256 kbps in .m4a (Opus in CAF, for example)
 *   Audio decodes
 *
 * Tags: FLAC and MP3 through HarmonyTagWriter (C++), M4A through AVFoundation.
 */
final class IosNativeAudioTranscoder {
    static let shared = IosNativeAudioTranscoder()

    /**
     * Moves or converts [file] to [directory]/[baseName].<ext> per the rule
     * above and returns where it ended up. [progress] reports conversions (0..1).
     */
    func prepare(file: URL, into directory: URL, baseName: String, lossless: LosslessStorage,
                 progress: @escaping (Double) -> Void = { _ in }) async throws -> PreparedAudio {
        let container = HarmonyTranscoder.sniffContainer(atPath: file.path)
        guard !container.isEmpty else { throw TranscodeError.notAudio }
        let info = HarmonyTranscoder.inspectFile(at: file)

        switch container {
        case "flac", "mp3":
            return PreparedAudio(url: try move(file, to: directory, base: baseName, ext: container),
                                 container: container, converted: false, info: info)
        case "m4a":
            guard info != nil else { throw TranscodeError.unsupported("iOS cannot read this M4A file.") }
            return PreparedAudio(url: try move(file, to: directory, base: baseName, ext: "m4a"),
                                 container: "m4a", converted: false, info: info)
        case "wav", "aiff", "caf":
            guard let info else { throw TranscodeError.unsupported("iOS cannot read this \(container.uppercased()) file.") }
            if info.lossless {
                let target: HarmonyTranscodeTarget = lossless == .flac ? .flac : .alac
                let ext = lossless == .flac ? "flac" : "m4a"
                let url = try await convert(file, to: directory, base: baseName, ext: ext, target: target, progress: progress)
                return PreparedAudio(url: url, container: ext, converted: true, info: HarmonyTranscoder.inspectFile(at: url))
            }
            let url = try await convert(file, to: directory, base: baseName, ext: "m4a", target: .aac, progress: progress)
            return PreparedAudio(url: url, container: "m4a", converted: true, info: HarmonyTranscoder.inspectFile(at: url))
        case "ogg":
            #if canImport(ffmpegkit)
            let url = try await FFmpegKitTranscoder.convert(file, to: directory, base: baseName, lossless: lossless)
            return PreparedAudio(url: url, container: url.pathExtension, converted: true, info: HarmonyTranscoder.inspectFile(at: url))
            #else
            if info != nil {
                // Newer iOS versions decode Ogg Opus/Vorbis themselves.
                let url = try await convert(file, to: directory, base: baseName, ext: "m4a", target: .aac, progress: progress)
                return PreparedAudio(url: url, container: "m4a", converted: true, info: HarmonyTranscoder.inspectFile(at: url))
            }
            throw TranscodeError.unsupported("This iOS version cannot decode Ogg files. Convert it to FLAC, MP3 or M4A first.")
            #endif
        default:
            throw TranscodeError.unsupported("Unsupported audio container: \(container).")
        }
    }

    /// Writes [tags] into [url] in place.
    func writeTags(_ tags: AudioTagValues, to url: URL) async throws {
        guard !tags.isEmpty else { return }
        let container = HarmonyTranscoder.sniffContainer(atPath: url.path)
        switch container {
        case "flac", "mp3":
            let objc = HarmonyAudioTags()
            objc.title = tags.title
            objc.artist = tags.artist
            objc.album = tags.album
            objc.albumArtist = tags.albumArtist
            objc.trackNumber = tags.trackNumber
            objc.date = tags.date
            objc.genre = tags.genre
            objc.isrc = tags.isrc
            objc.comment = tags.comment
            objc.cover = tags.cover
            try await Task.detached(priority: .utility) {
                try HarmonyTranscoder.write(objc, toFileAtPath: url.path)
            }.value
        case "m4a":
            try await writeMp4Tags(tags, to: url)
        default:
            break  // WAV/AIFF are converted before tagging; nothing else is kept
        }
    }

    // MARK: - Private

    private func move(_ file: URL, to directory: URL, base: String, ext: String) throws -> URL {
        let destination = HarmonyPaths.uniqueFile(in: directory, base: base, extension: ext)
        try FileManager.default.moveItem(at: file, to: destination)
        return destination
    }

    private func convert(_ file: URL, to directory: URL, base: String, ext: String, target: HarmonyTranscodeTarget,
                         progress: @escaping (Double) -> Void) async throws -> URL {
        let destination = HarmonyPaths.uniqueFile(in: directory, base: base, extension: ext)
        try await Task.detached(priority: .userInitiated) {
            try HarmonyTranscoder.transcodeFile(at: file, to: destination, target: target, progress: { fraction in
                DispatchQueue.main.async { progress(fraction) }
            }, isCancelled: nil)
        }.value
        try? FileManager.default.removeItem(at: file)
        return destination
    }

    /// M4A tags: a passthrough export (no re-encode) with the new metadata.
    private func writeMp4Tags(_ tags: AudioTagValues, to url: URL) async throws {
        let asset = AVURLAsset(url: url)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            throw TranscodeError.failed("Could not open the M4A file for tagging.")
        }
        var items: [AVMetadataItem] = []
        func add(_ identifier: AVMetadataIdentifier, _ value: (NSCopying & NSObjectProtocol)?) {
            guard let value else { return }
            let item = AVMutableMetadataItem()
            item.identifier = identifier
            item.value = value
            items.append(item)
        }
        add(.commonIdentifierTitle, tags.title as NSString?)
        add(.commonIdentifierArtist, tags.artist as NSString?)
        add(.commonIdentifierAlbumName, tags.album as NSString?)
        add(.iTunesMetadataAlbumArtist, tags.albumArtist as NSString?)
        add(.iTunesMetadataReleaseDate, tags.date as NSString?)
        add(.iTunesMetadataUserGenre, tags.genre as NSString?)
        add(.iTunesMetadataUserComment, tags.comment as NSString?)
        if let cover = tags.cover {
            let item = AVMutableMetadataItem()
            item.identifier = .commonIdentifierArtwork
            item.value = cover as NSData
            item.dataType = (HarmonyTranscoderImageKind.isPNG(cover) ? kCMMetadataBaseDataType_PNG : kCMMetadataBaseDataType_JPEG) as String
            items.append(item)
        }
        let output = HarmonyPaths.scratch.appendingPathComponent(UUID().uuidString).appendingPathExtension("m4a")
        export.outputURL = output
        export.outputFileType = .m4a
        export.metadata = items
        await export.export()
        guard export.status == .completed else {
            try? FileManager.default.removeItem(at: output)
            throw TranscodeError.failed(export.error?.localizedDescription ?? "Tagging the M4A file failed.")
        }
        _ = try FileManager.default.replaceItemAt(url, withItemAt: output)
    }
}

enum HarmonyTranscoderImageKind {
    static func isPNG(_ data: Data) -> Bool {
        data.starts(with: [0x89, 0x50, 0x4E, 0x47])
    }
}

#if canImport(ffmpegkit)
import ffmpegkit

/**
 * Strategy B: FFmpeg linked into the app as a library (ffmpeg-kit's
 * `ffmpegkit.xcframework`), for containers Apple's codecs don't read.
 *
 * Compiled only when an `ffmpegkit` module is added to the target. Note
 * that the ffmpeg-kit project was retired in 2025 and its prebuilt binaries
 * were withdrawn, so a framework has to come from a maintained fork or your
 * own build; Harmony works without it.
 */
enum FFmpegKitTranscoder {
    static func convert(_ input: URL, to directory: URL, base: String, lossless: LosslessStorage) async throws -> URL {
        // Ogg Vorbis and Opus are lossy: AAC 256 kbps keeps them playable without pretending they are lossless.
        let output = HarmonyPaths.uniqueFile(in: directory, base: base, extension: "m4a")
        let arguments = ["-y", "-i", input.path, "-map_metadata", "0", "-vn", "-c:a", "aac", "-b:a", "256k", output.path]
        return try await withCheckedThrowingContinuation { continuation in
            FFmpegKit.execute(withArgumentsAsync: arguments) { session in
                let code = session?.getReturnCode()
                if ReturnCode.isSuccess(code) {
                    try? FileManager.default.removeItem(at: input)
                    continuation.resume(returning: output)
                } else {
                    try? FileManager.default.removeItem(at: output)
                    continuation.resume(throwing: TranscodeError.failed(session?.getOutput() ?? "FFmpeg failed"))
                }
            }
        }
    }
}
#endif
