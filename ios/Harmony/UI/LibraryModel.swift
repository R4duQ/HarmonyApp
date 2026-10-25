import Combine
import Foundation

/// An album in the library: its songs in disc and track order.
struct LibraryAlbum: Identifiable, Hashable {
    let title: String
    let artist: String
    let tracks: [LibraryTrack]

    var id: String { artist.lowercased() + "|" + title.lowercased() }
    var artworkURL: URL? { tracks.first(where: { $0.artworkURL != nil })?.artworkURL }
    var lossless: Bool { tracks.allSatisfy(\.lossless) }
    var hiRes: Bool { tracks.contains { $0.bitDepth >= 24 || $0.sampleRate > 48_000 } }
    var durationMs: Int { tracks.reduce(0) { $0 + $1.durationMs } }
}

/// An artist and their songs.
struct LibraryArtist: Identifiable, Hashable {
    let name: String
    let tracks: [LibraryTrack]
    let albumCount: Int

    var id: String { name.lowercased() }
    var artworkURL: URL? { tracks.first(where: { $0.artworkURL != nil })?.artworkURL }
}

/**
 * The library as the screens show it: songs, albums, artists, playlists and
 * listening statistics, read once and refreshed when the library changes
 * (a download, a folder scan, a new vector), so every section shares one
 * copy.
 */
@MainActor
final class LibraryModel: ObservableObject {
    static let shared = LibraryModel()

    @Published private(set) var tracks: [LibraryTrack] = []
    @Published private(set) var albums: [LibraryAlbum] = []
    @Published private(set) var artists: [LibraryArtist] = []
    @Published private(set) var playlists: [LibraryPlaylist] = []
    @Published private(set) var stats: [Int64: PlayStats] = [:]
    @Published private(set) var loaded = false

    var indexedCount: Int { tracks.filter(\.hasVector).count }

    private var cancellable: AnyCancellable?
    private var loading = false
    private var reloadAgain = false

    private init() {
        cancellable = NotificationCenter.default.publisher(for: .harmonyLibraryChanged)
            .debounce(for: .milliseconds(500), scheduler: RunLoop.main)
            .sink { [weak self] _ in
                Task { @MainActor in self?.reload() }
            }
        reload()
    }

    func reload() {
        guard !loading else {
            reloadAgain = true
            return
        }
        loading = true
        Task {
            let snapshot = await Task.detached(priority: .userInitiated) { Self.read() }.value
            tracks = snapshot.tracks
            albums = snapshot.albums
            artists = snapshot.artists
            playlists = snapshot.playlists
            stats = snapshot.stats
            loaded = true
            loading = false
            if reloadAgain {
                reloadAgain = false
                reload()
            }
        }
    }

    func track(id: Int64) -> LibraryTrack? {
        tracks.first { $0.id == id }
    }

    private struct Snapshot: Sendable {
        var tracks: [LibraryTrack]
        var albums: [LibraryAlbum]
        var artists: [LibraryArtist]
        var playlists: [LibraryPlaylist]
        var stats: [Int64: PlayStats]
    }

    private nonisolated static func read() -> Snapshot {
        let store = HarmonyLibraryStore.shared
        let tracks = store.allTracks().sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }

        var byAlbum: [String: [LibraryTrack]] = [:]
        var byArtist: [String: [LibraryTrack]] = [:]
        for track in tracks {
            let albumArtist = (track.albumArtist?.isEmpty == false ? track.albumArtist! : track.artist)
            if !track.album.isEmpty {
                byAlbum[albumArtist.lowercased() + "|" + track.album.lowercased(), default: []].append(track)
            }
            byArtist[track.artist.lowercased(), default: []].append(track)
        }
        let albums = byAlbum.values.map { songs -> LibraryAlbum in
            let ordered = songs.sorted {
                ($0.discNumber ?? 1, $0.trackNumber ?? Int.max, $0.title) < ($1.discNumber ?? 1, $1.trackNumber ?? Int.max, $1.title)
            }
            let first = ordered[0]
            let artist = first.albumArtist?.isEmpty == false ? first.albumArtist! : first.artist
            return LibraryAlbum(title: first.album, artist: artist, tracks: ordered)
        }
        .sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }

        let artists = byArtist.values.map { songs -> LibraryArtist in
            LibraryArtist(name: songs[0].artist, tracks: songs,
                          albumCount: Set(songs.map { $0.album.lowercased() }.filter { !$0.isEmpty }).count)
        }
        .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }

        return Snapshot(tracks: tracks, albums: albums, artists: artists, playlists: store.playlists(), stats: store.allPlayStats())
    }
}
