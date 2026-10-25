import Foundation
import SQLite3

extension Notification.Name {
    /// Posted on the main thread whenever songs or their vectors change.
    static let harmonyLibraryChanged = Notification.Name("HarmonyLibraryChanged")
}

/// A song in the library.
struct LibraryTrack: Identifiable, Hashable {
    let id: Int64
    var relativePath: String
    var title: String
    var artist: String
    var album: String
    var albumArtist: String?
    var trackNumber: Int?
    var discNumber: Int?
    var durationMs: Int
    var sampleRate: Int
    var bitDepth: Int
    var channels: Int
    var codec: String
    var lossless: Bool
    var fileSize: Int64
    /// "Soulseek", "Link", "Imported", "Files".
    var source: String
    var isrc: String?
    var addedAt: Date
    var artworkPath: String?
    var hasVector: Bool

    var url: URL { HarmonyPaths.url(forRelative: relativePath) }
    var artworkURL: URL? { artworkPath.map { HarmonyPaths.artwork.appendingPathComponent($0) } }

    /// "FLAC 24/96", "ALAC 16/44.1", "AAC", "MP3".
    var qualityLabel: String {
        guard lossless, sampleRate > 0 else { return codec }
        let khz = Double(sampleRate) / 1000
        let rate = khz.rounded() == khz ? String(Int(khz)) : String(format: "%.1f", khz)
        return bitDepth > 0 ? "\(codec) \(bitDepth)/\(rate)" : "\(codec) \(rate) kHz"
    }
}

/// What is known about a song before it gets an id.
struct TrackDraft {
    var relativePath: String
    var title: String
    var artist: String
    var album: String
    var albumArtist: String?
    var trackNumber: Int?
    var discNumber: Int?
    var durationMs: Int
    var sampleRate: Int
    var bitDepth: Int
    var channels: Int
    var codec: String
    var lossless: Bool
    var fileSize: Int64
    var source: String
    var isrc: String?
}

enum LibraryStoreError: LocalizedError {
    case sqlite(String)

    var errorDescription: String? {
        switch self {
        case .sqlite(let message): return "Library database: \(message)"
        }
    }
}

/// A folder from the Files app that Harmony reads in place (a security-scoped bookmark).
struct LibraryFolder: Identifiable, Hashable {
    let id: Int64
    var bookmark: Data
    var name: String
    var addedAt: Date
    var lastScan: Date?

    /// Songs in this folder are stored as "@<id>/<path inside the folder>".
    var pathPrefix: String { "@\(id)/" }
}

/// A playlist the listener made (or one imported from Spotify, Deezer or a list).
struct LibraryPlaylist: Identifiable, Hashable {
    let id: Int64
    var name: String
    var createdAt: Date
    var songCount: Int
    var durationMs: Int
    /// Up to four song ids, for the cover mosaic.
    var coverTrackIds: [Int64]
}

/// What Smart Shuffle knows about how a song is listened to.
struct PlayStats: Hashable {
    var completed: Int
    var skipped: Int
    /// Milliseconds since 1970, 0 = never.
    var lastPlayedMs: Int64
}

/// A learned "to after from" habit.
struct Transition: Hashable {
    let from: Int64
    let to: Int64
    let weight: Float
}

/// The library database: songs, their 24-D Smart Shuffle vectors, the
/// folders read in place, play statistics and the Markov habits.
///
/// SQLite through its C API, one connection used on one serial queue, so it
/// is safe from any thread. Vectors are stored as 96-byte BLOBs (24 float32).
final class HarmonyLibraryStore: @unchecked Sendable {
    static let shared = HarmonyLibraryStore()

    private let queue = DispatchQueue(label: "Harmony library database")
    private var db: OpaquePointer?
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(path: URL = HarmonyPaths.database) {
        queue.sync {
            if sqlite3_open_v2(path.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) != SQLITE_OK {
                db = nil
                return
            }
            exec("PRAGMA journal_mode=WAL")
            exec("PRAGMA foreign_keys=ON")
            exec("""
                CREATE TABLE IF NOT EXISTS tracks(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    relative_path TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    album_artist TEXT,
                    track_number INTEGER,
                    disc_number INTEGER,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    sample_rate INTEGER NOT NULL DEFAULT 0,
                    bit_depth INTEGER NOT NULL DEFAULT 0,
                    channels INTEGER NOT NULL DEFAULT 0,
                    codec TEXT NOT NULL DEFAULT '',
                    lossless INTEGER NOT NULL DEFAULT 0,
                    file_size INTEGER NOT NULL DEFAULT 0,
                    source TEXT NOT NULL DEFAULT 'Files',
                    isrc TEXT,
                    match_key TEXT NOT NULL,
                    added_at REAL NOT NULL,
                    artwork_path TEXT
                )
                """)
            exec("CREATE INDEX IF NOT EXISTS tracks_match ON tracks(match_key)")
            exec("CREATE INDEX IF NOT EXISTS tracks_isrc ON tracks(isrc)")
            exec("""
                CREATE TABLE IF NOT EXISTS acoustic_vectors(
                    track_id INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
                    vector BLOB NOT NULL,
                    quality INTEGER NOT NULL,
                    measured_at REAL NOT NULL
                )
                """)
            exec("""
                CREATE TABLE IF NOT EXISTS folders(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    bookmark BLOB NOT NULL,
                    name TEXT NOT NULL,
                    added_at REAL NOT NULL,
                    last_scan REAL
                )
                """)
            exec("""
                CREATE TABLE IF NOT EXISTS playlists(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    created_at REAL NOT NULL
                )
                """)
            exec("""
                CREATE TABLE IF NOT EXISTS playlist_items(
                    playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                    PRIMARY KEY(playlist_id, position)
                )
                """)
            exec("""
                CREATE TABLE IF NOT EXISTS play_stats(
                    track_id INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
                    completed INTEGER NOT NULL DEFAULT 0,
                    skipped INTEGER NOT NULL DEFAULT 0,
                    last_played_ms INTEGER NOT NULL DEFAULT 0
                )
                """)
            exec("""
                CREATE TABLE IF NOT EXISTS transitions(
                    from_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                    to_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                    weight REAL NOT NULL,
                    PRIMARY KEY(from_id, to_id)
                )
                """)
        }
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    // MARK: - Writes

    /// Adds the song, or updates the one already stored at the same path. Returns its id.
    @discardableResult
    func upsert(_ draft: TrackDraft) throws -> Int64 {
        try queue.sync {
            let sql = """
                INSERT INTO tracks(relative_path, title, artist, album, album_artist, track_number, disc_number,
                                   duration_ms, sample_rate, bit_depth, channels, codec, lossless, file_size,
                                   source, isrc, match_key, added_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(relative_path) DO UPDATE SET
                    title=excluded.title, artist=excluded.artist, album=excluded.album,
                    album_artist=excluded.album_artist, track_number=excluded.track_number,
                    disc_number=excluded.disc_number, duration_ms=excluded.duration_ms,
                    sample_rate=excluded.sample_rate, bit_depth=excluded.bit_depth, channels=excluded.channels,
                    codec=excluded.codec, lossless=excluded.lossless, file_size=excluded.file_size,
                    isrc=COALESCE(excluded.isrc, tracks.isrc), match_key=excluded.match_key
                """
            let statement = try prepare(sql)
            defer { sqlite3_finalize(statement) }
            bind(statement, 1, draft.relativePath)
            bind(statement, 2, draft.title)
            bind(statement, 3, draft.artist)
            bind(statement, 4, draft.album)
            bind(statement, 5, draft.albumArtist)
            bind(statement, 6, draft.trackNumber)
            bind(statement, 7, draft.discNumber)
            sqlite3_bind_int64(statement, 8, Int64(draft.durationMs))
            sqlite3_bind_int64(statement, 9, Int64(draft.sampleRate))
            sqlite3_bind_int64(statement, 10, Int64(draft.bitDepth))
            sqlite3_bind_int64(statement, 11, Int64(draft.channels))
            bind(statement, 12, draft.codec)
            sqlite3_bind_int(statement, 13, draft.lossless ? 1 : 0)
            sqlite3_bind_int64(statement, 14, draft.fileSize)
            bind(statement, 15, draft.source)
            bind(statement, 16, draft.isrc)
            bind(statement, 17, Self.matchKey(title: draft.title, artist: draft.artist))
            sqlite3_bind_double(statement, 18, Date().timeIntervalSince1970)
            guard sqlite3_step(statement) == SQLITE_DONE else { throw LibraryStoreError.sqlite(lastError) }
            let lookup = try prepare("SELECT id FROM tracks WHERE relative_path = ?")
            defer { sqlite3_finalize(lookup) }
            bind(lookup, 1, draft.relativePath)
            guard sqlite3_step(lookup) == SQLITE_ROW else { throw LibraryStoreError.sqlite("the song was not stored") }
            return sqlite3_column_int64(lookup, 0)
        }
    }

    func setArtwork(trackId: Int64, fileName: String?) {
        queue.sync {
            guard let statement = try? prepare("UPDATE tracks SET artwork_path = ? WHERE id = ?") else { return }
            defer { sqlite3_finalize(statement) }
            bind(statement, 1, fileName)
            sqlite3_bind_int64(statement, 2, trackId)
            sqlite3_step(statement)
        }
    }

    /// Stores a measured vector; quality 2 = real 24-D (VectorQuality.Real in the C++ engine).
    func saveVector(trackId: Int64, vector: [Float], quality: Int = 2) {
        guard vector.count == 24, vector.allSatisfy(\.isFinite) else { return }
        queue.sync {
            guard let statement = try? prepare(
                "INSERT OR REPLACE INTO acoustic_vectors(track_id, vector, quality, measured_at) VALUES(?,?,?,?)"
            ) else { return }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int64(statement, 1, trackId)
            vector.withUnsafeBytes { raw in
                _ = sqlite3_bind_blob(statement, 2, raw.baseAddress, Int32(raw.count), Self.transient)
            }
            sqlite3_bind_int(statement, 3, Int32(quality))
            sqlite3_bind_double(statement, 4, Date().timeIntervalSince1970)
            sqlite3_step(statement)
        }
    }

    func delete(trackId: Int64) {
        queue.sync {
            guard let statement = try? prepare("DELETE FROM tracks WHERE id = ?") else { return }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int64(statement, 1, trackId)
            sqlite3_step(statement)
        }
    }

    // MARK: - Reads

    func allTracks() -> [LibraryTrack] {
        query("\(Self.selectTracks) ORDER BY t.added_at DESC", bind: { _ in })
    }

    func track(id: Int64) -> LibraryTrack? {
        query("\(Self.selectTracks) WHERE t.id = ?", bind: { sqlite3_bind_int64($0, 1, id) }).first
    }

    func track(relativePath: String) -> LibraryTrack? {
        query("\(Self.selectTracks) WHERE t.relative_path = ?", bind: { self.bind($0, 1, relativePath) }).first
    }

    /// The song with this ISRC, or else this title and artist (case, accents and punctuation ignored).
    func findTrack(title: String, artist: String, isrc: String? = nil) -> LibraryTrack? {
        if let isrc, !isrc.isEmpty,
           let byIsrc = query("\(Self.selectTracks) WHERE t.isrc = ? LIMIT 1", bind: { self.bind($0, 1, isrc) }).first {
            return byIsrc
        }
        let key = Self.matchKey(title: title, artist: artist)
        return query("\(Self.selectTracks) WHERE t.match_key = ? LIMIT 1", bind: { self.bind($0, 1, key) }).first
    }

    func tracksWithoutVectors() -> [LibraryTrack] {
        query("\(Self.selectTracks) WHERE v.track_id IS NULL", bind: { _ in })
    }

    func vector(trackId: Int64) -> [Float]? {
        queue.sync {
            guard let statement = try? prepare("SELECT vector FROM acoustic_vectors WHERE track_id = ?") else { return nil }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int64(statement, 1, trackId)
            guard sqlite3_step(statement) == SQLITE_ROW, let blob = sqlite3_column_blob(statement, 0) else { return nil }
            let count = Int(sqlite3_column_bytes(statement, 0)) / MemoryLayout<Float>.size
            guard count == 24 else { return nil }
            return Array(UnsafeBufferPointer(start: blob.assumingMemoryBound(to: Float.self), count: count))
        }
    }

    var trackCount: Int {
        queue.sync {
            guard let statement = try? prepare("SELECT COUNT(*) FROM tracks") else { return 0 }
            defer { sqlite3_finalize(statement) }
            return sqlite3_step(statement) == SQLITE_ROW ? Int(sqlite3_column_int64(statement, 0)) : 0
        }
    }

    /// The key songs are matched on: "artist|title", folded and stripped to letters and digits.
    static func matchKey(title: String, artist: String) -> String {
        func fold(_ s: String) -> String {
            let folded = s.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            let kept = folded.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
            return String(kept).split(separator: " ").joined(separator: " ")
        }
        // "Song (Remastered 2011)" and "Song - 2011 Remaster" are the same song.
        let base = title.replacingOccurrences(of: #"\s*[\(\[][^\)\]]*(remaster|version|edit|mono|stereo)[^\)\]]*[\)\]]"#,
                                              with: "", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: #"\s+-\s+.*(remaster|version).*$"#, with: "", options: [.regularExpression, .caseInsensitive])
        let firstArtist = artist.components(separatedBy: CharacterSet(charactersIn: ",&;")).first ?? artist
        return fold(firstArtist) + "|" + fold(base)
    }

    /// The songs stored under [prefix] ("@3/" for a folder), by relative path, with their size.
    func pathIndex(prefix: String) -> [String: (id: Int64, size: Int64)] {
        queue.sync {
            var index: [String: (id: Int64, size: Int64)] = [:]
            guard let statement = try? prepare(
                "SELECT relative_path, id, file_size FROM tracks WHERE substr(relative_path, 1, ?) = ?"
            ) else { return index }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int(statement, 1, Int32(prefix.utf8.count))
            bind(statement, 2, prefix)
            while sqlite3_step(statement) == SQLITE_ROW {
                if let path = text(statement, 0) {
                    index[path] = (sqlite3_column_int64(statement, 1), sqlite3_column_int64(statement, 2))
                }
            }
            return index
        }
    }

    /// Removes songs (their vectors, stats and habits go with them).
    func delete(trackIds: [Int64]) {
        guard !trackIds.isEmpty else { return }
        queue.sync {
            exec("BEGIN")
            if let statement = try? prepare("DELETE FROM tracks WHERE id = ?") {
                for id in trackIds {
                    sqlite3_reset(statement)
                    sqlite3_bind_int64(statement, 1, id)
                    sqlite3_step(statement)
                }
                sqlite3_finalize(statement)
            }
            exec("COMMIT")
        }
    }

    // MARK: - Folders

    func folders() -> [LibraryFolder] {
        queue.sync {
            guard let statement = try? prepare("SELECT id, bookmark, name, added_at, last_scan FROM folders ORDER BY name") else {
                return []
            }
            defer { sqlite3_finalize(statement) }
            var folders: [LibraryFolder] = []
            while sqlite3_step(statement) == SQLITE_ROW {
                let bytes = sqlite3_column_blob(statement, 1)
                let count = Int(sqlite3_column_bytes(statement, 1))
                let bookmark = bytes.map { Data(bytes: $0, count: count) } ?? Data()
                let scanned = sqlite3_column_type(statement, 4) == SQLITE_NULL ? nil
                    : Date(timeIntervalSince1970: sqlite3_column_double(statement, 4))
                folders.append(LibraryFolder(
                    id: sqlite3_column_int64(statement, 0),
                    bookmark: bookmark,
                    name: text(statement, 2) ?? "",
                    addedAt: Date(timeIntervalSince1970: sqlite3_column_double(statement, 3)),
                    lastScan: scanned
                ))
            }
            return folders
        }
    }

    func addFolder(bookmark: Data, name: String) throws -> Int64 {
        try queue.sync {
            let statement = try prepare("INSERT INTO folders(bookmark, name, added_at) VALUES(?,?,?)")
            defer { sqlite3_finalize(statement) }
            bindBlob(statement, 1, bookmark)
            bind(statement, 2, name)
            sqlite3_bind_double(statement, 3, Date().timeIntervalSince1970)
            guard sqlite3_step(statement) == SQLITE_DONE else { throw LibraryStoreError.sqlite(lastError) }
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// A stale bookmark resolved anyway: iOS hands out a fresh one to keep.
    func updateFolder(id: Int64, bookmark: Data) {
        queue.sync {
            guard let statement = try? prepare("UPDATE folders SET bookmark = ? WHERE id = ?") else { return }
            defer { sqlite3_finalize(statement) }
            bindBlob(statement, 1, bookmark)
            sqlite3_bind_int64(statement, 2, id)
            sqlite3_step(statement)
        }
    }

    func markFolderScanned(id: Int64) {
        queue.sync {
            guard let statement = try? prepare("UPDATE folders SET last_scan = ? WHERE id = ?") else { return }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_double(statement, 1, Date().timeIntervalSince1970)
            sqlite3_bind_int64(statement, 2, id)
            sqlite3_step(statement)
        }
    }

    /// Forgets the folder and its songs (the files themselves are not touched).
    func removeFolder(_ folder: LibraryFolder) {
        let ids = pathIndex(prefix: folder.pathPrefix).values.map { $0.id }
        delete(trackIds: ids)
        queue.sync {
            guard let statement = try? prepare("DELETE FROM folders WHERE id = ?") else { return }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int64(statement, 1, folder.id)
            sqlite3_step(statement)
        }
    }

    // MARK: - Playlists

    func playlists() -> [LibraryPlaylist] {
        queue.sync {
            guard let statement = try? prepare("""
                SELECT p.id, p.name, p.created_at, COUNT(t.id), COALESCE(SUM(t.duration_ms), 0)
                FROM playlists p
                LEFT JOIN playlist_items i ON i.playlist_id = p.id
                LEFT JOIN tracks t ON t.id = i.track_id
                GROUP BY p.id ORDER BY p.name COLLATE NOCASE
                """) else { return [] }
            defer { sqlite3_finalize(statement) }
            var playlists: [LibraryPlaylist] = []
            while sqlite3_step(statement) == SQLITE_ROW {
                playlists.append(LibraryPlaylist(
                    id: sqlite3_column_int64(statement, 0),
                    name: text(statement, 1) ?? "",
                    createdAt: Date(timeIntervalSince1970: sqlite3_column_double(statement, 2)),
                    songCount: Int(sqlite3_column_int64(statement, 3)),
                    durationMs: Int(sqlite3_column_int64(statement, 4)),
                    coverTrackIds: []
                ))
            }
            guard let covers = try? prepare(
                "SELECT track_id FROM playlist_items WHERE playlist_id = ? ORDER BY position LIMIT 4"
            ) else { return playlists }
            defer { sqlite3_finalize(covers) }
            for index in playlists.indices {
                sqlite3_reset(covers)
                sqlite3_bind_int64(covers, 1, playlists[index].id)
                while sqlite3_step(covers) == SQLITE_ROW { playlists[index].coverTrackIds.append(sqlite3_column_int64(covers, 0)) }
            }
            return playlists
        }
    }

    @discardableResult
    func createPlaylist(name: String) throws -> Int64 {
        try queue.sync {
            let statement = try prepare("INSERT INTO playlists(name, created_at) VALUES(?, ?)")
            defer { sqlite3_finalize(statement) }
            bind(statement, 1, name)
            sqlite3_bind_double(statement, 2, Date().timeIntervalSince1970)
            guard sqlite3_step(statement) == SQLITE_DONE else { throw LibraryStoreError.sqlite(lastError) }
            return sqlite3_last_insert_rowid(db)
        }
    }

    func renamePlaylist(id: Int64, name: String) {
        queue.sync {
            guard let statement = try? prepare("UPDATE playlists SET name = ? WHERE id = ?") else { return }
            defer { sqlite3_finalize(statement) }
            bind(statement, 1, name)
            sqlite3_bind_int64(statement, 2, id)
            sqlite3_step(statement)
        }
    }

    func deletePlaylist(id: Int64) {
        queue.sync {
            guard let statement = try? prepare("DELETE FROM playlists WHERE id = ?") else { return }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int64(statement, 1, id)
            sqlite3_step(statement)
        }
    }

    /// The playlist's songs in order (songs since removed from the library are skipped).
    func tracks(inPlaylist id: Int64) -> [LibraryTrack] {
        query("\(Self.selectTracks) JOIN playlist_items i ON i.track_id = t.id WHERE i.playlist_id = ? ORDER BY i.position",
              bind: { sqlite3_bind_int64($0, 1, id) })
    }

    /// Replaces the playlist's songs with [trackIds], in that order.
    func setPlaylistTracks(id: Int64, trackIds: [Int64]) {
        queue.sync {
            exec("BEGIN")
            if let clear = try? prepare("DELETE FROM playlist_items WHERE playlist_id = ?") {
                sqlite3_bind_int64(clear, 1, id)
                sqlite3_step(clear)
                sqlite3_finalize(clear)
            }
            if let insert = try? prepare("INSERT INTO playlist_items(playlist_id, position, track_id) VALUES(?,?,?)") {
                for (position, trackId) in trackIds.enumerated() {
                    sqlite3_reset(insert)
                    sqlite3_bind_int64(insert, 1, id)
                    sqlite3_bind_int64(insert, 2, Int64(position))
                    sqlite3_bind_int64(insert, 3, trackId)
                    sqlite3_step(insert)
                }
                sqlite3_finalize(insert)
            }
            exec("COMMIT")
        }
    }

    /// Adds songs at the end of the playlist.
    func addToPlaylist(id: Int64, trackIds: [Int64]) {
        let current = tracks(inPlaylist: id).map(\.id)
        setPlaylistTracks(id: id, trackIds: current + trackIds)
    }

    /// The playlist called [name], made if needed, holding exactly [trackIds] (an import run again updates it).
    func upsertPlaylist(name: String, trackIds: [Int64]) {
        let existing = playlists().first { $0.name.caseInsensitiveCompare(name) == .orderedSame }
        guard let id = existing?.id ?? (try? createPlaylist(name: name)) else { return }
        setPlaylistTracks(id: id, trackIds: trackIds)
    }

    // MARK: - Listening history (Smart Shuffle)

    /// A song was finished ([completed]) or skipped early ([skipped]) at [atMs].
    func recordPlay(trackId: Int64, completed: Bool, skipped: Bool, atMs: Int64) {
        queue.sync {
            guard let statement = try? prepare("""
                INSERT INTO play_stats(track_id, completed, skipped, last_played_ms) VALUES(?,?,?,?)
                ON CONFLICT(track_id) DO UPDATE SET
                    completed = completed + excluded.completed,
                    skipped = skipped + excluded.skipped,
                    last_played_ms = MAX(last_played_ms, excluded.last_played_ms)
                """) else { return }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int64(statement, 1, trackId)
            sqlite3_bind_int(statement, 2, completed ? 1 : 0)
            sqlite3_bind_int(statement, 3, skipped ? 1 : 0)
            sqlite3_bind_int64(statement, 4, atMs)
            sqlite3_step(statement)
        }
    }

    func allPlayStats() -> [Int64: PlayStats] {
        queue.sync {
            var stats: [Int64: PlayStats] = [:]
            guard let statement = try? prepare("SELECT track_id, completed, skipped, last_played_ms FROM play_stats") else {
                return stats
            }
            defer { sqlite3_finalize(statement) }
            while sqlite3_step(statement) == SQLITE_ROW {
                stats[sqlite3_column_int64(statement, 0)] = PlayStats(
                    completed: Int(sqlite3_column_int64(statement, 1)),
                    skipped: Int(sqlite3_column_int64(statement, 2)),
                    lastPlayedMs: sqlite3_column_int64(statement, 3)
                )
            }
            return stats
        }
    }

    /// Adds [delta] to the "to after from" habit.
    func addTransition(from: Int64, to: Int64, delta: Float) {
        guard from != to, delta.isFinite else { return }
        queue.sync {
            guard let statement = try? prepare("""
                INSERT INTO transitions(from_id, to_id, weight) VALUES(?,?,?)
                ON CONFLICT(from_id, to_id) DO UPDATE SET weight = weight + excluded.weight
                """) else { return }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int64(statement, 1, from)
            sqlite3_bind_int64(statement, 2, to)
            sqlite3_bind_double(statement, 3, Double(delta))
            sqlite3_step(statement)
        }
    }

    func allTransitions() -> [Transition] {
        queue.sync {
            var transitions: [Transition] = []
            guard let statement = try? prepare("SELECT from_id, to_id, weight FROM transitions WHERE weight > 0") else {
                return transitions
            }
            defer { sqlite3_finalize(statement) }
            while sqlite3_step(statement) == SQLITE_ROW {
                transitions.append(Transition(
                    from: sqlite3_column_int64(statement, 0),
                    to: sqlite3_column_int64(statement, 1),
                    weight: Float(sqlite3_column_double(statement, 2))
                ))
            }
            return transitions
        }
    }

    /// Every stored vector with its quality (2 = real 24-D).
    func allVectors() -> [Int64: (vector: [Float], quality: Int)] {
        queue.sync {
            var vectors: [Int64: (vector: [Float], quality: Int)] = [:]
            guard let statement = try? prepare("SELECT track_id, vector, quality FROM acoustic_vectors") else { return vectors }
            defer { sqlite3_finalize(statement) }
            while sqlite3_step(statement) == SQLITE_ROW {
                guard let blob = sqlite3_column_blob(statement, 1) else { continue }
                let count = Int(sqlite3_column_bytes(statement, 1)) / MemoryLayout<Float>.size
                guard count == 24 else { continue }
                let vector = Array(UnsafeBufferPointer(start: blob.assumingMemoryBound(to: Float.self), count: count))
                vectors[sqlite3_column_int64(statement, 0)] = (vector, Int(sqlite3_column_int(statement, 2)))
            }
            return vectors
        }
    }

    // MARK: - Plumbing

    private static let selectTracks = """
        SELECT t.id, t.relative_path, t.title, t.artist, t.album, t.album_artist, t.track_number, t.disc_number,
               t.duration_ms, t.sample_rate, t.bit_depth, t.channels, t.codec, t.lossless, t.file_size, t.source,
               t.isrc, t.added_at, t.artwork_path, v.track_id IS NOT NULL
        FROM tracks t LEFT JOIN acoustic_vectors v ON v.track_id = t.id
        """

    private func query(_ sql: String, bind binder: (OpaquePointer) -> Void) -> [LibraryTrack] {
        queue.sync {
            guard let statement = try? prepare(sql) else { return [] }
            defer { sqlite3_finalize(statement) }
            binder(statement)
            var rows: [LibraryTrack] = []
            while sqlite3_step(statement) == SQLITE_ROW {
                rows.append(LibraryTrack(
                    id: sqlite3_column_int64(statement, 0),
                    relativePath: text(statement, 1) ?? "",
                    title: text(statement, 2) ?? "",
                    artist: text(statement, 3) ?? "",
                    album: text(statement, 4) ?? "",
                    albumArtist: text(statement, 5),
                    trackNumber: int(statement, 6),
                    discNumber: int(statement, 7),
                    durationMs: Int(sqlite3_column_int64(statement, 8)),
                    sampleRate: Int(sqlite3_column_int64(statement, 9)),
                    bitDepth: Int(sqlite3_column_int64(statement, 10)),
                    channels: Int(sqlite3_column_int64(statement, 11)),
                    codec: text(statement, 12) ?? "",
                    lossless: sqlite3_column_int(statement, 13) != 0,
                    fileSize: sqlite3_column_int64(statement, 14),
                    source: text(statement, 15) ?? "",
                    isrc: text(statement, 16),
                    addedAt: Date(timeIntervalSince1970: sqlite3_column_double(statement, 17)),
                    artworkPath: text(statement, 18),
                    hasVector: sqlite3_column_int(statement, 19) != 0
                ))
            }
            return rows
        }
    }

    private func exec(_ sql: String) {
        sqlite3_exec(db, sql, nil, nil, nil)
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard let db, sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw LibraryStoreError.sqlite(lastError)
        }
        return statement
    }

    private var lastError: String {
        guard let db, let message = sqlite3_errmsg(db) else { return "the database is not open" }
        return String(cString: message)
    }

    private func bind(_ statement: OpaquePointer, _ index: Int32, _ value: String?) {
        if let value {
            sqlite3_bind_text(statement, index, value, -1, Self.transient)
        } else {
            sqlite3_bind_null(statement, index)
        }
    }

    private func bindBlob(_ statement: OpaquePointer, _ index: Int32, _ data: Data) {
        data.withUnsafeBytes { raw in
            _ = sqlite3_bind_blob(statement, index, raw.baseAddress, Int32(raw.count), Self.transient)
        }
    }

    private func bind(_ statement: OpaquePointer, _ index: Int32, _ value: Int?) {
        if let value {
            sqlite3_bind_int64(statement, index, Int64(value))
        } else {
            sqlite3_bind_null(statement, index)
        }
    }

    private func text(_ statement: OpaquePointer, _ column: Int32) -> String? {
        guard let c = sqlite3_column_text(statement, column) else { return nil }
        return String(cString: c)
    }

    private func int(_ statement: OpaquePointer, _ column: Int32) -> Int? {
        sqlite3_column_type(statement, column) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(statement, column))
    }
}
