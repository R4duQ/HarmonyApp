import Combine
import SwiftUI
import UIKit

/**
 * The Library tab: every song (Files › On My iPhone › Harmony, the folders
 * picked in Files, downloads), search, folders, and a player bar.
 *
 * A first, plain version so the library and the player can be used now; the
 * full Cyber-Glass shell (floating mini player, Now Playing, the Winamp
 * equalizer) comes with the UI module.
 */
struct LibraryScreen: View {
    @ObservedObject private var library = IosMusicLibraryManager.shared
    @ObservedObject private var player = HarmonyAudioController.shared
    @ObservedObject private var ingest = LibraryIngest.shared
    @ObservedObject private var indexer = LibraryIngest.shared.scanner

    @State private var tracks: [LibraryTrack] = []
    @State private var search = ""
    @State private var showingPicker = false
    @State private var showingFolders = false

    private var filtered: [LibraryTrack] {
        let query = search.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return tracks }
        return tracks.filter {
            $0.title.localizedCaseInsensitiveContains(query) ||
                $0.artist.localizedCaseInsensitiveContains(query) ||
                $0.album.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    header
                    GlassField(placeholder: "Search songs, artists, albums", text: $search)
                    banners
                    if tracks.isEmpty {
                        emptyState
                    } else {
                        let shown = filtered
                        ForEach(shown.indices, id: \.self) { index in
                            TrackRow(track: shown[index], playing: player.currentTrack?.id == shown[index].id)
                                .contentShape(Rectangle())
                                .onTapGesture { player.play(tracks: shown, startAt: index) }
                                .contextMenu {
                                    Button { player.playNext(shown[index]) } label: { Label("Play next", systemImage: "text.insert") }
                                    Button { player.addToQueue(shown[index]) } label: { Label("Add to queue", systemImage: "text.append") }
                                    Button { player.startSmartMix(from: shown[index]) } label: {
                                        Label("Smart Mix from here", systemImage: "sparkles")
                                    }
                                }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(CyberGlassColors.page.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                if player.currentEntry != nil { PlayerBar().padding(.horizontal, 10).padding(.bottom, 6) }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .sheet(isPresented: $showingPicker) {
            FolderPicker { url in library.addFolder(url) }
        }
        .sheet(isPresented: $showingFolders) {
            FoldersSheet(showingPicker: $showingPicker)
        }
        .onAppear(perform: reload)
        .onReceive(NotificationCenter.default.publisher(for: .harmonyLibraryChanged)
            .debounce(for: .milliseconds(400), scheduler: RunLoop.main)) { _ in reload() }
        .alert("Library", isPresented: Binding(get: { library.lastError != nil }, set: { if !$0 { library.lastError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(library.lastError ?? "")
        }
    }

    private func reload() {
        tracks = HarmonyLibraryStore.shared.allTracks()
            .sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Library")
                    .font(.system(size: 30, weight: .heavy))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                Text("\(tracks.count) songs")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(CyberGlassColors.textSecondary)
            }
            Spacer()
            Button { player.startSmartMix() } label: {
                Image(systemName: "sparkles").font(.system(size: 18, weight: .bold))
            }
            .accessibilityLabel("Smart Mix")
            .disabled(tracks.isEmpty)
            Button { showingFolders = true } label: {
                Image(systemName: "folder.badge.gearshape").font(.system(size: 18, weight: .bold))
            }
            .accessibilityLabel("Folders")
            .padding(.leading, 14)
        }
        .foregroundStyle(CyberGlassColors.cyan)
        .padding(.top, 12)
    }

    @ViewBuilder
    private var banners: some View {
        if let scan = library.scan {
            InfoBanner(icon: "magnifyingglass", text: "Reading \(scan.location) • \(scan.seen) checked, \(scan.added) new")
        } else if let summary = library.lastScanSummary {
            InfoBanner(icon: "checkmark.circle", text: summary)
        }
        if indexer.progress.isActive {
            let p = indexer.progress
            InfoBanner(icon: "waveform.path.ecg",
                       text: p.pauseReason?.rawValue ?? "24D Smart Shuffle indexing • \(p.done + p.failed) of \(p.total)",
                       fraction: p.fraction)
        }
    }

    private var emptyState: some View {
        CyberCard {
            Text("No music yet")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(CyberGlassColors.textPrimary)
            Text("Copy music into Files › On My iPhone › Harmony, download it in the Downloads tab, or add a folder from the Files app. Harmony reads a folder where it is, without copying it.")
                .font(.system(size: 14))
                .foregroundStyle(CyberGlassColors.textSecondary)
            NeonButton(title: "Add a folder", systemImage: "folder.badge.plus") { showingPicker = true }
        }
        .padding(.top, 20)
    }
}

/// A one-line notice above the list, with an optional progress line.
private struct InfoBanner: View {
    let icon: String
    let text: String
    var fraction: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: icon).foregroundStyle(CyberGlassColors.cyan)
                Text(text)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(CyberGlassColors.textSilver)
                    .lineLimit(2)
            }
            if let fraction { NeonProgressBar(fraction: fraction, height: 3) }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(CyberGlassColors.surface.opacity(0.9), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// One song: artwork, title, artist, quality, and whether Smart Shuffle has measured it.
private struct TrackRow: View {
    let track: LibraryTrack
    let playing: Bool

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: track.artworkURL, size: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text(track.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(playing ? CyberGlassColors.cyan : CyberGlassColors.textPrimary)
                    .lineLimit(1)
                Text(track.album.isEmpty ? track.artist : "\(track.artist) • \(track.album)")
                    .font(.system(size: 12))
                    .foregroundStyle(CyberGlassColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 4) {
                InfoChip(text: track.qualityLabel, accent: track.lossless ? CyberGlassColors.mint : CyberGlassColors.sky)
                if track.hasVector {
                    Text("24D")
                        .font(.system(size: 9, weight: .heavy, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.violet)
                }
            }
        }
        .padding(.vertical, 6)
    }
}

/// A song's cover from the artwork cache, or a waveform glyph.
struct Artwork: View {
    let url: URL?
    let size: CGFloat

    var body: some View {
        Group {
            if let url, let image = UIImage(contentsOfFile: url.path) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    CyberGlassColors.artWell
                    Image(systemName: "waveform")
                        .font(.system(size: size * 0.4, weight: .bold))
                        .foregroundStyle(CyberGlassColors.iridescent)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
    }
}

/// The song playing, its output, a progress line and the transport.
struct PlayerBar: View {
    @ObservedObject private var player = HarmonyAudioController.shared

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Artwork(url: player.currentTrack?.artworkURL, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.currentTrack?.title ?? "")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(CyberGlassColors.textPrimary)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.cyan)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(CyberGlassColors.obsidian)
                        .frame(width: 42, height: 42)
                        .background(CyberGlassColors.selectedChip, in: Circle())
                }
                .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
            }
            NeonProgressBar(fraction: player.duration > 0 ? player.position / player.duration : 0, height: 3)
            HStack {
                Menu {
                    Picker("Shuffle", selection: Binding(get: { player.shuffleMode }, set: { player.setShuffleMode($0) })) {
                        Label("Off", systemImage: "arrow.right").tag(ShuffleMode.off)
                        Label("Random", systemImage: "shuffle").tag(ShuffleMode.random)
                        Label("Smart Shuffle", systemImage: "sparkles").tag(ShuffleMode.smart)
                    }
                } label: {
                    Image(systemName: player.shuffleMode == .smart ? "sparkles" : "shuffle")
                        .foregroundStyle(player.shuffleMode == .off ? CyberGlassColors.textQuiet : CyberGlassColors.cyan)
                }
                .accessibilityLabel("Shuffle")
                Spacer()
                Button { player.skipToPrevious() } label: { Image(systemName: "backward.fill") }
                    .accessibilityLabel("Previous")
                Spacer()
                Button { player.skipToNext() } label: { Image(systemName: "forward.fill") }
                    .accessibilityLabel("Next")
                Spacer()
                Button { player.cycleRepeat() } label: {
                    Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                        .foregroundStyle(player.repeatMode == .off ? CyberGlassColors.textQuiet : CyberGlassColors.cyan)
                }
                .accessibilityLabel("Repeat")
            }
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(CyberGlassColors.textSilver)
            .padding(.horizontal, 8)
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(CyberGlassColors.iridescentSoft, lineWidth: 1))
    }

    /// "BLUETOOTH • AirPods • VIBE MATCH 94%", or the format on its own.
    private var subtitle: String {
        var parts = [player.route.badge]
        if let id = player.currentTrack?.id, let decision = player.decisions[id] {
            parts.append("VIBE MATCH \(decision.vibeMatchPct)%")
        } else if let track = player.currentTrack {
            parts.append(track.qualityLabel)
        }
        return parts.joined(separator: " • ")
    }
}

/// The folders read in place: rescan, remove, add.
private struct FoldersSheet: View {
    @ObservedObject private var library = IosMusicLibraryManager.shared
    @Binding var showingPicker: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Image(systemName: "iphone").foregroundStyle(CyberGlassColors.cyan)
                        VStack(alignment: .leading) {
                            Text("On My iPhone › Harmony")
                            Text("Always read").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    ForEach(library.folders) { folder in
                        HStack {
                            Image(systemName: library.unavailableFolderIds.contains(folder.id) ? "exclamationmark.triangle" : "folder")
                                .foregroundStyle(library.unavailableFolderIds.contains(folder.id) ? CyberGlassColors.amber : CyberGlassColors.cyan)
                            VStack(alignment: .leading) {
                                Text(folder.name)
                                Text(library.unavailableFolderIds.contains(folder.id)
                                     ? "Not reachable: add it again"
                                     : folder.lastScan.map { "Read \($0.formatted(.relative(presentation: .named)))" } ?? "Not read yet")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button { library.rescan(folder: folder) } label: { Image(systemName: "arrow.clockwise") }
                                .buttonStyle(.borderless)
                        }
                        .swipeActions {
                            Button(role: .destructive) { library.removeFolder(folder) } label: { Label("Remove", systemImage: "trash") }
                        }
                    }
                } footer: {
                    Text("Removing a folder takes its songs out of Harmony; the files stay where they are.")
                }
                Section {
                    Button {
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showingPicker = true }
                    } label: { Label("Add a folder from Files", systemImage: "folder.badge.plus") }
                    Button { library.rescanAll() } label: { Label("Read everything again", systemImage: "arrow.clockwise") }
                }
            }
            .navigationTitle("Folders")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .preferredColorScheme(.dark)
    }
}
