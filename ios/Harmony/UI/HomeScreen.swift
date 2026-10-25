import SwiftUI

/**
 * Home: the date and a greeting, the song playing, Smart Shuffle, the
 * library at a glance, what was added lately, and the tools (equalizer,
 * engine diagnostics).
 */
struct HomeScreen: View {
    @ObservedObject private var library = LibraryModel.shared
    @ObservedObject private var player = HarmonyAudioController.shared
    @ObservedObject private var router = HarmonyRouter.shared
    @ObservedObject private var indexer = LibraryIngest.shared.scanner

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    header
                    if let track = player.currentTrack { nowPlaying(track) }
                    smartMixCard
                    tiles
                    if !recent.isEmpty { recentlyAdded }
                    tools
                    Text("HARMONY • OFFLINE • LOSSLESS • YOURS")
                        .font(.system(size: 10, weight: .heavy, design: .monospaced))
                        .tracking(2)
                        .foregroundStyle(CyberGlassColors.textQuiet)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 8)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            .harmonyChromeInset()
            .background(CyberGlassColors.page.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var recent: [LibraryTrack] {
        Array(library.tracks.sorted { $0.addedAt > $1.addedAt }.prefix(12))
    }

    // MARK: - Sections

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(Date().formatted(.dateTime.weekday(.wide).day().month(.wide)).uppercased())
                .font(.system(size: 11, weight: .heavy, design: .monospaced))
                .tracking(1.6)
                .foregroundStyle(CyberGlassColors.cyan.opacity(0.85))
            Text(greeting)
                .font(.system(size: 34, weight: .heavy))
                .foregroundStyle(CyberGlassColors.textPrimary)
            Text(library.tracks.isEmpty ? "Add music from Files or Downloads to begin." :
                    "\(library.tracks.count.formatted()) songs ready when you are")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(CyberGlassColors.textSecondary)
        }
        .padding(.top, 12)
        .background(alignment: .topLeading) {
            Circle()
                .fill(RadialGradient(colors: [CyberGlassColors.violet.opacity(0.35), .clear], center: .center, startRadius: 0, endRadius: 160))
                .frame(width: 320, height: 320)
                .offset(x: -120, y: -150)
                .allowsHitTesting(false)
        }
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5..<12: return "Good morning"
        case 12..<18: return "Good afternoon"
        case 18..<23: return "Good evening"
        default: return "Late night session"
        }
    }

    private func nowPlaying(_ track: LibraryTrack) -> some View {
        Button { router.showNowPlaying = true } label: {
            HStack(spacing: 14) {
                Artwork(url: track.artworkURL, size: 86)
                    .shadow(color: CyberGlassColors.cyan.opacity(0.35), radius: 14)
                VStack(alignment: .leading, spacing: 5) {
                    Text(player.isPlaying ? "NOW PLAYING" : "PAUSED")
                        .font(.system(size: 10, weight: .heavy))
                        .tracking(1.2)
                        .foregroundStyle(CyberGlassColors.mint)
                    Text(track.title)
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundStyle(CyberGlassColors.textPrimary)
                        .lineLimit(1)
                    Text(track.album.isEmpty ? track.artist : "\(track.artist) • \(track.album)")
                        .font(.system(size: 13))
                        .foregroundStyle(CyberGlassColors.textSecondary)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        InfoChip(text: track.qualityLabel, accent: track.lossless ? CyberGlassColors.mint : CyberGlassColors.sky)
                        if player.queue.count > 1 {
                            InfoChip(text: "\(player.currentIndex + 1) of \(player.queue.count)", accent: CyberGlassColors.textSecondary)
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .background(
                LinearGradient(colors: [CyberGlassColors.indigoDeep.opacity(0.65), CyberGlassColors.card],
                               startPoint: .topLeading, endPoint: .bottomTrailing),
                in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(CyberGlassColors.iridescentSoft, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens Now Playing")
    }

    private var smartMixCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "sparkles")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(CyberGlassColors.obsidian)
                    .frame(width: 44, height: 44)
                    .background(CyberGlassColors.selectedChip, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Smart Shuffle")
                        .font(.system(size: 19, weight: .heavy))
                        .foregroundStyle(CyberGlassColors.textPrimary)
                    Text(smartMixSubtitle)
                        .font(.system(size: 12.5))
                        .foregroundStyle(CyberGlassColors.textSecondary)
                }
                Spacer()
            }
            if indexer.progress.isActive {
                NeonProgressBar(fraction: indexer.progress.fraction, height: 3)
            }
            NeonButton(title: "Start Smart Mix", systemImage: "play.fill", disabled: library.tracks.isEmpty) {
                player.startSmartMix()
                router.showNowPlaying = true
            }
        }
        .padding(16)
        .background(CyberGlassColors.card.opacity(CyberGlassColors.cardAlpha), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(CyberGlassColors.iridescent, lineWidth: 1.2))
        .shadow(color: CyberGlassColors.cyan.opacity(0.18), radius: 18)
    }

    private var smartMixSubtitle: String {
        let total = library.tracks.count
        guard total > 0 else { return "Picks by sound from your own library" }
        let pct = Int((Double(library.indexedCount) / Double(total) * 100).rounded())
        return "Picks by sound from \(total.formatted()) songs • \(pct)% measured"
    }

    private var tiles: some View {
        let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            StatTile(value: library.tracks.count, label: "Songs", icon: "music.note", tint: CyberGlassColors.cyan) { router.tab = .library }
            StatTile(value: library.albums.count, label: "Albums", icon: "square.stack", tint: CyberGlassColors.violet) { router.tab = .library }
            StatTile(value: library.artists.count, label: "Artists", icon: "person.2", tint: CyberGlassColors.mint) { router.tab = .library }
            StatTile(value: library.playlists.count, label: "Playlists", icon: "rectangle.stack", tint: CyberGlassColors.purple) { router.tab = .playlists }
        }
    }

    private var recentlyAdded: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeading(title: "Recently added", trailing: nil)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    let songs = recent
                    ForEach(songs.indices, id: \.self) { index in
                        CoverTile(track: songs[index]) { player.play(tracks: songs, startAt: index) }
                    }
                }
            }
        }
    }

    private var tools: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeading(title: "Tools", trailing: nil)
            ToolRow(icon: "slider.vertical.3", title: "Equalizer", subtitle: equalizerSubtitle, tint: CyberGlassColors.cyan) {
                router.showEqualizer = true
            }
            NavigationLink {
                EngineCheckView()
            } label: {
                ToolRowLabel(icon: "cpu", title: "Engine diagnostics",
                             subtitle: "Run the C++ equalizer and Smart Shuffle checks on this iPhone", tint: CyberGlassColors.violet)
            }
            .buttonStyle(.plain)
            ToolRow(icon: "folder.badge.plus", title: "Add music", subtitle: "A folder from Files, or downloads", tint: CyberGlassColors.mint) {
                router.tab = .downloads
            }
        }
        .sheet(isPresented: $router.showEqualizer) {
            WinampLiveEqualizerView()
        }
    }

    private var equalizerSubtitle: String {
        let eq = player.equalizer
        guard eq.enabled else { return "Off" }
        return eq.auto ? "On • AUTO LIVE • \(player.route.badge)" : "On • \(player.route.badge)"
    }
}

// MARK: - Pieces

/// A count with its label, as a tappable glass tile.
struct StatTile: View {
    let value: Int
    let label: String
    let icon: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(tint)
                    .shadow(color: tint.opacity(0.6), radius: 6)
                Text(value.formatted())
                    .font(.system(size: 24, weight: .heavy, design: .rounded))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                Text(label.uppercased())
                    .font(.system(size: 10.5, weight: .heavy))
                    .tracking(1)
                    .foregroundStyle(CyberGlassColors.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(tint.opacity(0.08), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(tint.opacity(0.3), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// A cover with the title under it (rows of songs).
struct CoverTile: View {
    let track: LibraryTrack
    var size: CGFloat = 132
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Artwork(url: track.artworkURL, size: size)
                    .overlay(alignment: .topTrailing) {
                        if track.bitDepth >= 24 || track.sampleRate > 48_000 {
                            Text("HI-RES")
                                .font(.system(size: 8.5, weight: .heavy, design: .monospaced))
                                .foregroundStyle(CyberGlassColors.obsidian)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(CyberGlassColors.selectedChip, in: Capsule())
                                .padding(6)
                        }
                    }
                Text(track.title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                    .lineLimit(1)
                Text(track.artist)
                    .font(.system(size: 11.5))
                    .foregroundStyle(CyberGlassColors.textSecondary)
                    .lineLimit(1)
            }
            .frame(width: size)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// A row in the Tools list.
struct ToolRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ToolRowLabel(icon: icon, title: title, subtitle: subtitle, tint: tint)
        }
        .buttonStyle(.plain)
    }
}

struct ToolRowLabel: View {
    let icon: String
    let title: String
    let subtitle: String
    let tint: Color

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(CyberGlassColors.textPrimary)
                Text(subtitle).font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary).lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 12, weight: .bold)).foregroundStyle(CyberGlassColors.textQuiet)
        }
        .padding(12)
        .background(CyberGlassColors.surface.opacity(0.9), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(Rectangle())
    }
}
