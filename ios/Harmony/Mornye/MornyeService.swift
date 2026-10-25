import Foundation

struct MornyeJob: Identifiable, Codable {
    enum Stage: String, Codable {
        case queued, preparing, downloading, indexing, completed, failed, paused, cancelled
        var active: Bool { [.queued, .preparing, .downloading, .indexing].contains(self) }
    }
    let id: UUID
    let track: MornyeTrack
    let quality: MornyeQuality
    let providerFilter: String
    var stage: Stage = .queued
    var progress: Double = 0
    var bytes: Int64 = 0
    var total: Int64 = 0
    var speed: Double = 0
    var detail: String?
    var indexed = false
    var actualFormatBadge: String?
    var finalRelativePath: String?
    var resumeOnForeground = false
    var verificationProvider: String?

    var item: DownloadItem {
        let status: DownloadItem.Status
        switch stage {
        case .queued: status = .queued
        case .preparing: status = .connecting
        case .downloading: status = .downloading
        case .indexing: status = .indexing24D
        case .completed: status = .completed
        case .failed: status = .failed
        case .paused: status = .paused
        case .cancelled: status = .cancelled
        }
        return DownloadItem(origin: .mornye(id), title: track.title, artist: track.artist, album: track.album,
                            source: "Mornye", formatBadge: actualFormatBadge ?? (quality == .hiRes ? "HI-RES REQUESTED" : "LOSSLESS"), progress: progress,
                            bytes: bytes, totalBytes: total, bytesPerSecond: stage == .downloading ? speed : 0,
                            status: status, detail: detail, indexed: indexed)
    }
}

/// Main-actor UI and persisted jobs; native work stays on background threads.
/// A slot is released only after the cancelled native call has returned.
@MainActor
final class MornyeService: ObservableObject {
    static let shared = MornyeService()
    @Published private(set) var jobs: [MornyeJob] = []
    @Published private(set) var results: [MornyeTrack] = []
    @Published private(set) var providers: [MornyeProvider] = []
    @Published private(set) var isPreparing = false
    @Published private(set) var isSearching = false
    @Published var error: String?
    @Published private(set) var verification: MornyeVerification?

    private let runtime = MornyeRuntime.shared
    private var started = false
    private var suspended = false
    private var activeID: UUID?
    private var activeItemID: String?
    private var worker: Task<Void, Never>?
    private var resumeRequested = Set<UUID>()
    private var callbacksInFlight = Set<String>()
    private var completedCallbacks: [String] = []
    private var lastProgress: (Int64, Date)?
    private var lastSaved = Date.distantPast

    var activeCount: Int { jobs.filter { $0.stage.active }.count }
    var socketWork: Int { jobs.filter { [.queued, .preparing, .downloading].contains($0.stage) }.count }

    private var persistence: URL {
        HarmonyPaths.ensure(HarmonyPaths.applicationSupport.appendingPathComponent("Mornye", isDirectory: true))
            .appendingPathComponent("downloads.json")
    }

    private init() {}

    func start() {
        guard !started else { return }
        started = true
        if let data = try? Data(contentsOf: persistence) {
            do {
                jobs = try JSONDecoder().decode([MornyeJob].self, from: data)
                for i in jobs.indices where jobs[i].stage.active || jobs[i].resumeOnForeground {
                    jobs[i].stage = .queued
                    jobs[i].speed = 0
                    jobs[i].resumeOnForeground = false
                }
            } catch { self.error = "Saved Mornye downloads could not be restored." }
        }
        pump()
    }

    func activate() {
        start()
        guard providers.isEmpty, !isPreparing else { return }
        isPreparing = true
        Task {
            defer { isPreparing = false }
            let runtime = self.runtime
            do { providers = try await Task.detached(priority: .userInitiated) { try runtime.prepare() }.value }
            catch { self.error = MornyeJSON.redact(error.localizedDescription) }
        }
    }

    func search(_ text: String, provider: String = "") {
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard query.count >= 2, !isSearching else { return }
        start()
        isSearching = true
        error = nil
        Task {
            defer { isSearching = false }
            do {
                let runtime = self.runtime
                providers = try await Task.detached { try runtime.prepare() }.value
                results = try await Task.detached { try runtime.search(query, provider: provider) }.value
                if results.isEmpty { await findSearchVerification(provider: provider) }
            } catch {
                self.error = MornyeJSON.redact(error.localizedDescription)
                await findSearchVerification(provider: provider)
            }
        }
    }

    /// Search itself can request a session before it returns any tracks.
    /// Surface that challenge instead of leaving a first-time user stuck.
    private func findSearchVerification(provider: String) async {
        let runtime = self.runtime
        for id in provider.isEmpty ? MornyeProvider.order : [provider] {
            if let pending = try? await Task.detached(operation: {
                try runtime.pendingVerification(provider: id, jobID: nil)
            }).value {
                verification = pending
                return
            }
        }
    }

    func enqueue(_ track: MornyeTrack, quality: MornyeQuality, provider: String = "") {
        start()
        guard !jobs.contains(where: { $0.track.id == track.id && ($0.stage.active || $0.stage == .paused) }) else {
            error = "This song is already in the Mornye queue."
            return
        }
        jobs.insert(MornyeJob(id: UUID(), track: track, quality: quality, providerFilter: provider), at: 0)
        save()
        pump()
    }

    func pause(_ id: UUID, background: Bool = false) {
        guard let index = jobs.firstIndex(where: { $0.id == id }),
              [.queued, .preparing, .downloading].contains(jobs[index].stage) else { return }
        jobs[index].stage = .paused
        jobs[index].speed = 0
        jobs[index].resumeOnForeground = background
        jobs[index].detail = background ? "Continues when Harmony returns" : "Paused"
        save()
        if activeID == id {
            worker?.cancel()
            if let itemID = activeItemID {
                let runtime = self.runtime
                Task.detached { runtime.cancel(itemID) }
            }
        }
    }

    func resume(_ id: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == id }), jobs[i].stage == .paused else { return }
        if let provider = jobs[i].verificationProvider {
            requestVerification(provider: provider, jobID: id)
            return
        }
        if activeID == id { resumeRequested.insert(id); return }
        jobs[i].stage = .queued
        jobs[i].resumeOnForeground = false
        jobs[i].detail = nil
        save()
        pump()
    }

    func retry(_ id: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == id }), jobs[i].stage == .failed else { return }
        jobs[i].stage = .queued
        jobs[i].verificationProvider = nil
        jobs[i].detail = nil
        save()
        pump()
    }

    func cancel(_ id: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == id }),
              jobs[i].stage != .indexing, jobs[i].stage != .completed else { return }
        jobs[i].stage = .cancelled
        jobs[i].resumeOnForeground = false
        jobs[i].verificationProvider = nil
        jobs[i].speed = 0
        jobs[i].detail = "Cancelled"
        resumeRequested.remove(id)
        if verification?.jobID == id { verification = nil }
        save()
        if activeID == id {
            worker?.cancel()
            if let itemID = activeItemID {
                let runtime = self.runtime
                Task.detached { runtime.cancel(itemID) }
            }
        } else { discardStaging(id) }
    }

    func clearFinished() {
        for job in jobs where [.completed, .cancelled, .failed].contains(job.stage) && job.id != activeID { discardStaging(job.id) }
        jobs.removeAll { [.completed, .cancelled, .failed].contains($0.stage) && $0.id != activeID }
        save()
    }

    func suspendForBackground() {
        suspended = true
        for job in jobs where [.queued, .preparing, .downloading].contains(job.stage) { pause(job.id, background: true) }
    }

    func resumeSuspended() {
        start()
        suspended = false
        for job in jobs where job.stage == .paused && job.resumeOnForeground { resume(job.id) }
        pump()
    }

    func networkRestored() {
        // The current attempt can finish or fail normally. A failed job stays
        // explicit and retryable; never start a duplicate download on a new route.
        if !suspended { pump() }
    }

    func verify() {
        guard let verification else { return }
        MornyeWebAuthentication.shared.open(verification.url) { url, message in
            if let url { self.handleCallback(url) }
            else if let message { self.error = message }
        }
    }

    private func requestVerification(provider: String, jobID: UUID?) {
        Task {
            let runtime = self.runtime
            do {
                _ = try await Task.detached { try runtime.prepare() }.value
                if let pending = try await Task.detached(operation: { try runtime.pendingVerification(provider: provider, jobID: jobID) }).value {
                    verification = pending
                    verify()
                } else {
                    error = "This verification expired. Retry the download to request a new one."
                    if let jobID, let i = jobs.firstIndex(where: { $0.id == jobID }) {
                        jobs[i].stage = .failed
                        jobs[i].verificationProvider = nil
                        save()
                    }
                }
            } catch { self.error = MornyeJSON.redact(error.localizedDescription) }
        }
    }

    @discardableResult
    func handleCallback(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "mornye" else { return false }
        guard let callback = MornyeAuthCallback.parse(url) else {
            error = "Invalid Mornye verification callback."
            return true
        }
        guard !callbacksInFlight.contains(callback.state), !completedCallbacks.contains(callback.state) else { return true }
        callbacksInFlight.insert(callback.state)
        Task {
            defer { callbacksInFlight.remove(callback.state) }
            do {
                let runtime = self.runtime
                _ = try await Task.detached { try runtime.prepare() }.value
                let provider = try await Task.detached { try runtime.completeCallback(callback) }.value
                completedCallbacks.append(callback.state)
                if completedCallbacks.count > 32 { completedCallbacks.removeFirst() }
                if verification?.providerID == provider { verification = nil }
                for i in jobs.indices where jobs[i].verificationProvider == provider && jobs[i].stage == .paused {
                    jobs[i].verificationProvider = nil
                    jobs[i].stage = .queued
                    jobs[i].detail = nil
                }
                error = nil
                save()
                pump()
            } catch { self.error = MornyeJSON.redact(error.localizedDescription) }
        }
        return true
    }

    private func pump() {
        guard started, !suspended, activeID == nil, let job = jobs.last(where: { $0.stage == .queued }) else { return }
        activeID = job.id
        lastProgress = nil
        let itemID = "harmony-ios-\(job.id.uuidString)-\(UUID().uuidString)"
        activeItemID = itemID
        worker = Task { await run(job.id, itemID: itemID) }
    }

    private func run(_ id: UUID, itemID: String) async {
        guard let initial = jobs.first(where: { $0.id == id }) else { finishWorker(id); return }
        var poller: Task<Void, Never>?
        defer {
            poller?.cancel()
            finishWorker(id)
        }
        do {
            // If the app ended during indexing, finish the same file instead
            // of downloading a duplicate into a new library row.
            if let relative = initial.finalRelativePath {
                let file = HarmonyPaths.documents.appendingPathComponent(relative)
                if HarmonyTranscoder.inspectFile(at: file)?.lossless == true {
                    try await finishImport(id, file: file)
                    return
                }
            }
            update(id) { $0.stage = .preparing; $0.detail = "Preparing Mornye providers" }
            let runtime = self.runtime
            providers = try await Task.detached(priority: .userInitiated) { try runtime.prepare() }.value
            try Task.checkCancellation()
            try await Task.detached { try runtime.resetCancellation(itemID) }.value
            try Task.checkCancellation()
            poller = Task { await poll(id, itemID: itemID) }
            let first = initial.providerFilter.isEmpty ? initial.track.providerID : initial.providerFilter
            let route = initial.providerFilter.isEmpty
                ? providers.sorted { ($0.id == first ? -1 : MornyeProvider.order.firstIndex(of: $0.id) ?? 99) <
                                     ($1.id == first ? -1 : MornyeProvider.order.firstIndex(of: $1.id) ?? 99) }
                : providers.filter { $0.id == first }
            var output: URL?
            var deferred: MornyeVerification?
            var failure = "No matching lossless provider is available."
            for provider in route {
                try Task.checkCancellation()
                lastProgress = nil
                update(id) { $0.stage = .downloading; $0.bytes = 0; $0.total = 0; $0.progress = 0; $0.detail = "Trying \(provider.name) · \(initial.quality.title)" }
                let directory = runtime.staging.appendingPathComponent(id.uuidString, isDirectory: true)
                    .appendingPathComponent(provider.id, isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                do {
                    output = try await Task.detached(priority: .userInitiated) {
                        try runtime.download(track: initial.track, provider: provider, quality: initial.quality,
                                             itemID: itemID, directory: directory)
                    }.value
                    break
                } catch MornyeError.verification(let providerID, let url) {
                    if deferred == nil, let url { deferred = MornyeVerification(providerID: providerID, url: url, jobID: id) }
                    failure = "\(provider.name) needs verification."
                } catch {
                    try Task.checkCancellation()
                    failure = MornyeJSON.redact(error.localizedDescription)
                }
            }
            poller?.cancel()
            try Task.checkCancellation()
            if let output {
                try await finishImport(id, file: output)
            } else if let deferred {
                verification = deferred
                update(id) { $0.stage = .paused; $0.verificationProvider = deferred.providerID; $0.detail = "Verify \(MornyeProvider.names[deferred.providerID] ?? deferred.providerID), then resume" }
            } else { throw MornyeError.message(failure) }
        } catch is CancellationError {
            // pause/cancel already chose the final UI state. Native has now
            // returned, so a later Resume can safely reset its cancellation.
        } catch {
            if !Task.isCancelled { update(id) { $0.stage = .failed; $0.detail = MornyeJSON.redact(error.localizedDescription); $0.speed = 0 } }
        }
    }

    private func finishImport(_ id: UUID, file: URL) async throws {
        guard let job = jobs.first(where: { $0.id == id }) else { return }
        try Task.checkCancellation()
        update(id) { $0.stage = .indexing; $0.detail = "Saving tags and indexing 24D"; $0.progress = 1; $0.speed = 0 }
        let final: URL
        if HarmonyPaths.relativePath(for: file) != nil { final = file }
        else {
            let directory = HarmonyPaths.ensure(HarmonyPaths.downloads.appendingPathComponent("Mornye", isDirectory: true))
            final = try await IosNativeAudioTranscoder.shared.prepare(
                file: file, into: directory, baseName: "\(job.track.artist) - \(job.track.title)", lossless: .flac).url
        }
        let info = HarmonyTranscoder.inspectFile(at: final)
        let actualFormat: String
        if let info, info.bitDepth > 0 {
            actualFormat = "\(info.codec.uppercased()) \(info.bitDepth)/\(String(format: "%g", info.sampleRate / 1000))"
        } else { actualFormat = "LOSSLESS" }
        update(id) {
            $0.finalRelativePath = HarmonyPaths.relativePath(for: final)
            $0.actualFormatBadge = actualFormat
        }
        let known = KnownTrackInfo(title: job.track.title, artist: job.track.artist, album: job.track.album,
                                   isrc: job.track.isrc.isEmpty ? nil : job.track.isrc,
                                   durationMs: job.track.durationMs, coverArtURL: job.track.coverURL)
        _ = try await DownloadPostProcessor.finish(file: final, source: "Mornye", known: known,
                                                   fallbackName: final.deletingPathExtension().lastPathComponent)
        update(id) { $0.stage = .completed; $0.indexed = true; $0.detail = "In your library · indexed for Smart Shuffle" }
        discardStaging(id)
    }

    private func poll(_ id: UUID, itemID: String) async {
        let runtime = self.runtime
        while !Task.isCancelled {
            let items = try? await Task.detached(operation: { try runtime.progress() }).value
            guard !Task.isCancelled, activeItemID == itemID else { return }
            if let node = items?[itemID] as? [String: Any],
               let i = jobs.firstIndex(where: { $0.id == id }),
               [.preparing, .downloading].contains(jobs[i].stage) {
                let bytes = Int64(MornyeJSON.integer(node["bytes_received"]))
                let total = Int64(MornyeJSON.integer(node["bytes_total"]))
                let now = Date()
                let speed = lastProgress.map { max(0, Double(bytes - $0.0) / max(0.1, now.timeIntervalSince($0.1))) } ?? 0
                lastProgress = (bytes, now)
                jobs[i].bytes = max(jobs[i].bytes, bytes)
                jobs[i].total = max(0, total)
                jobs[i].speed = speed
                let fraction = total > 0 ? Double(bytes) / Double(total) : (node["progress"] as? Double ?? 0)
                jobs[i].progress = fraction.isFinite ? min(1, max(0, fraction)) : 0
                if now.timeIntervalSince(lastSaved) >= 5 { save() }
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
    }

    private func finishWorker(_ id: UUID) {
        guard activeID == id else { return }
        if let i = jobs.firstIndex(where: { $0.id == id }) {
            if resumeRequested.remove(id) != nil {
                jobs[i].stage = .queued
                jobs[i].resumeOnForeground = false
            }
            if jobs[i].stage == .cancelled { discardStaging(id) }
        }
        if let itemID = activeItemID {
            let runtime = self.runtime
            Task.detached { runtime.clearProgress(itemID) }
        }
        activeID = nil
        activeItemID = nil
        worker = nil
        save()
        pump()
    }

    private func update(_ id: UUID, _ change: (inout MornyeJob) -> Void) {
        guard let i = jobs.firstIndex(where: { $0.id == id }) else { return }
        change(&jobs[i])
        save()
    }

    private func save() {
        do {
            try JSONEncoder().encode(jobs).write(to: persistence, options: .atomic)
            lastSaved = Date()
        } catch { self.error = "Mornye download state could not be saved." }
    }

    private func discardStaging(_ id: UUID) {
        let root = runtime.staging.standardizedFileURL.resolvingSymlinksInPath()
        let directory = root.appendingPathComponent(id.uuidString).standardizedFileURL.resolvingSymlinksInPath()
        guard directory.path.hasPrefix(root.path + "/") else { return }
        try? FileManager.default.removeItem(at: directory)
    }
}
