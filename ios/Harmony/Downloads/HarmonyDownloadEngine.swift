import Foundation

/// One download as the Downloads tab shows it, whatever its source.
struct DownloadItem: Identifiable, Equatable {
    enum Status: String {
        case searching = "Searching"
        case queued = "Queued"
        case connecting = "Connecting"
        case downloading = "Downloading"
        case indexing24D = "Indexing 24D"
        case completed = "Completed"
        case failed = "Failed"
        case paused = "Paused"
        case cancelled = "Cancelled"

        /// Still going (or waiting its turn).
        var isActive: Bool { [.searching, .queued, .connecting, .downloading, .indexing24D].contains(self) }
    }

    enum Origin: Equatable {
        case soulseek(UInt32)
        case link(UUID)
        case lookup(UUID)
    }

    let origin: Origin
    var title: String
    var artist: String
    var album: String
    /// "Soulseek · alice", "archive.org", "Playlist import".
    var source: String
    /// "FLAC", "HI-RES", "MP3 320".
    var formatBadge: String
    /// 0...1.
    var progress: Double
    var bytes: Int64
    var totalBytes: Int64
    var bytesPerSecond: Double
    var status: Status
    var detail: String?
    /// Measured for Smart Shuffle and in the library.
    var indexed: Bool

    var id: String {
        switch origin {
        case .soulseek(let id): return "slsk-\(id)"
        case .link(let id): return "link-\(id.uuidString)"
        case .lookup(let id): return "find-\(id.uuidString)"
        }
    }

    var progressPct: Int { Int((progress * 100).rounded()) }

    var canPause: Bool {
        if case .soulseek = origin { return [.queued, .connecting, .downloading].contains(status) }
        return false
    }
    var canResume: Bool { status == .paused }
    var canRetry: Bool {
        switch origin {
        case .soulseek, .link: return status == .failed
        case .lookup: return false
        }
    }
    var canCancel: Bool {
        switch origin {
        case .lookup: return false
        default: return status.isActive || status == .paused
        }
    }
}

/**
 * Harmony's download engine on iOS: one queue and one live telemetry for
 * every source.
 *
 *  - Soulseek (SlskEngine, C++): search, peer resolution (direct or through
 *    the server when the peer is behind NAT), the peer's upload queue,
 *    byte-offset resume of partial files from any peer that has the same
 *    file, timeouts, and at most [SoulseekService.maxConcurrent] transfers
 *    at once (the others wait for a slot). Pause keeps the bytes; Resume
 *    and Retry continue from them.
 *  - Direct links: a background URLSession, which keeps downloading even
 *    with Harmony closed.
 *  - Playlist import: the lossless matcher's searches.
 *
 * Every finished file goes through DownloadPostProcessor (tags, library,
 * 24-D vector). IosBackgroundTransferGuard keeps the transfers alive with
 * the screen locked.
 */
@MainActor
enum HarmonyDownloadEngine {
    /// Everything to show, newest first: searches, then transfers.
    static func items() -> [DownloadItem] {
        let soulseek = SoulseekService.shared
        let links = StreamIngestManager.shared
        let resolver = LosslessTrackResolver.shared
        var items: [DownloadItem] = []
        if resolver.running {
            for item in resolver.items where item.state == .searching {
                items.append(DownloadItem(
                    origin: .lookup(item.id), title: item.track.title, artist: item.track.artist,
                    album: item.track.album ?? "", source: "Playlist import", formatBadge: "FLAC",
                    progress: 0, bytes: 0, totalBytes: 0, bytesPerSecond: 0, status: .searching,
                    detail: "Looking for the best lossless copy", indexed: false))
            }
        }
        items += soulseek.transfers.map(item(for:))
        items += links.jobs.map(item(for:))
        return items
    }

    /// Downloads still running or waiting (the tab's badge).
    static func activeCount() -> Int {
        items().filter { $0.status.isActive }.count
    }

    // MARK: - Controls

    static func pause(_ item: DownloadItem) {
        if case .soulseek(let id) = item.origin { SoulseekService.shared.pause(id) }
    }

    static func resume(_ item: DownloadItem) {
        if case .soulseek(let id) = item.origin { SoulseekService.shared.resume(id) }
    }

    static func retry(_ item: DownloadItem) {
        switch item.origin {
        case .soulseek(let id):
            SoulseekService.shared.retry(id)
        case .link(let id):
            let links = StreamIngestManager.shared
            if let job = links.jobs.first(where: { $0.id == id }) {
                links.clear(id)
                _ = links.enqueue(job.source.absoluteString)
            }
        case .lookup:
            break
        }
    }

    static func cancel(_ item: DownloadItem) {
        switch item.origin {
        case .soulseek(let id): SoulseekService.shared.cancel(id)
        case .link(let id): StreamIngestManager.shared.cancel(id)
        case .lookup: break
        }
    }

    static func clearFinished() {
        SoulseekService.shared.clearFinished()
        StreamIngestManager.shared.clearFinished()
    }

    // MARK: - Mapping

    private static func item(for t: SoulseekTransfer) -> DownloadItem {
        let (parsedArtist, parsedTitle) = LibraryIngest.splitArtistTitle(t.title)
        var status: DownloadItem.Status
        var detail: String?
        if t.paused && !t.suspended {
            status = .paused
            detail = t.fraction > 0 ? "Paused at \(Int(t.fraction * 100))%; Resume continues from there" : "Paused"
        } else if t.waitingForSlot || t.suspended {
            status = .queued
            detail = t.suspended ? "Continues when Harmony is back" : "Waiting for a free slot (\(SoulseekService.maxConcurrent) at a time)"
        } else {
            switch t.stage {
            case .connectingPeer, .requested, .accepted:
                status = .connecting
                detail = t.stage == .connectingPeer ? "Reaching \(t.username)" : "Asking \(t.username) for the file"
            case .queued:
                status = .queued
                detail = t.queuePlace > 0 ? "In \(t.username)'s queue, #\(t.queuePlace)" : "In \(t.username)'s queue"
            case .transferring:
                status = .downloading
            case .verifying:
                status = .downloading
                detail = "Checking that it is real audio"
            case .done:
                status = t.indexing ? .indexing24D : .completed
                detail = t.indexing ? "Measuring its 24-D vector for Smart Shuffle" : nil
            case .failed:
                status = .failed
                detail = t.message.isEmpty ? "The download failed" : t.message
            case .cancelled:
                status = .cancelled
            @unknown default:
                status = .connecting
            }
        }
        return DownloadItem(
            origin: .soulseek(t.id),
            title: t.known?.title ?? parsedTitle ?? t.title,
            artist: t.known?.artist ?? parsedArtist ?? "",
            album: t.known?.album ?? "",
            source: "Soulseek · \(t.username)",
            formatBadge: t.formatBadge,
            progress: t.stage == .done ? 1 : t.fraction,
            bytes: Int64(t.bytes),
            totalBytes: Int64(t.total),
            bytesPerSecond: status == .downloading ? t.bytesPerSecond : 0,
            status: status,
            detail: detail,
            indexed: t.indexed
        )
    }

    private static func item(for job: StreamIngestManager.Job) -> DownloadItem {
        let (artist, title) = LibraryIngest.splitArtistTitle(job.title)
        let status: DownloadItem.Status
        var detail: String? = job.message
        switch job.stage {
        case .queued: status = .queued
        case .downloading: status = .downloading
        case .verifying, .converting, .tagging:
            status = .downloading
            detail = detail ?? job.stage.rawValue
        case .indexing: status = .indexing24D
        case .done: status = .completed
        case .failed: status = .failed
        case .cancelled: status = .cancelled
        }
        let ext = job.source.pathExtension.uppercased()
        let audio = ["FLAC", "MP3", "M4A", "AAC", "WAV", "AIFF", "AIF", "ALAC"]
        return DownloadItem(
            origin: .link(job.id),
            title: title ?? job.title,
            artist: artist ?? "",
            album: "",
            source: job.source.host ?? "Link",
            formatBadge: audio.contains(ext) ? ext : "AUDIO",
            progress: job.fraction,
            bytes: job.bytes,
            totalBytes: job.totalBytes,
            bytesPerSecond: job.stage == .downloading ? job.bytesPerSecond : 0,
            status: status,
            detail: detail,
            indexed: job.stage == .done
        )
    }
}
