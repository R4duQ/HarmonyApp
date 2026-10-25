import Foundation
import Security

/// The Soulseek password, in the Keychain (this device only).
enum HarmonyKeychain {
    static func set(_ value: String?, for account: String, service: String = "com.harmony.app.ios") {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        guard let value, let data = value.data(using: .utf8) else { return }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ account: String, service: String = "com.harmony.app.ios") -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: account,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

extension HarmonySlskFormat: CaseIterable {
    public static var allCases: [HarmonySlskFormat] { [.flacOnly, .preferFlac, .preferMp3, .mp3Only] }

    var label: String {
        switch self {
        case .flacOnly: return "FLAC only"
        case .preferFlac: return "Prefer FLAC"
        case .preferMp3: return "Prefer MP3"
        case .mp3Only: return "MP3 only"
        @unknown default: return "FLAC only"
        }
    }
}

extension HarmonySlskStage {
    var label: String {
        switch self {
        case .connectingPeer: return "Connecting to peer"
        case .requested: return "Request sent"
        case .queued: return "Queued"
        case .accepted: return "Opening transfer"
        case .transferring: return "Downloading"
        case .verifying: return "Checking the file"
        case .done: return "Downloaded"
        case .failed: return "Failed"
        case .cancelled: return "Cancelled"
        @unknown default: return ""
        }
    }

    var isFinished: Bool { self == .done || self == .failed || self == .cancelled }
}

/// A Soulseek transfer as the UI shows it.
struct SoulseekTransfer: Identifiable, Equatable {
    /// Harmony's own id: it stays the same through pauses, resumes and retries.
    let id: UInt32
    /// The engine's id for the attempt running now (nil while waiting or paused).
    var engineId: UInt32?
    let candidate: HarmonySlskCandidate
    let patient: Bool
    let fileName: String?
    /// What the download knows about the song (a playlist import), for its tags.
    var known: KnownTrackInfo?
    var title: String
    var username: String
    var stage: HarmonySlskStage
    var bytes: UInt64 = 0
    var total: UInt64 = 0
    var bytesPerSecond: Double = 0
    var queuePlace: Int = 0
    var message: String = ""
    var path: String?
    /// Waiting for one of the [SoulseekService.maxConcurrent] transfer slots.
    var waitingForSlot = false
    var paused = false
    /// Paused by the background guard when iOS took the time back; resumed on its own.
    var suspended = false
    var failedAt: Date?
    var automaticRetries = 0
    /// Being measured for Smart Shuffle / measured and in the library.
    var indexing = false
    var indexed = false

    var fraction: Double { total > 0 ? min(1, Double(bytes) / Double(total)) : 0 }

    /// Holds or waits for a slot, or is paused by the guard (it will come back).
    var isActive: Bool {
        waitingForSlot || suspended || (engineId != nil && !paused && !stage.isFinished)
    }

    /// Uses one of the slots: connecting, asking, transferring, verifying (a peer's queue doesn't count).
    var holdsSlot: Bool {
        engineId != nil && !paused && !stage.isFinished && stage != .queued
    }

    /// "HI-RES", "FLAC", "MP3 320", "M4A".
    var formatBadge: String {
        let ext = candidate.fileExtension.uppercased()
        if candidate.bitDepth >= 24 || candidate.sampleRate > 48_000 { return "HI-RES" }
        if ext == "MP3", candidate.bitrateKbps > 0 { return "MP3 \(candidate.bitrateKbps)" }
        return ext.isEmpty ? "AUDIO" : ext
    }
}

/// How a download ended.
enum SoulseekOutcome {
    case downloaded(URL)
    case failed(String, queued: Bool)
    case cancelled
}

/**
 * Harmony's Soulseek session for SwiftUI: connection, search and downloads,
 * over the C++ engine (SlskEngine via HarmonySoulseekBridge).
 *
 * Soulseek allows one session per account: signing in here signs out any
 * other client using the same name. Transfers continue while Harmony plays
 * music in the background; with nothing playing, iOS suspends the app after
 * a short while and an unfinished transfer resumes from where it stopped.
 */
@MainActor
final class SoulseekService: ObservableObject {
    static let shared = SoulseekService()

    @Published private(set) var status: HarmonySlskStatus = .disconnected
    @Published private(set) var statusMessage = "Not connected"
    @Published var username: String {
        didSet { UserDefaults.standard.set(username, forKey: "slsk.username") }
    }
    @Published var format: HarmonySlskFormat {
        didSet { UserDefaults.standard.set(format.rawValue, forKey: "slsk.format") }
    }
    @Published private(set) var results: [HarmonySlskCandidate] = []
    @Published private(set) var searching = false
    @Published private(set) var lastSearchStats = ""
    @Published private(set) var transfers: [SoulseekTransfer] = []

    var isConnected: Bool { status == .connected }
    var hasSavedPassword: Bool { HarmonyKeychain.get("slsk:\(username)") != nil }

    private let bridge: HarmonySoulseekBridge
    private let proxy = SoulseekDelegateProxy()
    /// Transfers running at once; the rest wait their turn.
    static let maxConcurrent = 3

    private var uiSearchId: UInt32 = 0
    private var nextTransferId: UInt32 = 1
    /// After the network came back, a transfer that fails is retried once on its own until this moment.
    private var autoRetryUntil: Date?
    private var searchWaiters: [UInt32: CheckedContinuation<[HarmonySlskCandidate], Never>] = [:]
    private var transferWaiters: [UInt32: CheckedContinuation<SoulseekOutcome, Never>] = [:]
    private var connectWaiters: [CheckedContinuation<Bool, Never>] = []

    private init() {
        username = UserDefaults.standard.string(forKey: "slsk.username") ?? ""
        format = HarmonySlskFormat(rawValue: UserDefaults.standard.integer(forKey: "slsk.format")) ?? .flacOnly
        bridge = HarmonySoulseekBridge(partialDirectory: HarmonyPaths.soulseekPartials.path,
                                       downloadDirectory: HarmonyPaths.soulseekDownloads.path)
        proxy.owner = self
        bridge.delegate = proxy
    }

    // MARK: - Session

    func connect(password: String, remember: Bool) {
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !password.isEmpty else {
            statusMessage = "Enter your Soulseek username and password."
            return
        }
        username = name
        HarmonyKeychain.set(remember ? password : nil, for: "slsk:\(name)")
        bridge.connect(withUsername: name, password: password)
    }

    /// Signs in with the saved password, if there is one; returns whether the session is up.
    func ensureConnected() async -> Bool {
        if isConnected { return true }
        guard status != .connecting else { return await waitForConnection() }
        guard let password = HarmonyKeychain.get("slsk:\(username)") else { return false }
        bridge.connect(withUsername: username, password: password)
        return await waitForConnection()
    }

    func disconnect() {
        bridge.disconnect()
    }

    private func waitForConnection() async -> Bool {
        await withCheckedContinuation { continuation in
            if isConnected {
                continuation.resume(returning: true)
            } else {
                connectWaiters.append(continuation)
            }
        }
    }

    // MARK: - Search

    /// A search for the search screen: results stream into [results].
    func searchForUI(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return }
        if uiSearchId != 0 { bridge.cancelSearch(uiSearchId) }
        results = []
        searching = true
        lastSearchStats = ""
        uiSearchId = bridge.search(trimmed, mode: .balanced, format: format, onlyFreeSlots: false, targetDurationSeconds: 0)
    }

    /// A search that waits for the final ranking (for the lossless resolver).
    func search(_ query: String, mode: HarmonySlskSearchMode, format: HarmonySlskFormat,
                targetDurationSeconds: Int) async -> [HarmonySlskCandidate] {
        await withCheckedContinuation { continuation in
            let id = bridge.search(query, mode: mode, format: format, onlyFreeSlots: false,
                                   targetDurationSeconds: targetDurationSeconds)
            searchWaiters[id] = continuation
        }
    }

    // MARK: - Downloads

    /// Transfers that are running, waiting for a slot, or will come back after a suspension.
    var activeTransferCount: Int { transfers.filter(\.isActive).count }

    /**
     * Queues a download the UI follows in [transfers]; it starts as soon as
     * one of the [maxConcurrent] slots is free. Returns Harmony's id for it.
     */
    @discardableResult
    func startDownload(_ candidate: HarmonySlskCandidate, title: String? = nil, patient: Bool = true,
                       fileName: String? = nil, known: KnownTrackInfo? = nil) -> UInt32 {
        let id = nextTransferId
        nextTransferId += 1
        let transfer = SoulseekTransfer(id: id, candidate: candidate, patient: patient, fileName: fileName, known: known,
                                        title: title ?? (candidate.leafName as NSString).deletingPathExtension,
                                        username: candidate.username, stage: .connectingPeer, total: candidate.size,
                                        waitingForSlot: true)
        transfers.insert(transfer, at: 0)
        pump()
        return id
    }

    /// Downloads and waits for the outcome.
    func download(_ candidate: HarmonySlskCandidate, title: String, patient: Bool, fileName: String?,
                  onStart: ((UInt32) -> Void)? = nil) async -> SoulseekOutcome {
        await withCheckedContinuation { continuation in
            let id = startDownload(candidate, title: title, patient: patient, fileName: fileName)
            transferWaiters[id] = continuation
            onStart?(id)
        }
    }

    /// Starts waiting transfers while slots are free (oldest first).
    private func pump() {
        guard isConnected else { return }
        var free = Self.maxConcurrent - transfers.filter(\.holdsSlot).count
        for index in transfers.indices.reversed() where free > 0 && transfers[index].waitingForSlot {
            let t = transfers[index]
            transfers[index].engineId = bridge.download(t.candidate, patient: t.patient, fileName: t.fileName)
            transfers[index].waitingForSlot = false
            transfers[index].stage = .connectingPeer
            transfers[index].message = ""
            free -= 1
        }
    }

    /// Stops a transfer and keeps its bytes; Resume continues from there.
    func pause(_ transferId: UInt32) {
        guard let index = transfers.firstIndex(where: { $0.id == transferId }) else { return }
        let t = transfers[index]
        if t.waitingForSlot {
            transfers[index].waitingForSlot = false
            transfers[index].paused = true
            transfers[index].bytesPerSecond = 0
        } else if let engineId = t.engineId, !t.stage.isFinished, !t.paused {
            transfers[index].paused = true
            transfers[index].bytesPerSecond = 0
            bridge.pauseTransfer(engineId)
        }
    }

    /// A paused transfer goes back in line for a slot.
    func resume(_ transferId: UInt32) {
        guard let index = transfers.firstIndex(where: { $0.id == transferId }), transfers[index].paused else { return }
        transfers[index].paused = false
        transfers[index].suspended = false
        requeue(index)
    }

    /// A failed transfer tries the same source again, from the bytes it already has.
    func retry(_ transferId: UInt32) {
        guard let index = transfers.firstIndex(where: { $0.id == transferId }),
              transfers[index].stage == .failed || (transfers[index].stage == .cancelled && !transfers[index].paused) else { return }
        transfers[index].failedAt = nil
        requeue(index)
    }

    private func requeue(_ index: Int) {
        transfers[index].engineId = nil
        transfers[index].waitingForSlot = true
        transfers[index].stage = .connectingPeer
        transfers[index].message = ""
        transfers[index].bytesPerSecond = 0
        pump()
        if !isConnected { Task { _ = await ensureConnected() } }
    }

    func cancel(_ transferId: UInt32) {
        guard let index = transfers.firstIndex(where: { $0.id == transferId }) else { return }
        let t = transfers[index]
        if t.waitingForSlot || t.paused {
            if t.paused { bridge.discardPartial(for: t.candidate) }
            transfers[index].waitingForSlot = false
            transfers[index].paused = false
            transfers[index].suspended = false
            transfers[index].stage = .cancelled
            transfers[index].bytesPerSecond = 0
            transferWaiters.removeValue(forKey: transferId)?.resume(returning: .cancelled)
        } else if let engineId = t.engineId {
            bridge.cancelTransfer(engineId)
        }
    }

    func clearFinished() {
        transfers.removeAll { $0.stage.isFinished && !$0.paused && !$0.indexing }
    }

    /// iOS is about to suspend Harmony: every running transfer stops cleanly, keeping its bytes.
    func suspendForBackground() {
        for index in transfers.indices where transfers[index].holdsSlot || transfers[index].stage == .queued {
            transfers[index].suspended = true
            if let engineId = transfers[index].engineId, !transfers[index].paused {
                transfers[index].paused = true
                bridge.pauseTransfer(engineId)
            }
        }
    }

    /// Back from a suspension: what the guard paused carries on.
    func resumeSuspended() {
        for index in transfers.indices where transfers[index].suspended {
            transfers[index].suspended = false
            transfers[index].paused = false
            requeue(index)
        }
    }

    /**
     * The network came back (Wi-Fi to cellular, a tunnel): sign in again and
     * retry, once, what failed while it was gone.
     */
    func networkRestored(lostAt: Date) {
        // Sockets on the old network may only fail later: keep retrying those for a minute.
        autoRetryUntil = Date().addingTimeInterval(60)
        Task {
            guard await ensureConnected() else { return }
            for index in transfers.indices {
                guard transfers[index].stage == .failed, let failedAt = transfers[index].failedAt,
                      failedAt >= lostAt.addingTimeInterval(-10), transfers[index].automaticRetries < 1 else { continue }
                transfers[index].automaticRetries += 1
                transfers[index].failedAt = nil
                requeue(index)
            }
            pump()
        }
    }

    /// Adds a finished download to the library: tags, the song, its 24-D vector.
    func addToLibrary(transferId: UInt32, path: String, known: KnownTrackInfo? = nil) {
        setIndexing(transferId, indexing: true, indexed: false)
        let fallback = transfers.first(where: { $0.id == transferId }).map { "\($0.title)" }
        Task {
            let file = URL(fileURLWithPath: path)
            let indexed = (try? await DownloadPostProcessor.finish(file: file, source: "Soulseek", known: known,
                                                                   fallbackName: fallback)) != nil
            setIndexing(transferId, indexing: false, indexed: indexed)
        }
    }

    func setIndexing(_ transferId: UInt32, indexing: Bool, indexed: Bool) {
        guard let index = transfers.firstIndex(where: { $0.id == transferId }) else { return }
        transfers[index].indexing = indexing
        transfers[index].indexed = indexed
    }

    // MARK: - Bridge events (main actor)

    fileprivate func statusChanged(_ status: HarmonySlskStatus, message: String) {
        self.status = status
        statusMessage = message
        if status == .connected || status == .error || status == .disconnected {
            let waiters = connectWaiters
            connectWaiters.removeAll()
            for waiter in waiters { waiter.resume(returning: status == .connected) }
        }
        if status == .connected { pump() }
    }

    fileprivate func searchUpdated(_ id: UInt32, results: [HarmonySlskCandidate], done: Bool, peers: Int, files: Int) {
        if id == uiSearchId {
            self.results = results
            lastSearchStats = "\(peers) peers · \(files) files"
            if done {
                searching = false
                uiSearchId = 0
            }
        }
        if done, let waiter = searchWaiters.removeValue(forKey: id) {
            waiter.resume(returning: results)
        }
    }

    fileprivate func transferUpdated(_ engineId: UInt32, stage: HarmonySlskStage, bytes: UInt64, total: UInt64,
                                     speed: Double, queuePlace: Int, message: String, path: String?, queued: Bool) {
        guard let index = transfers.firstIndex(where: { $0.engineId == engineId }) else { return }
        let id = transfers[index].id
        if transfers[index].paused {
            // The engine confirms a pause: the bytes stay for Resume, nobody is told it ended.
            if stage.isFinished {
                transfers[index].stage = .cancelled
                transfers[index].message = transfers[index].suspended ? "Paused while Harmony was in the background" : "Paused"
                transfers[index].bytesPerSecond = 0
                pump()
            }
            return
        }
        transfers[index].stage = stage
        if bytes > 0 || stage != .connectingPeer { transfers[index].bytes = bytes }
        if total > 0 { transfers[index].total = total }
        transfers[index].bytesPerSecond = speed
        transfers[index].queuePlace = queuePlace
        if !message.isEmpty || stage.isFinished { transfers[index].message = message }
        if let path { transfers[index].path = path }
        if stage == .queued { pump() }
        guard stage.isFinished else { return }
        if stage == .failed {
            transfers[index].failedAt = Date()
            if let until = autoRetryUntil, Date() < until, transfers[index].automaticRetries < 1 {
                transfers[index].automaticRetries += 1
                requeue(index)
                return
            }
        }
        pump()
        guard let waiter = transferWaiters.removeValue(forKey: id) else {
            // Started from the search screen (or retried): it goes straight into the library.
            if stage == .done, let path { addToLibrary(transferId: id, path: path, known: transfers[index].known) }
            return
        }
        switch stage {
        case .done:
            if let path { waiter.resume(returning: .downloaded(URL(fileURLWithPath: path))) }
            else { waiter.resume(returning: .failed("The download finished without a file", queued: false)) }
        case .cancelled: waiter.resume(returning: .cancelled)
        default: waiter.resume(returning: .failed(message.isEmpty ? "The download failed" : message, queued: queued))
        }
    }
}

/// Receives the bridge's (main-thread) callbacks and hands them to the service.
final class SoulseekDelegateProxy: NSObject, HarmonySoulseekDelegate {
    weak var owner: SoulseekService?

    func soulseekStatusChanged(_ status: HarmonySlskStatus, message: String) {
        Task { @MainActor [weak owner] in owner?.statusChanged(status, message: message) }
    }

    func soulseekSearch(_ searchId: UInt32, results: [HarmonySlskCandidate], done: Bool, peersResponded peers: Int,
                        filesSeen files: Int) {
        Task { @MainActor [weak owner] in
            owner?.searchUpdated(searchId, results: results, done: done, peers: peers, files: files)
        }
    }

    func soulseekTransfer(_ transferId: UInt32, stage: HarmonySlskStage, bytes: UInt64, total: UInt64,
                          bytesPerSecond: Double, queuePlace: Int, message: String, path: String?,
                          queuedElsewhere: Bool) {
        Task { @MainActor [weak owner] in
            owner?.transferUpdated(transferId, stage: stage, bytes: bytes, total: total, speed: bytesPerSecond,
                                   queuePlace: queuePlace, message: message, path: path, queued: queuedElsewhere)
        }
    }
}
