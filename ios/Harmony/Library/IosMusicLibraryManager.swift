import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/**
 * The folders from the Files app that Harmony reads in place.
 *
 * A folder picked in Files is outside Harmony's sandbox: it can be read only
 * while its security-scoped access is open. Harmony opens each folder's
 * access once (when the app starts, or when the folder is added) and keeps
 * it open while it runs, so the player, the tag reader and the 24-D scanner
 * can all open its files from any thread.
 *
 * Songs inside such a folder are stored as "@<folder id>/<path inside it>",
 * never as absolute paths: iOS may move the folder's mount point between
 * launches, and only the bookmark finds it again.
 */
final class FolderAccess: @unchecked Sendable {
    static let shared = FolderAccess()

    private let lock = NSLock()
    private var open: [Int64: URL] = [:]

    /// "@12/Albums/Song.flac" -> (12, "Albums/Song.flac").
    static func parse(_ stored: String) -> (folderId: Int64, inside: String)? {
        guard stored.hasPrefix("@"), let slash = stored.firstIndex(of: "/") else { return nil }
        guard let id = Int64(stored[stored.index(after: stored.startIndex)..<slash]) else { return nil }
        return (id, String(stored[stored.index(after: slash)...]))
    }

    static func storedPath(folderId: Int64, inside: String) -> String {
        "@\(folderId)/\(inside)"
    }

    /// The folder's location, when its access is open.
    func url(forFolder id: Int64) -> URL? {
        lock.lock()
        defer { lock.unlock() }
        return open[id]
    }

    /// Keeps [url] (whose access was already started) for [id]; closes any older one.
    func keep(_ url: URL, forFolder id: Int64) {
        lock.lock()
        let previous = open.updateValue(url, forKey: id)
        lock.unlock()
        if let previous, previous != url { previous.stopAccessingSecurityScopedResource() }
    }

    func close(folder id: Int64) {
        lock.lock()
        let url = open.removeValue(forKey: id)
        lock.unlock()
        url?.stopAccessingSecurityScopedResource()
    }
}

/**
 * Harmony's music library on iOS.
 *
 *  - Files › On My iPhone › Harmony (UIFileSharingEnabled): music copied
 *    there (or downloaded) is found by a scan of Documents.
 *  - Any folder in the Files app (iCloud Drive, a USB drive, another app's
 *    folder): picked once with UIDocumentPickerViewController, then kept
 *    through a bookmark, so it stays readable after restarts without copying
 *    a single file.
 *
 * Scans add new songs (tags and artwork through AVFoundation), refresh
 * changed ones, drop songs whose files are gone, and leave the 24-D vectors
 * to the background indexer, which follows the phone's temperature and
 * battery.
 */
@MainActor
final class IosMusicLibraryManager: ObservableObject {
    static let shared = IosMusicLibraryManager()

    struct ScanProgress: Equatable {
        var location: String
        var seen: Int
        var added: Int
        var removed: Int
    }

    /// The folders read in place.
    @Published private(set) var folders: [LibraryFolder] = []
    /// Folders whose bookmark no longer resolves (deleted, or the drive is unplugged).
    @Published private(set) var unavailableFolderIds: Set<Int64> = []
    /// The scan running now, if any.
    @Published private(set) var scan: ScanProgress?
    /// The last scan's outcome, for a short notice.
    @Published private(set) var lastScanSummary: String?
    @Published var lastError: String?

    /// Audio files Harmony can play (ExtAudioFile decodes these).
    nonisolated static let audioExtensions: Set<String> = ["flac", "mp3", "m4a", "aac", "alac", "wav", "wave", "aif", "aiff", "aifc", "caf", "m4b"]

    private let store = HarmonyLibraryStore.shared
    private var scanTask: Task<Void, Never>?
    private var started = false

    private init() {}

    // MARK: - Startup

    /// Reopens every folder's access and rescans the library. Call once at launch.
    func start() {
        guard !started else { return }
        started = true
        restoreFolderAccess()
        rescanAll()
    }

    private func restoreFolderAccess() {
        var unavailable = Set<Int64>()
        for folder in store.folders() {
            do {
                var stale = false
                let url = try URL(resolvingBookmarkData: folder.bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
                guard url.startAccessingSecurityScopedResource() else {
                    unavailable.insert(folder.id)
                    continue
                }
                FolderAccess.shared.keep(url, forFolder: folder.id)
                if stale, let fresh = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
                    store.updateFolder(id: folder.id, bookmark: fresh)
                }
            } catch {
                unavailable.insert(folder.id)
            }
        }
        unavailableFolderIds = unavailable
        folders = store.folders()
    }

    // MARK: - Folders

    /// A folder picked in the Files app: remember it and read its songs.
    func addFolder(_ url: URL) {
        let accessing = url.startAccessingSecurityScopedResource()
        do {
            let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            if let existing = folders.first(where: { FolderAccess.shared.url(forFolder: $0.id)?.standardizedFileURL == url.standardizedFileURL }) {
                if accessing { url.stopAccessingSecurityScopedResource() }
                rescan(folder: existing)
                return
            }
            let name = FileManager.default.displayName(atPath: url.path)
            let id = try store.addFolder(bookmark: bookmark, name: name.isEmpty ? url.lastPathComponent : name)
            if accessing {
                FolderAccess.shared.keep(url, forFolder: id)
            }
            folders = store.folders()
            unavailableFolderIds.remove(id)
            if let folder = folders.first(where: { $0.id == id }) { rescan(folder: folder) }
        } catch {
            if accessing { url.stopAccessingSecurityScopedResource() }
            lastError = "Harmony could not keep access to “\(url.lastPathComponent)”: \(error.localizedDescription)"
        }
    }

    /// Forgets the folder and its songs; the files stay where they are.
    func removeFolder(_ folder: LibraryFolder) {
        store.removeFolder(folder)
        FolderAccess.shared.close(folder: folder.id)
        folders = store.folders()
        unavailableFolderIds.remove(folder.id)
        LibraryIngest.shared.refreshCount()
        NotificationCenter.default.post(name: .harmonyLibraryChanged, object: nil)
    }

    // MARK: - Scanning

    /// One place to scan: Documents (no prefix) or a folder read in place.
    private struct ScanTarget {
        let name: String
        let url: URL
        let prefix: String
        let folderId: Int64?
    }

    /// Documents and every folder, one after the other.
    func rescanAll() {
        var targets = [ScanTarget(name: "On My iPhone", url: HarmonyPaths.documents, prefix: "", folderId: nil)]
        for folder in folders {
            if let url = FolderAccess.shared.url(forFolder: folder.id) {
                targets.append(ScanTarget(name: folder.name, url: url, prefix: folder.pathPrefix, folderId: folder.id))
            }
        }
        runScan(targets)
    }

    func rescan(folder: LibraryFolder) {
        guard let url = FolderAccess.shared.url(forFolder: folder.id) else {
            unavailableFolderIds.insert(folder.id)
            return
        }
        runScan([ScanTarget(name: folder.name, url: url, prefix: folder.pathPrefix, folderId: folder.id)])
    }

    /// Scans run one at a time; a new request waits for the one in progress.
    private func runScan(_ targets: [ScanTarget]) {
        let previous = scanTask
        scanTask = Task { [weak self] in
            await previous?.value
            guard let self else { return }
            var added = 0
            var removed = 0
            for target in targets {
                let result = await self.scanLocation(target.name, base: target.url, prefix: target.prefix)
                added += result.added
                removed += result.removed
                if let folderId = target.folderId {
                    self.store.markFolderScanned(id: folderId)
                }
            }
            self.scan = nil
            self.folders = self.store.folders()
            if added > 0 || removed > 0 {
                var parts: [String] = []
                if added > 0 { parts.append("\(added) new song\(added == 1 ? "" : "s")") }
                if removed > 0 { parts.append("\(removed) removed") }
                self.lastScanSummary = parts.joined(separator: ", ")
                LibraryIngest.shared.indexMissingVectors()
            }
        }
    }

    /// One location: add what is new or changed, drop what is gone.
    private func scanLocation(_ location: String, base: URL, prefix: String) async -> (added: Int, removed: Int) {
        scan = ScanProgress(location: location, seen: 0, added: 0, removed: 0)
        let known = store.pathIndex(prefix: prefix)
        let files = await Task.detached(priority: .utility) { Self.audioFiles(under: base) }.value

        var seen = Set<String>()
        var added = 0
        for (index, file) in files.enumerated() {
            if Task.isCancelled { break }
            let stored = prefix + file.inside
            // Documents' own scan skips songs that belong to a folder.
            seen.insert(stored)
            if let existing = known[stored], existing.size == file.size {
                if index % 50 == 0 { scan?.seen = index + 1 }
                continue
            }
            let source = prefix.isEmpty ? Self.sourceName(forDocumentsPath: file.inside) : "Files"
            let ok = (try? await LibraryIngest.shared.ingest(fileAt: file.url, source: source,
                                                             relativePath: stored, measureNow: false)) != nil
            if ok && known[stored] == nil { added += 1 }
            scan = ScanProgress(location: location, seen: index + 1, added: added, removed: 0)
        }

        // Songs whose files are gone. Documents' scan leaves "@…" songs to their folders.
        let gone = known.filter { path, _ in
            !seen.contains(path) && (prefix.isEmpty ? FolderAccess.parse(path) == nil : true)
        }
        // A file still downloading from iCloud is not "gone".
        let missing = gone.filter { path, _ in
            !FileManager.default.fileExists(atPath: HarmonyPaths.url(forRelative: path).path)
        }
        store.delete(trackIds: missing.values.map { $0.id })
        if !missing.isEmpty {
            LibraryIngest.shared.refreshCount()
            NotificationCenter.default.post(name: .harmonyLibraryChanged, object: nil)
        }
        return (added, missing.count)
    }

    struct FoundFile: Sendable {
        let url: URL
        /// The path inside the scanned location.
        let inside: String
        let size: Int64
    }

    /// Every playable file under [base], skipping hidden files and packages.
    /// Files in iCloud that are not on the phone yet are asked for, and found next time.
    nonisolated static func audioFiles(under base: URL) -> [FoundFile] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .isUbiquitousItemKey,
                                      .ubiquitousItemDownloadingStatusKey]
        guard let enumerator = FileManager.default.enumerator(
            at: base, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else { return [] }
        let basePath = base.standardizedFileURL.resolvingSymlinksInPath().path
        var found: [FoundFile] = []
        for case let url as URL in enumerator {
            guard audioExtensions.contains(url.pathExtension.lowercased()) else { continue }
            guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else { continue }
            if values.isUbiquitousItem == true, values.ubiquitousItemDownloadingStatus != .current {
                try? FileManager.default.startDownloadingUbiquitousItem(at: url)
                continue
            }
            let path = url.standardizedFileURL.resolvingSymlinksInPath().path
            guard path.hasPrefix(basePath + "/") else { continue }
            found.append(FoundFile(url: url, inside: String(path.dropFirst(basePath.count + 1)),
                                   size: Int64(values.fileSize ?? 0)))
        }
        return found.sorted { $0.inside.localizedStandardCompare($1.inside) == .orderedAscending }
    }

    /// "Soulseek", "Link", "Imported" or "Files", by where a file sits in Documents.
    nonisolated static func sourceName(forDocumentsPath path: String) -> String {
        if path.hasPrefix("Downloads/Soulseek/") { return "Soulseek" }
        if path.hasPrefix("Downloads/Links/") { return "Link" }
        if path.hasPrefix("Imported/") { return "Imported" }
        return "Files"
    }
}

/// The Files app's folder picker. The folder comes back with its security-scoped access ready.
struct FolderPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void

        init(onPick: @escaping (URL) -> Void) {
            self.onPick = onPick
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let url = urls.first { onPick(url) }
        }
    }
}
