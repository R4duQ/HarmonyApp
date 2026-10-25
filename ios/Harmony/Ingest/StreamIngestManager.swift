import Foundation

/**
 * Link downloads: paste (or share to Harmony) a direct link to an audio
 * file (a Bandcamp or label download, archive.org, your own server or NAS)
 * and it lands in the library, checked, losslessly compressed when it is
 * WAV/AIFF, tagged and measured for Smart Shuffle.
 *
 *   Downloading -> Checking the file -> Converting to FLAC (WAV/AIFF only)
 *   -> Tagging -> Indexing 24D vector -> In your library
 *
 * Transfers run in a background URLSession, so they continue with Harmony
 * closed. The link must serve the audio file itself: a page that only plays
 * music (a streaming site) downloads as HTML and is rejected by the check.
 */
@MainActor
final class StreamIngestManager: ObservableObject {
    static let shared = StreamIngestManager()
    static let sessionIdentifier = "com.harmony.app.ios.links"

    enum Stage: String, Codable {
        case queued = "Queued"
        case downloading = "Downloading"
        case verifying = "Checking the file"
        case converting = "Converting to FLAC"
        case tagging = "Tagging"
        case indexing = "Indexing 24D vector"
        case done = "In your library"
        case failed = "Failed"
        case cancelled = "Cancelled"

        var isActive: Bool { ![.done, .failed, .cancelled].contains(self) }
    }

    struct Job: Identifiable, Codable, Equatable {
        let id: UUID
        let source: URL
        var title: String
        var stage: Stage
        var bytes: Int64 = 0
        var totalBytes: Int64 = 0
        var bytesPerSecond: Double = 0
        var stageProgress: Double = 0
        var message: String?
        var trackId: Int64?
        var startedAt = Date()

        var fraction: Double {
            switch stage {
            case .downloading: return totalBytes > 0 ? Double(bytes) / Double(totalBytes) : 0
            case .converting: return stageProgress
            case .done: return 1
            default: return stage.isActive ? 1 : 0
            }
        }
    }

    @Published private(set) var jobs: [Job] = []
    @Published var losslessStorage: LosslessStorage {
        didSet { UserDefaults.standard.set(losslessStorage.rawValue, forKey: "ingest.lossless") }
    }

    /// Set by the app delegate when iOS wakes Harmony to finish background transfers.
    var backgroundCompletionHandler: (() -> Void)?

    private let delegate = LinkSessionDelegate()
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.allowsCellularAccess = true
        configuration.httpAdditionalHeaders = ["User-Agent": "Harmony/1.0 (iOS)"]
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }()
    private var speedSamples: [UUID: (time: Date, bytes: Int64)] = [:]
    private let journal = HarmonyPaths.applicationSupport.appendingPathComponent("link-jobs.json")

    private init() {
        losslessStorage = LosslessStorage(rawValue: UserDefaults.standard.string(forKey: "ingest.lossless") ?? "") ?? .flac
        if let data = try? Data(contentsOf: journal), let saved = try? JSONDecoder().decode([Job].self, from: data) {
            // Anything that was mid-pipeline when the app died is reported, not silently lost.
            jobs = saved.map { job in
                var job = job
                if job.stage.isActive && job.stage != .downloading && job.stage != .queued {
                    job.stage = .failed
                    job.message = "Harmony was closed while this was being processed. Try again."
                }
                return job
            }
        }
        delegate.owner = self
        _ = session  // reconnects to transfers that ran while the app was closed
    }

    // MARK: - Public

    /// Starts downloading [text] (a URL, or text containing one). Returns an error message for a bad link.
    @discardableResult
    func enqueue(_ text: String) -> String? {
        guard let url = Self.extractURL(from: text) else {
            return "Paste a link that starts with https:// and points to an audio file."
        }
        let job = Job(id: UUID(), source: url, title: Self.titleGuess(for: url), stage: .downloading)
        jobs.insert(job, at: 0)
        var request = URLRequest(url: url)
        request.setValue("audio/*, application/octet-stream;q=0.9, */*;q=0.5", forHTTPHeaderField: "Accept")
        let task = session.downloadTask(with: request)
        task.taskDescription = job.id.uuidString
        task.resume()
        persist()
        return nil
    }

    func cancel(_ id: UUID) {
        session.getAllTasks { tasks in
            tasks.first { $0.taskDescription == id.uuidString }?.cancel()
        }
        update(id) { $0.stage = .cancelled; $0.bytesPerSecond = 0 }
    }

    /// Forgets one finished job (a retry replaces it with a new one).
    func clear(_ id: UUID) {
        jobs.removeAll { $0.id == id && !$0.stage.isActive }
        persist()
    }

    func clearFinished() {
        jobs.removeAll { !$0.stage.isActive }
        persist()
    }

    // MARK: - Session events (from LinkSessionDelegate, on the main actor)

    fileprivate func didWrite(taskDescription: String?, written: Int64, expected: Int64) {
        guard let id = taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        let now = Date()
        var speed: Double?
        if let last = speedSamples[id] {
            let elapsed = now.timeIntervalSince(last.time)
            if elapsed >= 0.5 {
                speed = Double(written - last.bytes) / elapsed
                speedSamples[id] = (now, written)
            }
        } else {
            speedSamples[id] = (now, written)
        }
        update(id) { job in
            job.stage = .downloading
            job.bytes = written
            job.totalBytes = max(expected, 0)
            if let speed { job.bytesPerSecond = job.bytesPerSecond <= 0 ? speed : job.bytesPerSecond * 0.6 + speed * 0.4 }
        }
    }

    fileprivate func didFinish(taskDescription: String?, file: URL, suggestedName: String?, mimeType: String?) {
        guard let id = taskDescription.flatMap(UUID.init(uuidString:)) else {
            try? FileManager.default.removeItem(at: file)
            return
        }
        speedSamples[id] = nil
        if let suggestedName, !suggestedName.isEmpty {
            update(id) { $0.title = (suggestedName as NSString).deletingPathExtension }
        }
        Task { await process(id: id, file: file, mimeType: mimeType) }
    }

    fileprivate func didFail(taskDescription: String?, message: String, cancelled: Bool) {
        guard let id = taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        speedSamples[id] = nil
        update(id) { job in
            job.stage = cancelled ? .cancelled : .failed
            job.message = cancelled ? nil : message
            job.bytesPerSecond = 0
        }
    }

    fileprivate func finishedBackgroundEvents() {
        backgroundCompletionHandler?()
        backgroundCompletionHandler = nil
    }

    // MARK: - The pipeline

    private func process(id: UUID, file: URL, mimeType: String?) async {
        update(id) { $0.stage = .verifying; $0.bytesPerSecond = 0 }
        if let mimeType, mimeType.hasPrefix("text/") {
            try? FileManager.default.removeItem(at: file)
            fail(id, "The link opened a web page, not an audio file. Use the file's direct download link.")
            return
        }
        guard let job = jobs.first(where: { $0.id == id }) else { return }
        do {
            let container = HarmonyTranscoder.sniffContainer(atPath: file.path)
            if ["wav", "aiff", "caf"].contains(container) {
                update(id) { $0.stage = .converting; $0.stageProgress = 0 }
            }
            let prepared = try await IosNativeAudioTranscoder.shared.prepare(
                file: file, into: HarmonyPaths.linkDownloads, baseName: job.title, lossless: losslessStorage,
                progress: { [weak self] fraction in self?.update(id) { $0.stageProgress = fraction } })

            // Tags (from the file's name when it has none), the library, the 24-D vector.
            update(id) { $0.stage = .tagging }
            let trackId = try await DownloadPostProcessor.finish(
                file: prepared.url, source: "Link", known: nil, fallbackName: job.title,
                onIndexing: { [weak self] in self?.update(id) { $0.stage = .indexing } })
            update(id) { job in
                job.stage = .done
                job.trackId = trackId
                job.message = prepared.converted ? "Stored losslessly as \(prepared.container.uppercased())" : nil
            }
        } catch {
            try? FileManager.default.removeItem(at: file)
            fail(id, error.localizedDescription)
        }
        persist()
    }

    private func fail(_ id: UUID, _ message: String) {
        update(id) { $0.stage = .failed; $0.message = message; $0.bytesPerSecond = 0 }
        persist()
    }

    private func update(_ id: UUID, _ change: (inout Job) -> Void) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        change(&jobs[index])
    }

    private func persist() {
        let recent = Array(jobs.prefix(100))
        if let data = try? JSONEncoder().encode(recent) { try? data.write(to: journal, options: .atomic) }
    }

    static func extractURL(from text: String) -> URL? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http", url.host != nil {
            return url
        }
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(trimmed.startIndex..., in: trimmed)
        return detector?.matches(in: trimmed, options: [], range: range)
            .compactMap(\.url)
            .first { ["http", "https"].contains($0.scheme?.lowercased() ?? "") }
    }

    static func titleGuess(for url: URL) -> String {
        let last = url.deletingPathExtension().lastPathComponent.removingPercentEncoding ?? url.lastPathComponent
        return last.isEmpty || last == "/" ? (url.host ?? "Download") : last
    }
}

/// URLSession's delegate (called on its own queue), forwarding to the manager on the main actor.
final class LinkSessionDelegate: NSObject, URLSessionDownloadDelegate {
    weak var owner: StreamIngestManager?

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let description = downloadTask.taskDescription
        Task { @MainActor [weak owner] in
            owner?.didWrite(taskDescription: description, written: totalBytesWritten, expected: totalBytesExpectedToWrite)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The temporary file is deleted when this returns: move it first, synchronously.
        let response = downloadTask.response as? HTTPURLResponse
        if let status = response?.statusCode, !(200..<300).contains(status) {
            let description = downloadTask.taskDescription
            Task { @MainActor [weak owner] in
                owner?.didFail(taskDescription: description, message: "The server answered \(status).", cancelled: false)
            }
            return
        }
        let destination = HarmonyPaths.incoming.appendingPathComponent(UUID().uuidString)
        do {
            try FileManager.default.moveItem(at: location, to: destination)
        } catch {
            let description = downloadTask.taskDescription
            Task { @MainActor [weak owner] in
                owner?.didFail(taskDescription: description, message: "Could not keep the download: \(error.localizedDescription)", cancelled: false)
            }
            return
        }
        let description = downloadTask.taskDescription
        let name = response?.suggestedFilename
        let mime = response?.mimeType
        Task { @MainActor [weak owner] in
            owner?.didFinish(taskDescription: description, file: destination, suggestedName: name, mimeType: mime)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        let cancelled = (error as NSError).code == NSURLErrorCancelled
        let description = task.taskDescription
        let message = error.localizedDescription
        Task { @MainActor [weak owner] in
            owner?.didFail(taskDescription: description, message: message, cancelled: cancelled)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor [weak owner] in owner?.finishedBackgroundEvents() }
    }
}
