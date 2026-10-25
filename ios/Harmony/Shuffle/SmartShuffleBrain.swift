import Foundation

/// Why Smart Shuffle picked a song, for the queue and the brain HUD.
struct ShuffleDecision: Hashable, Sendable {
    let trackId: Int64
    /// "94% Bass/Vibe Match", "Markov Favorite Transition"…
    let reasonTag: String
    let vibePct: Float
    let markovPct: Float
    let affinityPct: Float
    let explorationPct: Float
    /// Cosine with the session vibe, -1..1.
    let cosine: Float
    let smoothness: Float
    let zone: String
    let realVector: Bool

    /// The cosine as "Vibe match 94%".
    var vibeMatchPct: Int { Int((max(0, cosine) * 100).rounded()) }
}

/// One self-audit of the last queue.
struct ShuffleAuditLine: Hashable, Sendable {
    let name: String
    /// 0 passed, 1 repaired, 2 failed.
    let status: Int
    let detail: String
}

struct ShuffleAuditSummary: Hashable, Sendable {
    let generation: UInt64
    let lines: [ShuffleAuditLine]
    let healthy: Bool
    let afterFastSkip: Bool
    let realVectorPct: Float
    let elapsedMs: Double
}

/// Everything the engine needs to know about the library, read from the database.
struct ShuffleLibrarySnapshot: Sendable {
    var ids: [Int64] = []
    /// ids.count x 24, NaN where unknown.
    var vectors: [Float] = []
    var qualities: [UInt8] = []
    var artists: [Int32] = []
    var albums: [Int32] = []
    var completed: [Int32] = []
    var skipped: [Int32] = []
    var lastPlayedMs: [Int64] = []
    var transitions: [Transition] = []

    /// Reads it from [store]; call off the main thread.
    static func read(from store: HarmonyLibraryStore) -> ShuffleLibrarySnapshot {
        let tracks = store.allTracks()
        let vectors = store.allVectors()
        let stats = store.allPlayStats()
        var snapshot = ShuffleLibrarySnapshot()
        var artistIds: [String: Int32] = [:]
        var albumIds: [String: Int32] = [:]
        func intern(_ key: String, in table: inout [String: Int32]) -> Int32 {
            let folded = key.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
            guard !folded.isEmpty, folded != "unknown artist" else { return -1 }
            if let id = table[folded] { return id }
            let id = Int32(table.count)
            table[folded] = id
            return id
        }
        let unknown = [Float](repeating: .nan, count: 24)
        for track in tracks {
            snapshot.ids.append(track.id)
            if let stored = vectors[track.id] {
                snapshot.vectors += stored.vector
                snapshot.qualities.append(UInt8(clamping: stored.quality))
            } else {
                snapshot.vectors += unknown
                snapshot.qualities.append(0)
            }
            snapshot.artists.append(intern(track.artist, in: &artistIds))
            snapshot.albums.append(track.album.isEmpty ? -1 : intern(track.artist + "|" + track.album, in: &albumIds))
            let stat = stats[track.id]
            snapshot.completed.append(Int32(clamping: stat?.completed ?? 0))
            snapshot.skipped.append(Int32(clamping: stat?.skipped ?? 0))
            snapshot.lastPlayedMs.append(stat?.lastPlayedMs ?? 0)
        }
        snapshot.transitions = store.allTransitions()
        return snapshot
    }
}

/**
 * Smart Shuffle on iOS: the shared C++ engine (through HarmonySmartShuffle)
 * on its own actor, so loading a large library or building a queue never
 * runs on the main thread.
 */
actor SmartShuffleBrain {
    private let engine = HarmonySmartShuffle()
    private var random = SystemRandomNumberGenerator()

    var librarySize: Int { engine.librarySize }

    /// Loads (or reloads) the library. The session and the learned habits stay.
    @discardableResult
    func load(_ snapshot: ShuffleLibrarySnapshot) -> Int {
        let count = snapshot.ids.count
        let loaded = engine.setLibrary(
            ids: Self.data(snapshot.ids), vectors: Self.data(snapshot.vectors),
            qualities: Self.data(snapshot.qualities), artists: Self.data(snapshot.artists),
            albums: Self.data(snapshot.albums), count: count
        )
        engine.setTrackStats(completed: Self.data(snapshot.completed), skipped: Self.data(snapshot.skipped),
                                          lastPlayed: Self.data(snapshot.lastPlayedMs), count: count)
        if !snapshot.transitions.isEmpty {
            engine.addTransitions(from: Self.data(snapshot.transitions.map(\.from)),
                                  to: Self.data(snapshot.transitions.map(\.to)),
                                  weights: Self.data(snapshot.transitions.map(\.weight)),
                                  count: snapshot.transitions.count)
        }
        return loaded
    }

    func trackStarted(_ id: Int64) -> HarmonyShuffleEvent {
        engine.trackStarted(id)
    }

    /// A song was left after [fraction]. Returns the event and the habit it taught, to persist.
    func outcome(_ id: Int64, fraction: Float, shufflePick: Bool) -> (HarmonyShuffleEvent, Transition?) {
        let event = engine.playbackOutcome(id, fraction: fraction, atMillis: Self.nowMs, shufflePick: shufflePick)
        var from: Int64 = -1
        var to: Int64 = -1
        var delta: Float = 0
        let learned = engine.lastLearnedTransition(from: &from, to: &to, delta: &delta) && to == id
        return (event, learned ? Transition(from: from, to: to, weight: delta) : nil)
    }

    func manualSelect(_ id: Int64) -> HarmonyShuffleEvent {
        engine.manualSelect(id)
    }

    func resetSession() {
        engine.resetSession()
    }

    var sessionVector: [Float] {
        engine.sessionVector.map(\.floatValue)
    }

    var hasSession: Bool { engine.hasSession }

    /// Up to [count] songs after [current] and [prefix], never any of [exclude], with why each was picked.
    func generate(after current: Int64, prefix: [Int64], exclude: Set<Int64>, count: Int)
        -> (ids: [Int64], decisions: [ShuffleDecision], audit: ShuffleAuditSummary) {
        let ids = engine.generate(
            after: current,
            prefix: prefix.map { NSNumber(value: $0) },
            exclude: exclude.map { NSNumber(value: $0) },
            count: count,
            atMillis: Self.nowMs,
            seed: random.next()
        ).map(\.int64Value)
        let decisions = engine.lastTraces.map { trace in
            ShuffleDecision(
                trackId: trace.trackId, reasonTag: trace.reasonTag, vibePct: trace.vibePct,
                markovPct: trace.markovPct, affinityPct: trace.affinityPct, explorationPct: trace.explorationPct,
                cosine: trace.cosine, smoothness: trace.smoothness, zone: trace.zoneName, realVector: trace.vectorQuality == 2
            )
        }
        let report = engine.lastAudit
        let audit = ShuffleAuditSummary(
            generation: report.generation,
            lines: report.checks.map { ShuffleAuditLine(name: $0.name, status: $0.status, detail: $0.detail) },
            healthy: report.healthy,
            afterFastSkip: report.afterFastSkip,
            realVectorPct: report.realVectorPct,
            elapsedMs: report.elapsedMs
        )
        return (ids, decisions, audit)
    }

    private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private static func data<T>(_ values: [T]) -> Data {
        values.withUnsafeBufferPointer { Data(buffer: $0) }
    }
}
