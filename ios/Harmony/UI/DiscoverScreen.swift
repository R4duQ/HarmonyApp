import SwiftUI

/**
 * Songs that sound alike, from the 24-D vectors: each dimension is
 * standardized over the library (as the C++ engine does), then compared by
 * cosine. Read-only: it never touches the Smart Shuffle session.
 */
@MainActor
final class SoundNeighbours: ObservableObject {
    static let shared = SoundNeighbours()

    @Published private(set) var neighbours: [LibraryTrack] = []
    @Published private(set) var seed: LibraryTrack?

    private var computing: Int64?

    /// The songs that sound most like [track].
    func refresh(for track: LibraryTrack?) {
        guard let track, track.id != seed?.id, track.id != computing else { return }
        computing = track.id
        let tracks = LibraryModel.shared.tracks
        Task {
            let ids = await Task.detached(priority: .utility) {
                Self.nearest(to: track.id, among: HarmonyLibraryStore.shared.allVectors(), count: 12)
            }.value
            let byId = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            neighbours = ids.compactMap { byId[$0] }
            seed = track
            computing = nil
        }
    }

    private nonisolated static func nearest(to id: Int64, among stored: [Int64: (vector: [Float], quality: Int)], count: Int) -> [Int64] {
        let entries = stored.filter { $0.value.quality >= 1 }
        guard entries.count > 2, entries[id] != nil else { return [] }
        let dims = 24
        var mean = [Float](repeating: 0, count: dims)
        var sq = [Float](repeating: 0, count: dims)
        for (_, entry) in entries {
            for d in 0..<dims {
                mean[d] += entry.vector[d]
                sq[d] += entry.vector[d] * entry.vector[d]
            }
        }
        let n = Float(entries.count)
        let std = (0..<dims).map { d -> Float in
            let m = mean[d] / n
            return max(1e-4, (sq[d] / n - m * m).squareRoot())
        }
        for d in 0..<dims { mean[d] /= n }
        func unit(_ v: [Float]) -> [Float] {
            let z = (0..<dims).map { (v[$0] - mean[$0]) / std[$0] }
            let norm = max(1e-6, z.reduce(0) { $0 + $1 * $1 }.squareRoot())
            return z.map { $0 / norm }
        }
        guard let seedVector = entries[id]?.vector else { return [] }
        let seed = unit(seedVector)
        var scored: [(id: Int64, score: Float)] = []
        for (key, entry) in entries where key != id {
            let v = unit(entry.vector)
            var dot: Float = 0
            for d in 0..<dims { dot += seed[d] * v[d] }
            scored.append((key, dot))
        }
        return scored.sorted { $0.score > $1.score }.prefix(count).map { $0.id }
    }
}

/**
 * Discover, offline: what sounds like the song playing, your heavy
 * rotation, forgotten favourites, songs you have never played and the
 * hi-res corner, all from your own library and listening.
 */
struct DiscoverScreen: View {
    @ObservedObject private var library = LibraryModel.shared
    @ObservedObject private var player = HarmonyAudioController.shared
    @ObservedObject private var neighbours = SoundNeighbours.shared

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Discover")
                        .font(.system(size: 32, weight: .heavy))
                        .foregroundStyle(CyberGlassColors.textPrimary)
                    Text("FROM YOUR OWN LIBRARY • OFFLINE")
                        .font(.system(size: 10.5, weight: .semibold))
                        .tracking(1.1)
                        .foregroundStyle(CyberGlassColors.cyan.opacity(0.8))
                }
                .padding(.top, 12)

                if library.tracks.isEmpty {
                    CyberCard {
                        Text("Discover fills up as your library grows and as you listen.")
                            .font(.system(size: 14))
                            .foregroundStyle(CyberGlassColors.textSecondary)
                    }
                }
                if let seed = neighbours.seed, !neighbours.neighbours.isEmpty {
                    shelf(title: "Sounds like \(seed.title)", subtitle: "Closest in the 24-D sound space", tracks: neighbours.neighbours)
                }
                shelf(title: "Heavy rotation", subtitle: "What you play to the end most", tracks: heavyRotation)
                shelf(title: "Forgotten favourites", subtitle: "Loved once, unheard for a month", tracks: forgotten)
                shelf(title: "Never played", subtitle: "Already yours, still waiting", tracks: neverPlayed)
                shelf(title: "Hi-Res corner", subtitle: "24-bit and above 48 kHz", tracks: hiRes)
            }
            .padding(.horizontal, 16)
        }
        .harmonyChromeInset()
        .background(CyberGlassColors.page.ignoresSafeArea())
        .onAppear { neighbours.refresh(for: player.currentTrack) }
        .onChange(of: player.currentTrack?.id) { _ in neighbours.refresh(for: player.currentTrack) }
    }

    private var heavyRotation: [LibraryTrack] {
        library.tracks
            .filter { (library.stats[$0.id]?.completed ?? 0) > 0 }
            .sorted { (library.stats[$0.id]?.completed ?? 0) > (library.stats[$1.id]?.completed ?? 0) }
            .prefix(15).map { $0 }
    }

    private var forgotten: [LibraryTrack] {
        let monthAgo = Int64(Date().addingTimeInterval(-30 * 86_400).timeIntervalSince1970 * 1000)
        return library.tracks
            .filter { track in
                guard let stat = library.stats[track.id] else { return false }
                return stat.completed >= 2 && stat.lastPlayedMs > 0 && stat.lastPlayedMs < monthAgo
            }
            .sorted { (library.stats[$0.id]?.completed ?? 0) > (library.stats[$1.id]?.completed ?? 0) }
            .prefix(15).map { $0 }
    }

    private var neverPlayed: [LibraryTrack] {
        let unplayed = library.tracks.filter { library.stats[$0.id] == nil }
        // Stable for the day, so the shelf doesn't reshuffle on every redraw.
        var generator = SeededGenerator(seed: UInt64(Calendar.current.ordinality(of: .day, in: .era, for: Date()) ?? 1))
        return Array(unplayed.shuffled(using: &generator).prefix(15))
    }

    private var hiRes: [LibraryTrack] {
        library.tracks.filter { $0.bitDepth >= 24 || $0.sampleRate > 48_000 }.prefix(15).map { $0 }
    }

    @ViewBuilder
    private func shelf(title: String, subtitle: String, tracks: [LibraryTrack]) -> some View {
        if !tracks.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.system(size: 18, weight: .heavy)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(1)
                        Text(subtitle).font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary)
                    }
                    Spacer()
                    Button { player.play(tracks: tracks, startAt: 0) } label: {
                        Image(systemName: "play.circle.fill")
                            .font(.system(size: 28))
                            .foregroundStyle(CyberGlassColors.cyan)
                            .shadow(color: CyberGlassColors.cyan.opacity(0.5), radius: 8)
                    }
                    .accessibilityLabel("Play \(title)")
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(tracks.indices, id: \.self) { index in
                            CoverTile(track: tracks[index], size: 120) { player.play(tracks: tracks, startAt: index) }
                        }
                    }
                }
            }
        }
    }
}

/// A small deterministic generator (SplitMix64).
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
