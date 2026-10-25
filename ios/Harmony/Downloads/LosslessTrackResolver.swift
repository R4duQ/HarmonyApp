import Foundation

/// A song from an imported playlist (Spotify, Deezer, a pasted list).
struct ImportTrack: Identifiable, Hashable, Codable {
    var id = UUID()
    var title: String
    var artist: String
    var album: String?
    var durationMs: Int?
    var isrc: String?
    var coverArtURL: URL?

    var query: String { "\(artist) - \(title)" }
}

/**
 * The Smart Lossless Matcher: finds each imported song on Soulseek, downloads
 * the best lossless copy and adds it to the library, one song at a time.
 *
 * For every song: already in the library (by ISRC, else by title and artist)
 * means nothing to do. Otherwise "Artist - Title" is searched with the song's
 * length as the target (results more than 6 s off are another edit and are
 * dropped; within 2 s rank up), FLAC first. The best four sources are tried
 * in turn, each given a few seconds to start; if every one only queued us,
 * the best queued source is waited on patiently. The finished file is tagged
 * with the playlist's metadata and cover, and its 24-D vector is measured.
 */
@MainActor
final class LosslessTrackResolver: ObservableObject {
    static let shared = LosslessTrackResolver()

    enum State: Equatable {
        case waiting
        case inLibrary
        case searching
        case downloading(peer: String)
        case indexing
        case done
        case notFound
        case failed(String)

        var label: String {
            switch self {
            case .waiting: return "Waiting"
            case .inLibrary: return "Already in your library"
            case .searching: return "Searching Soulseek"
            case .downloading(let peer): return "Downloading from \(peer)"
            case .indexing: return "Indexing 24D vector"
            case .done: return "Added"
            case .notFound: return "No lossless copy found"
            case .failed(let why): return why
            }
        }

        var finished: Bool {
            switch self {
            case .inLibrary, .done, .notFound, .failed: return true
            default: return false
            }
        }
    }

    struct Item: Identifiable, Equatable {
        let track: ImportTrack
        var state: State = .waiting
        var transferId: UInt32?
        var trackId: Int64?
        var id: UUID { track.id }
    }

    @Published private(set) var items: [Item] = []
    @Published private(set) var running = false
    @Published var playlistName = ""
    /// FLAC only (default) or FLAC with an MP3 fallback.
    @Published var allowMp3Fallback = false

    private var worker: Task<Void, Never>?

    var summary: String {
        let added = items.filter { $0.state == .done }.count
        let present = items.filter { $0.state == .inLibrary }.count
        let missing = items.filter { $0.state == .notFound }.count
        return "\(items.count) songs · \(added) added · \(present) already here · \(missing) not found"
    }

    func load(_ tracks: [ImportTrack], name: String) {
        cancel()
        playlistName = name
        let store = HarmonyLibraryStore.shared
        items = tracks.map { track in
            var item = Item(track: track)
            if let existing = store.findTrack(title: track.title, artist: track.artist, isrc: track.isrc) {
                item.state = .inLibrary
                item.trackId = existing.id
            }
            return item
        }
    }

    func start() {
        guard !running else { return }
        running = true
        worker = Task { [weak self] in
            guard let self else { return }
            guard await SoulseekService.shared.ensureConnected() else {
                self.markRemaining(.failed("Connect to Soulseek first (Downloads › Soulseek)."))
                self.running = false
                return
            }
            for index in self.items.indices {
                if Task.isCancelled { break }
                guard !self.items[index].state.finished else { continue }
                await self.resolve(at: index)
            }
            self.running = false
        }
    }

    func cancel() {
        worker?.cancel()
        worker = nil
        for item in items {
            if let transfer = item.transferId, !item.state.finished { SoulseekService.shared.cancel(transfer) }
        }
        running = false
    }

    // MARK: - One song

    private func resolve(at index: Int) async {
        let track = items[index].track
        set(index, .searching)
        let format: HarmonySlskFormat = allowMp3Fallback ? .preferFlac : .flacOnly
        let target = (track.durationMs ?? 0) / 1000
        var candidates = await SoulseekService.shared.search(track.query, mode: .balanced, format: format, targetDurationSeconds: target)
        if candidates.isEmpty {
            candidates = await SoulseekService.shared.search(track.query, mode: .deep, format: format, targetDurationSeconds: target)
        }
        // Only results that really are this song: the title must be in the file name.
        let titleKey = HarmonyLibraryStore.matchKey(title: track.title, artist: "").split(separator: "|").last.map(String.init) ?? ""
        candidates = candidates.filter { candidate in
            let leaf = HarmonyLibraryStore.matchKey(title: candidate.leafName, artist: "")
            return titleKey.isEmpty || leaf.contains(titleKey)
        }
        guard !candidates.isEmpty else {
            set(index, .notFound)
            return
        }

        let fileName = "\(track.artist) - \(track.title)"
        var queuedSources: [HarmonySlskCandidate] = []
        var lastError = "No source finished the download"
        for candidate in candidates.prefix(4) {
            if Task.isCancelled { return }
            set(index, .downloading(peer: candidate.username))
            let outcome = await SoulseekService.shared.download(candidate, title: track.title, patient: false, fileName: fileName,
                                                                onStart: { [weak self] id in self?.setTransfer(index, id) })
            switch outcome {
            case .downloaded(let url):
                await finish(index: index, file: url, track: track)
                return
            case .failed(let why, let queued):
                lastError = why
                if queued { queuedSources.append(candidate) }
            case .cancelled:
                set(index, .failed("Cancelled"))
                return
            }
        }
        if let best = queuedSources.first, !Task.isCancelled {
            set(index, .downloading(peer: best.username))
            if case .downloaded(let url) = await SoulseekService.shared.download(best, title: track.title, patient: true, fileName: fileName,
                                                                                  onStart: { [weak self] id in self?.setTransfer(index, id) }) {
                await finish(index: index, file: url, track: track)
                return
            }
        }
        set(index, .failed(lastError))
    }

    private func finish(index: Int, file: URL, track: ImportTrack) async {
        set(index, .indexing)
        let transfer = items.indices.contains(index) ? items[index].transferId : nil
        if let transfer { SoulseekService.shared.setIndexing(transfer, indexing: true, indexed: false) }
        defer {
            if let transfer {
                let ok = items.indices.contains(index) && items[index].state == .done
                SoulseekService.shared.setIndexing(transfer, indexing: false, indexed: ok)
            }
        }
        var tags = AudioTagValues(title: track.title, artist: track.artist, album: track.album, isrc: track.isrc)
        if let cover = track.coverArtURL {
            tags.cover = try? await URLSession.shared.data(from: cover).0
        }
        try? await IosNativeAudioTranscoder.shared.writeTags(tags, to: file)
        let known = KnownTrackInfo(title: track.title, artist: track.artist, album: track.album, isrc: track.isrc,
                                   durationMs: track.durationMs, coverArtURL: track.coverArtURL)
        do {
            let id = try await LibraryIngest.shared.ingest(fileAt: file, source: "Soulseek", known: known)
            if items.indices.contains(index) { items[index].trackId = id }
            set(index, .done)
        } catch {
            set(index, .failed(error.localizedDescription))
        }
    }

    private func setTransfer(_ index: Int, _ id: UInt32) {
        guard items.indices.contains(index) else { return }
        items[index].transferId = id
    }

    private func set(_ index: Int, _ state: State) {
        guard items.indices.contains(index) else { return }
        items[index].state = state
    }

    private func markRemaining(_ state: State) {
        for index in items.indices where !items[index].state.finished {
            items[index].state = state
        }
    }
}
