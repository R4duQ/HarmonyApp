import Foundation
import Security
import MornyeBackend

/// UniFFI objects are internally synchronized by Mornye. Initialization is
/// serialized here; cancellation/progress must not wait on a download queue.
final class MornyeRuntime: @unchecked Sendable {
    static let shared = MornyeRuntime()
    static let version = "5.2.5"
    private let lock = NSLock()
    private var manager: ExtensionManager?
    private var loadedProviders: [MornyeProvider] = []

    private var root: URL { HarmonyPaths.applicationSupport.appendingPathComponent("Mornye", isDirectory: true) }
    var staging: URL { HarmonyPaths.ensure(root.appendingPathComponent("Staging", isDirectory: true)) }

    private init() {}

    func prepare() throws -> [MornyeProvider] {
        lock.lock()
        defer { lock.unlock() }
        if manager != nil { return loadedProviders }
        let sources = root.appendingPathComponent("Extensions", isDirectory: true)
        let data = root.appendingPathComponent("Data", isDirectory: true)
        for path in [root, sources, data, staging] {
            try FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        }
        let created = try ExtensionManager(
            sourceDirectory: sources.path, dataDirectory: data.path,
            masterKey: Self.masterKey(), appVersion: Self.version, timeoutMs: 30_000)
        do {
            try created.environment().setAllowedDownloadDirectories(directories: [
                staging.standardizedFileURL.path, staging.resolvingSymlinksInPath().path
            ])
            _ = try created.loadAll()
            let installed = try JSONSerialization.jsonObject(with: Data(created.installed().utf8)) as? [[String: Any]] ?? []
            for id in MornyeProvider.order {
                guard let package = Bundle.main.url(forResource: id, withExtension: "sflx", subdirectory: "MornyeProviders") else {
                    throw MornyeError.message("The app is missing its \(id) download extension. Reinstall the complete Harmony build.")
                }
                // The app ships a verified, pinned package snapshot. Existing encrypted
                // provider state stays in Data; install/upgrade only replaces source.
                if let old = installed.first(where: { MornyeJSON.text($0["id"]) == id }) {
                    let manifestURL = package.deletingPathExtension().appendingPathExtension("json")
                    if let manifestData = try? Data(contentsOf: manifestURL),
                       let manifest = try JSONSerialization.jsonObject(with: manifestData) as? [String: Any] {
                        let bundledVersion = MornyeJSON.text(manifest["version"])
                        if MornyeJSON.text(old["version"]).compare(bundledVersion, options: .numeric) == .orderedAscending {
                            _ = try created.upgrade(packagePath: package.path)
                        }
                    }
                } else {
                    _ = try created.install(packagePath: package.path)
                }
                try created.setEnabled(extensionId: id, enabled: true)
            }
            let rows = try JSONSerialization.jsonObject(with: Data(created.installed().utf8)) as? [[String: Any]] ?? []
            let providers = MornyeProvider.order.compactMap { id -> MornyeProvider? in
                guard let row = rows.first(where: { MornyeJSON.text($0["id"]) == id }) else { return nil }
                let options = row["quality_options"] as? [[String: Any]] ?? []
                return MornyeProvider(id: id, name: MornyeProvider.names[id] ?? id,
                                     qualities: options.map { .init(id: MornyeJSON.text($0["id"]), kind: MornyeJSON.text($0["kind"])) })
            }
            guard !providers.isEmpty else { throw MornyeError.message("No Mornye providers could be loaded.") }
            try created.setProviderPriority(kind: "download", ids: providers.map(\.id))
            try created.setProviderPriority(kind: "metadata", ids: providers.map(\.id))
            try created.setFallbackProviders(ids: providers.map(\.id))
            loadedProviders = providers
            manager = created
            return providers
        } catch {
            created.shutdown()
            throw error
        }
    }

    private func owner() throws -> ExtensionManager {
        lock.lock()
        defer { lock.unlock() }
        guard let manager else { throw MornyeError.message("Mornye is not ready.") }
        return manager
    }

    func search(_ query: String, provider: String) throws -> [MornyeTrack] {
        let current = try owner()
        let raw: String
        if MornyeTrack.isProviderURL(query) {
            raw = try current.handleUrlJson(url: query)
        } else if provider.isEmpty {
            raw = try current.searchMetadataProviders(query: query, limit: 40, includeExtensions: true,
                                                       itemId: "", timeoutMs: 30_000)
        } else {
            raw = try current.searchMetadataProvider(extensionId: provider, query: query, limit: 40, timeoutMs: 30_000)
        }
        return try MornyeTrack.parseSearch(raw)
    }

    func pendingVerification(provider: String, jobID: UUID?) throws -> MornyeVerification? {
        let raw = try owner().getExtensionPendingAuthJson(extensionId: provider)
        guard !raw.isEmpty, raw != "null" else { return nil }
        let node = try MornyeJSON.object(raw)
        guard let url = MornyeJSON.httpsURL(node["auth_url"]) else { return nil }
        return MornyeVerification(providerID: provider, url: url, jobID: jobID)
    }

    func completeCallback(_ callback: MornyeAuthCallback) throws -> String {
        let current = try owner()
        let environment = current.environment()
        let provider: String
        if callback.sessionGrant {
            provider = try environment.resolveCallbackState(state: callback.state)
        } else {
            provider = try environment.consumeCallbackState(state: callback.state)
        }
        guard MornyeProvider.names[provider] != nil else { throw MornyeError.message("This callback does not belong to a Harmony download provider.") }
        if callback.sessionGrant { try environment.setSessionGrant(extensionId: provider, grant: callback.credential) }
        else { try environment.setAuthCode(extensionId: provider, code: callback.credential) }
        let action = callback.sessionGrant ? "completeGrant" : "completeSpotifyLogin"
        let response = try MornyeJSON.object(current.invokeAction(extensionId: provider, action: action))
        guard response["success"] as? Bool == true else { throw MornyeError.message(MornyeJSON.failure(response)) }
        return provider
    }

    /// One Mornye download runs at a time, so its isolated provider route is
    /// never changed beneath another attempt. Other sources keep their own slots.
    func download(track: MornyeTrack, provider: MornyeProvider, quality: MornyeQuality,
                  itemID: String, directory: URL) throws -> URL {
        let current = try owner()
        try current.setProviderPriority(kind: "download", ids: [provider.id])
        try current.setFallbackProviders(ids: [provider.id])
        let request = try track.request(provider: provider.id, quality: provider.qualityID(for: quality),
                                        itemID: itemID, directory: directory)
        let commands = current.environment().ffmpegCommands()
        let pump = CoreFFmpegExecution(
            wait: { try JSONDecoder().decode([CoreFFmpegCommand].self, from: Data(commands.waitPending(timeoutMs: $0).utf8)) },
            active: { try !commands.getCommand(commandId: $0).isEmpty },
            complete: { id, success, output, error in
                _ = try commands.complete(commandId: id, success: success, output: output, error: error)
            })
        let raw = try pump.run { try current.downloadByStrategy(requestJson: request) }
        let node = try MornyeJSON.object(raw)
        guard node["success"] as? Bool == true else {
            if MornyeJSON.text(node["error_type"]) == "verification_required" {
                let challenge = try? pendingVerification(provider: provider.id, jobID: nil)
                throw MornyeError.verification(provider.id, challenge?.url)
            }
            throw MornyeError.message("\(provider.name): \(MornyeJSON.failure(node))")
        }
        if let descriptor = node["decryption"] as? [String: Any], !descriptor.isEmpty {
            throw MornyeError.message("\(provider.name) returned a protected stream that Harmony cannot import.")
        }
        let rawPath = MornyeJSON.text(node["file_path"]).isEmpty ? MornyeJSON.text(node["file"]) : MornyeJSON.text(node["file_path"])
        let input = try MornyeJSON.stagedFile(rawPath, directory: directory)
        return try MornyeAudioFile.losslessFile(input, directory: directory) {
            (try? current.environment().downloadState().isCancelled(itemId: itemID)) != false
        }
    }

    func resetCancellation(_ itemID: String) throws {
        try owner().environment().downloadState().resetDownloadCancel(itemId: itemID)
    }
    func cancel(_ itemID: String) {
        // No lazy initialization here: a cancelled setup must not start a runtime.
        try? owner().environment().downloadState().cancelDownload(itemId: itemID)
    }
    func clearProgress(_ itemID: String) {
        try? owner().environment().downloadState().clearItemProgress(itemId: itemID)
    }
    func progress() throws -> [String: Any] {
        try MornyeJSON.object(owner().environment().downloadState().allProgress())["items"] as? [String: Any] ?? [:]
    }

    private static func masterKey() throws -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.harmony.app.ios.mornye",
            kSecAttrAccount as String: "extension-master-key-v1"
        ]
        var read = query
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data, data.count == 32 { return data.base64EncodedString() }
        guard status == errSecItemNotFound else {
            throw MornyeError.message("Mornye's saved key is unavailable. Unlock the iPhone and try again.")
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw MornyeError.message("Could not create the Mornye storage key.")
        }
        let data = Data(bytes)
        var create = query
        create[kSecValueData as String] = data
        create[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(create as CFDictionary, nil) == errSecSuccess else {
            throw MornyeError.message("Could not save the Mornye storage key in Keychain.")
        }
        return data.base64EncodedString()
    }
}
