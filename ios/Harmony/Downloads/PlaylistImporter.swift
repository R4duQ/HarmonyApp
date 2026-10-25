import AuthenticationServices
import CryptoKit
import Foundation
import UIKit

/// A playlist offered for import.
struct RemotePlaylist: Identifiable, Hashable {
    let id: String
    let name: String
    let owner: String
    let trackCount: Int
    let imageURL: URL?
    let importable: Bool
}

enum PlaylistImportError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case .message(let text): return text
        }
    }
}

/**
 * Playlist import: Spotify (your own playlists, through Spotify's Web API and
 * your own app's Client ID, as on Android), any public Deezer playlist link,
 * or a pasted list of "Artist - Title" lines. The songs go to the
 * LosslessTrackResolver.
 *
 * Spotify sign-in is PKCE through ASWebAuthenticationSession, with the same
 * redirect URI as the Android app (com.harmony.app://spotify-playlist-callback),
 * so one Spotify developer app serves both.
 */
@MainActor
final class PlaylistImporter: ObservableObject {
    static let shared = PlaylistImporter()
    static let redirectURI = "com.harmony.app://spotify-playlist-callback"

    @Published var spotifyClientId: String {
        didSet { UserDefaults.standard.set(spotifyClientId.trimmingCharacters(in: .whitespaces), forKey: "spotify.clientId") }
    }
    @Published private(set) var spotifyConnected = false
    @Published private(set) var playlists: [RemotePlaylist] = []
    @Published private(set) var loading = false
    @Published var errorMessage: String?

    private var accessToken: String?
    private var accessTokenExpiry = Date.distantPast
    private var authSession: ASWebAuthenticationSession?
    private var anchorProvider: AuthAnchorProvider?

    private init() {
        spotifyClientId = UserDefaults.standard.string(forKey: "spotify.clientId") ?? ""
        spotifyConnected = HarmonyKeychain.get("spotify.refresh") != nil
    }

    private func keyWindow() -> UIWindow {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow } ?? UIWindow()
    }

    // MARK: - Spotify

    func connectSpotify() async {
        let clientId = spotifyClientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clientId.range(of: "^[A-Za-z0-9]{32}$", options: .regularExpression) != nil else {
            errorMessage = "Paste the 32-character Client ID of your Spotify developer app."
            return
        }
        let verifier = Self.randomVerifier()
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncoded
        var components = URLComponents(string: "https://accounts.spotify.com/authorize")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientId),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "redirect_uri", value: Self.redirectURI),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "scope", value: "playlist-read-private playlist-read-collaborative"),
        ]
        do {
            let callback: URL = try await withCheckedThrowingContinuation { continuation in
                let session = ASWebAuthenticationSession(url: components.url!, callbackURLScheme: "com.harmony.app") { url, error in
                    if let url { continuation.resume(returning: url) }
                    else { continuation.resume(throwing: error ?? PlaylistImportError.message("Spotify sign-in was cancelled.")) }
                }
                let provider = AuthAnchorProvider(anchor: self.keyWindow())
                self.anchorProvider = provider
                session.presentationContextProvider = provider
                session.prefersEphemeralWebBrowserSession = false
                self.authSession = session
                session.start()
            }
            guard let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "code" })?.value else {
                throw PlaylistImportError.message("Spotify did not return an authorization code.")
            }
            try await tokenRequest([
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": Self.redirectURI,
                "client_id": clientId,
                "code_verifier": verifier,
            ])
            spotifyConnected = true
            await loadSpotifyPlaylists()
        } catch {
            if (error as? ASWebAuthenticationSessionError)?.code != .canceledLogin {
                errorMessage = error.localizedDescription
            }
        }
    }

    func disconnectSpotify() {
        HarmonyKeychain.set(nil, for: "spotify.refresh")
        accessToken = nil
        spotifyConnected = false
        playlists = []
    }

    func loadSpotifyPlaylists() async {
        loading = true
        defer { loading = false }
        do {
            let me = try await spotifyGet("https://api.spotify.com/v1/me")
            let myId = me["id"] as? String ?? ""
            var result: [RemotePlaylist] = []
            var next: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
            var pages = 0
            while let url = next, pages < 100 {
                pages += 1
                let page = try await spotifyGet(url)
                for item in page["items"] as? [[String: Any]] ?? [] {
                    guard let id = item["id"] as? String, let name = item["name"] as? String else { continue }
                    let owner = item["owner"] as? [String: Any]
                    let ownerId = owner?["id"] as? String ?? ""
                    let collection = (item["items"] ?? item["tracks"]) as? [String: Any]
                    result.append(RemotePlaylist(
                        id: id, name: name,
                        owner: owner?["display_name"] as? String ?? ownerId,
                        trackCount: collection?["total"] as? Int ?? 0,
                        imageURL: Self.firstImage(item["images"]),
                        importable: ownerId == myId || (item["collaborative"] as? Bool ?? false)))
                }
                next = page["next"] as? String
            }
            playlists = result
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func spotifyTracks(playlistId: String) async throws -> [ImportTrack] {
        var tracks: [ImportTrack] = []
        var next: String? = "https://api.spotify.com/v1/playlists/\(playlistId)/items?limit=50"
        var pages = 0
        while let url = next, pages < 200 {
            pages += 1
            let page: [String: Any]
            do {
                page = try await spotifyGet(url)
            } catch PlaylistImportError.message(let text) where text.contains("404") && pages == 1 {
                // Older API shape.
                next = "https://api.spotify.com/v1/playlists/\(playlistId)/tracks?limit=50"
                continue
            }
            for wrapper in page["items"] as? [[String: Any]] ?? [] {
                guard let item = (wrapper["item"] ?? wrapper["track"]) as? [String: Any],
                      (item["type"] as? String ?? "track") == "track",
                      !(wrapper["is_local"] as? Bool ?? false),
                      let title = item["name"] as? String else { continue }
                let artists = (item["artists"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }
                guard !artists.isEmpty else { continue }
                let album = item["album"] as? [String: Any]
                tracks.append(ImportTrack(
                    title: title,
                    artist: artists.joined(separator: ", "),
                    album: album?["name"] as? String,
                    durationMs: item["duration_ms"] as? Int,
                    isrc: (item["external_ids"] as? [String: Any])?["isrc"] as? String,
                    coverArtURL: Self.firstImage(album?["images"])))
            }
            next = page["next"] as? String
        }
        return tracks
    }

    // MARK: - Deezer

    /// Tracks of a public Deezer playlist ("https://www.deezer.com/…/playlist/123", a deezer.page.link short link, or the id).
    func deezerTracks(link: String) async throws -> (String, [ImportTrack]) {
        var text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.contains("deezer.page.link"), let short = URL(string: text) {
            // Short links redirect to the real playlist URL.
            var request = URLRequest(url: short)
            request.httpMethod = "HEAD"
            let (_, response) = try await URLSession.shared.data(for: request)
            text = response.url?.absoluteString ?? text
        }
        guard let id = text.range(of: #"playlist/(\d+)"#, options: .regularExpression)
            .map({ String(text[$0].dropFirst("playlist/".count)) }) ?? (text.allSatisfy(\.isNumber) ? text : nil) else {
            throw PlaylistImportError.message("That is not a Deezer playlist link.")
        }
        let info = try await getJSON("https://api.deezer.com/playlist/\(id)")
        if let error = info["error"] as? [String: Any] {
            throw PlaylistImportError.message(error["message"] as? String ?? "Deezer could not open that playlist.")
        }
        let name = info["title"] as? String ?? "Deezer playlist"
        var tracks: [ImportTrack] = []
        var next: String? = "https://api.deezer.com/playlist/\(id)/tracks?limit=100"
        var pages = 0
        while let url = next, pages < 100 {
            pages += 1
            let page = try await getJSON(url)
            for item in page["data"] as? [[String: Any]] ?? [] {
                guard let title = item["title"] as? String,
                      let artist = (item["artist"] as? [String: Any])?["name"] as? String else { continue }
                let album = item["album"] as? [String: Any]
                tracks.append(ImportTrack(
                    title: title, artist: artist, album: album?["title"] as? String,
                    durationMs: (item["duration"] as? Int).map { $0 * 1000 },
                    isrc: item["isrc"] as? String,
                    coverArtURL: (album?["cover_xl"] as? String).flatMap(URL.init(string:))))
            }
            next = (page["next"] as? String)?.replacingOccurrences(of: "http://", with: "https://")
        }
        return (name, tracks)
    }

    // MARK: - Pasted list

    /// One song per line: "Artist - Title" (also "Artist – Title", or "Title by Artist").
    func pastedTracks(_ text: String) -> [ImportTrack] {
        text.split(whereSeparator: \.isNewline).compactMap { raw in
            let line = raw.trimmingCharacters(in: .whitespaces)
                .replacingOccurrences(of: #"^\d{1,3}[\.\)]\s*"#, with: "", options: .regularExpression)
            guard !line.isEmpty else { return nil }
            let (artist, title) = LibraryIngest.splitArtistTitle(line)
            if let artist, let title { return ImportTrack(title: title, artist: artist) }
            if let range = line.range(of: " by ", options: [.caseInsensitive, .backwards]) {
                let title = String(line[..<range.lowerBound]).trimmingCharacters(in: .whitespaces)
                let artist = String(line[range.upperBound...]).trimmingCharacters(in: .whitespaces)
                if !title.isEmpty, !artist.isEmpty { return ImportTrack(title: title, artist: artist) }
            }
            return nil
        }
    }

    // MARK: - HTTP

    private func tokenRequest(_ parameters: [String: String]) async throws {
        var request = URLRequest(url: URL(string: "https://accounts.spotify.com/api/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = parameters
            .map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .urlQueryValueAllowed) ?? "")" }
            .joined(separator: "&").data(using: .utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard (response as? HTTPURLResponse)?.statusCode == 200, let token = json["access_token"] as? String else {
            if parameters["grant_type"] == "refresh_token" { disconnectSpotify() }
            throw PlaylistImportError.message(json["error_description"] as? String ?? "Spotify sign-in failed.")
        }
        accessToken = token
        accessTokenExpiry = Date().addingTimeInterval(TimeInterval((json["expires_in"] as? Int ?? 3600) - 60))
        if let refresh = json["refresh_token"] as? String { HarmonyKeychain.set(refresh, for: "spotify.refresh") }
    }

    private func validAccessToken() async throws -> String {
        if let accessToken, Date() < accessTokenExpiry { return accessToken }
        guard let refresh = HarmonyKeychain.get("spotify.refresh") else {
            spotifyConnected = false
            throw PlaylistImportError.message("Sign in to Spotify first.")
        }
        try await tokenRequest(["grant_type": "refresh_token", "refresh_token": refresh,
                                "client_id": spotifyClientId.trimmingCharacters(in: .whitespaces)])
        guard let accessToken else { throw PlaylistImportError.message("Spotify sign-in failed.") }
        return accessToken
    }

    private func spotifyGet(_ url: String) async throws -> [String: Any] {
        var request = URLRequest(url: URL(string: url)!)
        request.setValue("Bearer \(try await validAccessToken())", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            if status == 403 {
                throw PlaylistImportError.message("Spotify shows playlist songs only for playlists you own or collaborate on.")
            }
            throw PlaylistImportError.message("Spotify answered \(status).")
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private func getJSON(_ url: String) async throws -> [String: Any] {
        guard let target = URL(string: url) else { throw PlaylistImportError.message("Bad address") }
        let (data, _) = try await URLSession.shared.data(from: target)
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private static func firstImage(_ value: Any?) -> URL? {
        ((value as? [[String: Any]])?.first?["url"] as? String).flatMap(URL.init(string:))
    }

    private static func randomVerifier() -> String {
        let characters = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        var generator = SystemRandomNumberGenerator()
        return String((0..<96).map { _ in characters[Int(generator.next() % UInt64(characters.count))] })
    }
}

/// Tells the sign-in sheet which window to appear over (chosen on the main actor beforehand).
final class AuthAnchorProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    private let anchor: ASPresentationAnchor

    init(anchor: ASPresentationAnchor) {
        self.anchor = anchor
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor
    }
}

private extension Data {
    var base64URLEncoded: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private extension CharacterSet {
    static let urlQueryValueAllowed: CharacterSet = {
        var set = CharacterSet.urlQueryAllowed
        set.remove(charactersIn: "&+=?")
        return set
    }()
}
