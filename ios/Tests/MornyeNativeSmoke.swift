import Foundation

/// Runs the actual generated Swift binding against the same pinned Rust
/// library on macOS; no provider account or external network is needed.
@main
enum MornyeNativeSmoke {
    static func main() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let sources = root.appendingPathComponent("extensions")
        let data = root.appendingPathComponent("data")
        let staging = root.appendingPathComponent("staging")
        for url in [sources, data, staging] {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        let owner = try ExtensionManager(sourceDirectory: sources.path, dataDirectory: data.path,
                                         masterKey: Data(repeating: 42, count: 32).base64EncodedString(),
                                         appVersion: "5.2.5", timeoutMs: 1000)
        defer { owner.shutdown() }
        let environment = owner.environment()
        try environment.setAllowedDownloadDirectories(directories: [staging.path])
        _ = try owner.loadAll()
        let installed = try JSONSerialization.jsonObject(with: Data(owner.installed().utf8)) as? [Any]
        precondition(installed?.isEmpty == true)
        let response = try owner.downloadByStrategy(requestJson: #"{"use_extensions":false,"item_id":"smoke"}"#)
        let failure = try JSONSerialization.jsonObject(with: Data(response.utf8)) as? [String: Any]
        precondition(failure?["success"] as? Bool == false)
        let state = environment.downloadState()
        try state.cancelDownload(itemId: "queued")
        let cancelled = try state.isCancelled(itemId: "queued")
        precondition(cancelled)
        try state.resetDownloadCancel(itemId: "queued")
        let reset = try state.isCancelled(itemId: "queued")
        precondition(!reset)
        try state.initItemProgress(itemId: "progress")
        try state.setProgress(itemId: "progress", progress: 0.5, received: 100, total: 200)
        let progress = try JSONSerialization.jsonObject(with: Data(state.allProgress().utf8)) as? [String: Any]
        let item = (progress?["items"] as? [String: [String: Any]])?["progress"]
        precondition(item?["bytes_received"] as? Int == 100)
        let oldAttempt = try state.acquire(itemId: "old-attempt")
        let newAttempt = try state.acquire(itemId: "new-attempt")
        try state.cancelDownload(itemId: "old-attempt")
        let newCancelled = try state.isCancelled(itemId: "new-attempt")
        precondition(!newCancelled, "Late cancellation must not affect a new attempt")
        oldAttempt.release()
        newAttempt.release()
        try state.clearItemProgress(itemId: "progress")
        let cleared = try JSONSerialization.jsonObject(with: Data(state.allProgress().utf8)) as? [String: Any]
        precondition((cleared?["items"] as? [String: Any])?["progress"] == nil)

        // Exercise the shipped packages in the actual Rust/JavaScript runtime,
        // without a provider account or network request.
        let packages = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            .appendingPathComponent("ios/Vendor/Mornye/MornyeProviders")
        let expected = ["tidal-web", "qobuz-web", "deezer", "amazon"]
        for id in expected {
            _ = try owner.install(packagePath: packages.appendingPathComponent(id + ".sflx").path)
            try owner.setEnabled(extensionId: id, enabled: true)
        }
        let rows = try JSONSerialization.jsonObject(with: Data(owner.installed().utf8)) as? [[String: Any]] ?? []
        for id in expected {
            guard let row = rows.first(where: { $0["id"] as? String == id }),
                  let qualities = row["quality_options"] as? [[String: Any]], !qualities.isEmpty else {
                fatalError("Bundled provider failed to load its quality options: \(id)")
            }
            precondition(qualities.contains { $0["kind"] as? String == "lossless" || $0["id"] as? String == "flac" })
        }
        try owner.setProviderPriority(kind: "download", ids: expected)
        try owner.setProviderPriority(kind: "metadata", ids: expected)
        try owner.setFallbackProviders(ids: expected)
        let commands = environment.ffmpegCommands()
        let pending = try commands.waitPending(timeoutMs: 0)
        precondition(pending == "[]")
        do {
            _ = try environment.consumeCallbackState(state: "not-issued-by-this-runtime")
            fatalError("Unknown callback state was accepted")
        } catch {}
        print("Mornye native Swift/Rust initialization, download errors, cancellation, progress, bundled providers and callback checks passed")
    }
}
