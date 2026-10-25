import Foundation

enum MornyeQuality: String, Codable, CaseIterable, Identifiable {
    case lossless, hiRes
    var id: String { rawValue }
    var title: String { self == .hiRes ? "Hi-Res lossless" : "Lossless" }
}

struct MornyeProvider: Identifiable, Equatable, Sendable {
    struct Quality: Equatable, Sendable {
        let id: String
        let kind: String
    }
    let id: String
    let name: String
    let qualities: [Quality]

    static let names = [
        "tidal-web": "Tidal", "qobuz-web": "Qobuz", "deezer": "Deezer", "amazon": "Amazon"
    ]
    static let order = ["tidal-web", "qobuz-web", "deezer", "amazon"]

    func qualityID(for selection: MornyeQuality) throws -> String {
        let eligible = qualities.filter {
            let id = $0.id.lowercased()
            return $0.kind == "lossless" ||
                ($0.kind.isEmpty && ["flac", "lossless", "hi_res", "hi_res_lossless", "best"].contains(id))
        }
        let preferences = selection == .hiRes
            ? ["HI_RES_LOSSLESS", "HI_RES", "best", "LOSSLESS", "flac"]
            : ["LOSSLESS", "flac", "HI_RES", "HI_RES_LOSSLESS", "best"]
        for wanted in preferences {
            if let option = eligible.first(where: { $0.id.caseInsensitiveCompare(wanted) == .orderedSame }) {
                return option.id
            }
        }
        guard let first = eligible.first else { throw MornyeError.message("\(name) offers no lossless download quality.") }
        return first.id
    }
}

struct MornyeTrack: Identifiable, Hashable, Codable, Sendable {
    let providerID: String
    let trackID: String
    let title: String
    let artist: String
    let album: String
    let durationMs: Int
    let isrc: String
    let coverURL: URL?
    var id: String { providerID + ":" + trackID }

    static func isProviderURL(_ text: String) -> Bool {
        guard let url = URL(string: text), url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(), url.user == nil, url.password == nil else { return false }
        let domains = ["tidal.com", "qobuz.com", "deezer.com", "deezer.page.link",
                       "music.amazon.com", "music.amazon.co.uk", "music.amazon.de", "music.amazon.co.jp",
                       "music.amazon.fr", "music.amazon.it", "music.amazon.es", "music.amazon.in",
                       "music.amazon.com.br", "music.amazon.com.mx", "music.amazon.com.au"]
        return domains.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    static func parseSearch(_ raw: String) throws -> [MornyeTrack] {
        let value = try JSONSerialization.jsonObject(with: Data(raw.utf8))
        let rows: [[String: Any]]
        var fallbackProvider = ""
        if let array = value as? [[String: Any]] { rows = array }
        else if let object = value as? [String: Any] {
            if object["error"] != nil { throw MornyeError.message(MornyeJSON.failure(object)) }
            fallbackProvider = MornyeJSON.text(object["extension_id"])
            if let track = object["track"] as? [String: Any] { rows = [track] }
            else { rows = (object["tracks"] ?? object["results"] ?? object["data"]) as? [[String: Any]] ?? [] }
        } else { throw MornyeError.message("Mornye returned an invalid search response.") }
        var seen = Set<String>()
        return rows.compactMap { row in
            let kind = MornyeJSON.text(row["item_type"])
            let provider = MornyeJSON.text(row["provider_id"]).isEmpty ? fallbackProvider : MornyeJSON.text(row["provider_id"])
            let trackID = MornyeJSON.text(row["id"])
            let title = MornyeJSON.text(row["name"])
            guard kind.isEmpty || kind == "track", MornyeProvider.names[provider] != nil,
                  !trackID.isEmpty, !title.isEmpty else { return nil }
            let track = MornyeTrack(
                providerID: provider, trackID: trackID, title: title,
                artist: MornyeJSON.text(row["artists"]).nilIfEmpty ?? MornyeJSON.text(row["album_artist"]),
                album: MornyeJSON.text(row["album_name"]),
                durationMs: max(0, MornyeJSON.integer(row["duration_ms"])),
                isrc: MornyeJSON.text(row["isrc"]),
                coverURL: MornyeJSON.httpsURL(row["cover_url"]) ?? MornyeJSON.httpsURL(row["images"]))
            return seen.insert(track.id).inserted ? track : nil
        }
    }

    func request(provider: String, quality: String, itemID: String, directory: URL) throws -> String {
        var node: [String: Any] = [
            "contract_version": 1, "use_extensions": true, "use_fallback": false,
            "service": provider, "source": providerID, "download_provider": provider,
            "provider_track_id": provider == providerID ? trackID : "",
            "track_name": title, "artist_name": artist, "album_name": album, "album_artist": artist,
            "duration_ms": durationMs, "isrc": isrc, "cover_url": coverURL?.absoluteString ?? "",
            "output_dir": directory.path, "output_ext": ".flac", "filename_format": "{artist} - {title}",
            "quality": quality, "item_id": itemID, "allow_quality_variant": false,
            "requires_container_conversion": true, "embed_metadata": false,
            "embed_lyrics": false, "embed_replaygain": false, "post_processing_enabled": false
        ]
        let fields = ["tidal-web": "tidal_id", "qobuz-web": "qobuz_id", "deezer": "deezer_id"]
        if let field = fields[providerID] { node[field] = trackID }
        return String(decoding: try JSONSerialization.data(withJSONObject: node, options: [.sortedKeys]), as: UTF8.self)
    }
}

struct MornyeAuthCallback: Equatable {
    let state: String
    let credential: String
    let sessionGrant: Bool

    static func parse(_ url: URL) -> MornyeAuthCallback? {
        guard url.scheme?.lowercased() == "mornye",
              ["callback", "spotify-callback", "session-grant"].contains(url.host?.lowercased() ?? ""),
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let grant = url.host?.lowercased() == "session-grant"
        let query = parts.queryItems ?? []
        let state = query.first(where: { $0.name == "state" })?.value ?? ""
        let credential = query.first(where: { $0.name == (grant ? "grant" : "code") })?.value ?? ""
        guard !state.isEmpty, state.count <= 1024, !credential.isEmpty, credential.count <= 65_536 else { return nil }
        return MornyeAuthCallback(state: state, credential: credential, sessionGrant: grant)
    }
}

struct MornyeVerification: Identifiable {
    let providerID: String
    let url: URL
    let jobID: UUID?
    var id: String { providerID }
}

enum MornyeError: LocalizedError {
    case message(String)
    case verification(String, URL?)
    var errorDescription: String? {
        switch self {
        case .message(let message): return MornyeJSON.redact(message)
        case .verification(let provider, _):
            return "\(MornyeProvider.names[provider] ?? provider) needs verification. Verify the provider, then resume."
        }
    }
}

enum MornyeJSON {
    static func object(_ raw: String) throws -> [String: Any] {
        guard let result = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] else {
            throw MornyeError.message("Mornye returned an invalid response.")
        }
        return result
    }
    static func text(_ value: Any?) -> String {
        if let text = value as? String { return text.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let number = value as? NSNumber { return number.stringValue }
        if let names = value as? [String] { return names.joined(separator: ", ") }
        return ""
    }
    static func integer(_ value: Any?) -> Int {
        if let number = value as? NSNumber { return number.intValue }
        return Int(text(value)) ?? 0
    }
    static func httpsURL(_ value: Any?) -> URL? {
        guard let url = URL(string: text(value)), url.scheme?.lowercased() == "https",
              url.host != nil, url.user == nil, url.password == nil else { return nil }
        return url
    }
    static func failure(_ node: [String: Any]) -> String {
        text(node["error"]).nilIfEmpty ?? text(node["message"]).nilIfEmpty ?? "The provider did not return a download."
    }
    static func redact(_ text: String) -> String {
        let redacted = text.replacingOccurrences(of: #"https?://[^\s"'<>]+"#, with: "[provider URL]", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)(token|grant|secret|password|auth_code|master_key)\s*[=:]\s*\S+"#,
                                  with: "$1=[redacted]", options: .regularExpression)
        return String(redacted.prefix(500))
    }

    /// A provider path is usable only inside this attempt's staging directory.
    static func stagedFile(_ raw: String, directory: URL) throws -> URL {
        let root = directory.standardizedFileURL.resolvingSymlinksInPath()
        let file = URL(fileURLWithPath: raw).standardizedFileURL.resolvingSymlinksInPath()
        guard file.path.hasPrefix(root.path + "/"),
              (try? file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true,
              ((try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0) > 0 else {
            throw MornyeError.message("The provider returned no usable audio file in its download directory.")
        }
        return file
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
