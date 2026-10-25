import Foundation

/// Where Harmony keeps things in its sandbox.
///
/// Music lives under Documents, which the Files app shows as
/// "On My iPhone › Harmony": downloads in Documents/Downloads, files opened
/// in Harmony from elsewhere in Documents/Imported. Paths are stored relative
/// to Documents, because the sandbox's absolute path changes when iOS
/// reinstalls or updates the app.
enum HarmonyPaths {
    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static var downloads: URL { ensure(documents.appendingPathComponent("Downloads", isDirectory: true)) }
    static var soulseekDownloads: URL { ensure(downloads.appendingPathComponent("Soulseek", isDirectory: true)) }
    static var linkDownloads: URL { ensure(downloads.appendingPathComponent("Links", isDirectory: true)) }
    static var imported: URL { ensure(documents.appendingPathComponent("Imported", isDirectory: true)) }

    static var applicationSupport: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return ensure(base.appendingPathComponent("Harmony", isDirectory: true))
    }

    static var artwork: URL { ensure(applicationSupport.appendingPathComponent("Artwork", isDirectory: true)) }
    static var database: URL { applicationSupport.appendingPathComponent("library.sqlite") }

    static var caches: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    }

    /// Partial Soulseek downloads (resumable; iOS may purge them under storage pressure).
    static var soulseekPartials: URL { ensure(caches.appendingPathComponent("SoulseekPartials", isDirectory: true)) }
    /// Link downloads waiting to be checked and converted.
    static var incoming: URL { ensure(caches.appendingPathComponent("Incoming", isDirectory: true)) }
    /// Scratch space for conversions and tagging.
    static var scratch: URL { ensure(caches.appendingPathComponent("Scratch", isDirectory: true)) }

    @discardableResult
    static func ensure(_ url: URL) -> URL {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// The path of [url] relative to Documents, or nil when it lies elsewhere.
    static func relativePath(for url: URL) -> String? {
        let base = documents.standardizedFileURL.resolvingSymlinksInPath().path
        let path = url.standardizedFileURL.resolvingSymlinksInPath().path
        guard path.hasPrefix(base + "/") else { return nil }
        return String(path.dropFirst(base.count + 1))
    }

    /// Where a stored path points: under Documents, or "@<folder>/…" inside a
    /// folder from the Files app (see FolderAccess).
    static func url(forRelative path: String) -> URL {
        if let stored = FolderAccess.parse(path), let base = FolderAccess.shared.url(forFolder: stored.folderId) {
            return base.appendingPathComponent(stored.inside)
        }
        return documents.appendingPathComponent(path)
    }

    /// A file name safe for the Files app: no separators or control characters, at most 150 characters.
    static func safeFileName(_ name: String) -> String {
        let banned = CharacterSet(charactersIn: "/\\:*?\"<>|").union(.controlCharacters)
        var cleaned = String(name.unicodeScalars.map { banned.contains($0) ? "_" : Character($0) })
            .trimmingCharacters(in: .whitespacesAndNewlines)
        while cleaned.hasSuffix(".") { cleaned.removeLast() }
        if cleaned.count > 150 { cleaned = String(cleaned.prefix(150)) }
        return cleaned.isEmpty ? "Untitled" : cleaned
    }

    /// [directory]/[base].[ext], or "[base] (2).[ext]" and so on when taken.
    static func uniqueFile(in directory: URL, base: String, extension ext: String) -> URL {
        let safe = safeFileName(base)
        var candidate = directory.appendingPathComponent(safe).appendingPathExtension(ext)
        var n = 2
        while FileManager.default.fileExists(atPath: candidate.path) && n < 1000 {
            candidate = directory.appendingPathComponent("\(safe) (\(n))").appendingPathExtension(ext)
            n += 1
        }
        return candidate
    }
}
