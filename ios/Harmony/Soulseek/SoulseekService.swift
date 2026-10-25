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
    let id: UInt32
    var title: String
    var username: String
    var stage: HarmonySlskStage
    var bytes: UInt64 = 0
    var total: UInt64 = 0
    var bytesPerSecond: Double = 0
    var queuePlace: Int = 0
    var message: String = ""
    var path: String?
    /// Being measured for Smart Shuffle / measured and in the library.
    var indexing = false
    var indexed = false

    var fraction: Double { total > 0 ? min(1, Double(bytes) / Double(total)) : 0 }
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
    private var uiSearchId: UInt32 = 0
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

    /// Starts a download the UI follows in [transfers].
    @discardableResult
    func startDownload(_ candidate: HarmonySlskCandidate, title: String? = nil, patient: Bool = true,
                       fileName: String? = nil) -> UInt32 {
        let id = bridge.download(candidate, patient: patient, fileName: fileName)
        let transfer = SoulseekTransfer(id: id, title: title ?? (candidate.leafName as NSString).deletingPathExtension,
                                        username: candidate.username, stage: .connectingPeer, total: candidate.size)
        transfers.insert(transfer, at: 0)
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

    func cancel(_ transferId: UInt32) {
        bridge.cancelTransfer(transferId)
    }

    func clearFinished() {
        transfers.removeAll { $0.stage.isFinished }
    }

    /// Adds a finished download to the library and measures its vector.
    func addToLibrary(transferId: UInt32, path: String, known: KnownTrackInfo? = nil) {
        setIndexing(transferId, indexing: true, indexed: false)
        Task {
            let indexed = (try? await LibraryIngest.shared.ingest(fileAt: URL(fileURLWithPath: path), source: "Soulseek", known: known)) != nil
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

    fileprivate func transferUpdated(_ id: UInt32, stage: HarmonySlskStage, bytes: UInt64, total: UInt64,
                                     speed: Double, queuePlace: Int, message: String, path: String?, queued: Bool) {
        if let index = transfers.firstIndex(where: { $0.id == id }) {
            transfers[index].stage = stage
            transfers[index].bytes = bytes
            if total > 0 { transfers[index].total = total }
            transfers[index].bytesPerSecond = speed
            transfers[index].queuePlace = queuePlace
            if !message.isEmpty || stage.isFinished { transfers[index].message = message }
            if let path { transfers[index].path = path }
        }
        guard stage.isFinished else { return }
        guard let waiter = transferWaiters.removeValue(forKey: id) else {
            // Started from the search screen: it goes straight into the library.
            if stage == .done, let path { addToLibrary(transferId: id, path: path) }
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
