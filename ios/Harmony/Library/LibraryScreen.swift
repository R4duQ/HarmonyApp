import SwiftUI
import UIKit

/// Songs picked for "Add to playlist".
struct TrackSelection: Identifiable {
    let id = UUID()
    let tracks: [LibraryTrack]
}

/**
 * The Library: a dark-glass search bar, Songs / Albums / Artists, the songs
 * with their quality and Smart Shuffle state, a banner while folders are
 * read or the 24-D vectors measured, and the folders read in place.
 */
struct LibraryScreen: View {
    enum Section: Hashable { case songs, albums, artists }

    @ObservedObject private var library = LibraryModel.shared
    @ObservedObject private var folders = IosMusicLibraryManager.shared
    @ObservedObject private var player = HarmonyAudioController.shared
    @ObservedObject private var indexer = LibraryIngest.shared.scanner

    @State private var section: Section = .songs
    @State private var search = ""
    @State private var showingPicker = false
    @State private var showingFolders = false
    @State private var addToPlaylist: TrackSelection?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    header
                    GlassField(placeholder: "Search songs, artists, albums", text: $search)
                    CapsuleTabs(tabs: [
                        (Section.songs, "Songs", "music.note"),
                        (Section.albums, "Albums", "square.stack"),
                        (Section.artists, "Artists", "person.2"),
                    ], selection: $section)
                    banners
                    if library.loaded && library.tracks.isEmpty {
                        emptyState
                    } else {
                        switch section {
                        case .songs: songs
                        case .albums: albums
                        case .artists: artists
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .harmonyChromeInset()
            .scrollDismissesKeyboard(.interactively)
            .background(CyberGlassColors.page.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: LibraryAlbum.self) { album in
                TrackListScreen(title: album.title, subtitle: albumSubtitle(album), artworkURL: album.artworkURL,
                                tracks: album.tracks, numbered: true)
            }
            .navigationDestination(for: LibraryArtist.self) { artist in
                TrackListScreen(title: artist.name, subtitle: "\(artist.tracks.count) songs • \(artist.albumCount) albums",
                                artworkURL: artist.artworkURL, tracks: artist.tracks, numbered: false)
            }
        }
        .sheet(isPresented: $showingPicker) {
            FolderPicker { url in folders.addFolder(url) }
        }
        .sheet(isPresented: $showingFolders) {
            FoldersSheet(showingPicker: $showingPicker)
        }
        .sheet(item: $addToPlaylist) { selection in
            AddToPlaylistSheet(tracks: selection.tracks)
        }
        .alert("Library", isPresented: Binding(get: { folders.lastError != nil }, set: { if !$0 { folders.lastError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(folders.lastError ?? "")
        }
    }

    private var query: String { search.trimmingCharacters(in: .whitespaces) }

    private func matches(_ track: LibraryTrack) -> Bool {
        query.isEmpty || track.title.localizedCaseInsensitiveContains(query) ||
            track.artist.localizedCaseInsensitiveContains(query) || track.album.localizedCaseInsensitiveContains(query)
    }

    private func albumSubtitle(_ album: LibraryAlbum) -> String {
        var parts = [album.artist, "\(album.tracks.count) songs"]
        if album.hiRes { parts.append("HI-RES") } else if album.lossless { parts.append("LOSSLESS") }
        return parts.joined(separator: " • ")
    }

    // MARK: - Header and banners

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Library")
                    .font(.system(size: 32, weight: .heavy))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                Text("\(library.tracks.count.formatted()) songs • \(library.indexedCount.formatted()) in Smart Shuffle")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(CyberGlassColors.textSecondary)
            }
            Spacer()
            Button { player.startSmartMix() } label: {
                Image(systemName: "sparkles").font(.system(size: 18, weight: .bold))
            }
            .accessibilityLabel("Smart Mix")
            .disabled(library.tracks.isEmpty)
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
        if let scan = folders.scan {
            InfoBanner(icon: "magnifyingglass", text: "Reading \(scan.location) • \(scan.seen) checked, \(scan.added) new")
        }
        if indexer.progress.isActive {
            let p = indexer.progress
            InfoBanner(icon: "waveform.path.ecg",
                       text: p.pauseReason?.rawValue ?? "24D SCANNING • \(p.done + p.failed) of \(p.total) songs measured for Smart Shuffle",
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

    // MARK: - Sections

    @ViewBuilder
    private var songs: some View {
        let shown = library.tracks.filter(matches)
        HStack {
            SectionHeading(title: query.isEmpty ? "All songs" : "Results", trailing: "\(shown.count)")
            NeonButton(title: "Shuffle", systemImage: "shuffle", prominent: false, disabled: shown.isEmpty) {
                if let random = shown.indices.randomElement() {
                    player.setShuffleMode(.random)
                    player.play(tracks: shown, startAt: random)
                }
            }
        }
        ForEach(shown.indices, id: \.self) { index in
            TrackRow(track: shown[index], playing: player.currentTrack?.id == shown[index].id)
                .contentShape(Rectangle())
                .onTapGesture { player.play(tracks: shown, startAt: index) }
                .contextMenu { TrackMenu(track: shown[index], addToPlaylist: $addToPlaylist) }
        }
    }

    @ViewBuilder
    private var albums: some View {
        let shown = library.albums.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) ||
            $0.artist.localizedCaseInsensitiveContains(query) }
        SectionHeading(title: "Albums", trailing: "\(shown.count)")
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 16) {
            ForEach(shown) { album in
                NavigationLink(value: album) {
                    VStack(alignment: .leading, spacing: 6) {
                        Color.clear
                            .aspectRatio(1, contentMode: .fit)
                            .overlay { GeometryReader { geo in Artwork(url: album.artworkURL, size: geo.size.width) } }
                            .shadow(color: CyberGlassColors.violet.opacity(0.25), radius: 10)
                        Text(album.title).font(.system(size: 13.5, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(1)
                        Text(album.artist).font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private var artists: some View {
        let shown = library.artists.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
        SectionHeading(title: "Artists", trailing: "\(shown.count)")
        ForEach(shown) { artist in
            NavigationLink(value: artist) {
                HStack(spacing: 12) {
                    Artwork(url: artist.artworkURL, size: 50)
                        .clipShape(Circle())
                        .overlay(Circle().strokeBorder(CyberGlassColors.thumbRim, lineWidth: 1))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(artist.name).font(.system(size: 15, weight: .semibold)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(1)
                        Text("\(artist.tracks.count) songs • \(artist.albumCount) albums")
                            .font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .bold)).foregroundStyle(CyberGlassColors.textQuiet)
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }
}

/// An album or an artist: a header, Play / Shuffle, and the songs.
struct TrackListScreen: View {
    let title: String
    let subtitle: String
    let artworkURL: URL?
    let tracks: [LibraryTrack]
    let numbered: Bool

    @ObservedObject private var player = HarmonyAudioController.shared
    @State private var addToPlaylist: TrackSelection?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .bottom, spacing: 16) {
                    Artwork(url: artworkURL, size: 130)
                        .shadow(color: CyberGlassColors.cyan.opacity(0.3), radius: 16)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(title).font(.system(size: 22, weight: .heavy)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(3)
                        Text(subtitle).font(.system(size: 12.5)).foregroundStyle(CyberGlassColors.textSecondary).lineLimit(2)
                    }
                }
                .padding(.top, 8)
                HStack(spacing: 10) {
                    NeonButton(title: "Play", systemImage: "play.fill") { player.play(tracks: tracks, startAt: 0) }
                    NeonButton(title: "Shuffle", systemImage: "shuffle", prominent: false) {
                        player.setShuffleMode(.random)
                        player.play(tracks: tracks, startAt: Int.random(in: 0..<max(1, tracks.count)))
                    }
                    Spacer()
                    Button { addToPlaylist = TrackSelection(tracks: tracks) } label: {
                        Image(systemName: "text.badge.plus").font(.system(size: 18, weight: .bold)).foregroundStyle(CyberGlassColors.cyan)
                    }
                    .accessibilityLabel("Add all to a playlist")
                }
                .padding(.vertical, 6)
                ForEach(tracks.indices, id: \.self) { index in
                    TrackRow(track: tracks[index], playing: player.currentTrack?.id == tracks[index].id,
                             number: numbered ? (tracks[index].trackNumber ?? index + 1) : nil)
                        .contentShape(Rectangle())
                        .onTapGesture { player.play(tracks: tracks, startAt: index) }
                        .contextMenu { TrackMenu(track: tracks[index], addToPlaylist: $addToPlaylist) }
                }
            }
            .padding(.horizontal, 16)
        }
        .harmonyChromeInset()
        .background(CyberGlassColors.page.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $addToPlaylist) { selection in AddToPlaylistSheet(tracks: selection.tracks) }
    }
}

/// What a long press on a song offers.
struct TrackMenu: View {
    let track: LibraryTrack
    @Binding var addToPlaylist: TrackSelection?

    var body: some View {
        Button { HarmonyAudioController.shared.playNext(track) } label: { Label("Play next", systemImage: "text.insert") }
        Button { HarmonyAudioController.shared.addToQueue(track) } label: { Label("Add to queue", systemImage: "text.append") }
        Button { addToPlaylist = TrackSelection(tracks: [track]) } label: { Label("Add to playlist…", systemImage: "text.badge.plus") }
        Button { HarmonyAudioController.shared.startSmartMix(from: track) } label: { Label("Smart Mix from here", systemImage: "sparkles") }
    }
}

/// A one-line notice above the list, with an optional progress line.
struct InfoBanner: View {
    let icon: String
    let text: String
    var fraction: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: icon).foregroundStyle(CyberGlassColors.cyan)
                Text(text)
                    .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.textSilver)
                    .lineLimit(2)
            }
            if let fraction { NeonProgressBar(fraction: fraction, height: 3) }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(CyberGlassColors.violet.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(CyberGlassColors.violet.opacity(0.35), lineWidth: 1))
    }
}

/// One song: artwork (or its track number), title, artist, quality, length,
/// and whether Smart Shuffle has measured it.
struct TrackRow: View {
    let track: LibraryTrack
    let playing: Bool
    var number: Int?

    var body: some View {
        HStack(spacing: 12) {
            if let number {
                Text("\(number)")
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundStyle(playing ? CyberGlassColors.cyan : CyberGlassColors.textQuiet)
                    .frame(width: 26)
            } else {
                Artwork(url: track.artworkURL, size: 48)
                    .overlay(
                        RoundedRectangle(cornerRadius: 48 * 0.22, style: .continuous)
                            .strokeBorder(playing ? CyberGlassColors.cyan : Color.clear, lineWidth: 1.5)
                    )
                    .shadow(color: playing ? CyberGlassColors.cyan.opacity(0.6) : .clear, radius: 8)
            }
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
                HStack(spacing: 6) {
                    if track.hasVector {
                        Text("24D")
                            .font(.system(size: 9, weight: .heavy, design: .monospaced))
                            .foregroundStyle(CyberGlassColors.violet)
                    }
                    Text(Formatters.duration(seconds: track.durationMs / 1000))
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.textQuiet)
                }
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}

/// A song's cover from the artwork cache, or the glowing neon waveform.
struct Artwork: View {
    let url: URL?
    let size: CGFloat

    var body: some View {
        Group {
            if let image = ArtworkCache.shared.image(for: url, maxPixels: size * 3) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                NeonArtPlaceholder()
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
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
