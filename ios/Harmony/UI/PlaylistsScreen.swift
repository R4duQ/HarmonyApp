import SwiftUI

/**
 * Playlists: the listener's own, and the ones imported from Spotify, Deezer
 * or a pasted list (saved here once the lossless matcher has finished).
 */
struct PlaylistsScreen: View {
    @ObservedObject private var library = LibraryModel.shared
    @State private var creating = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Playlists")
                                .font(.system(size: 32, weight: .heavy))
                                .foregroundStyle(CyberGlassColors.textPrimary)
                            Text("\(library.playlists.count) playlists")
                                .font(.system(size: 12.5, weight: .medium))
                                .foregroundStyle(CyberGlassColors.textSecondary)
                        }
                        Spacer()
                        Button { creating = true } label: {
                            Image(systemName: "plus").font(.system(size: 20, weight: .bold)).foregroundStyle(CyberGlassColors.cyan)
                        }
                        .accessibilityLabel("New playlist")
                    }
                    .padding(.top, 12)

                    if library.playlists.isEmpty {
                        CyberCard {
                            Text("No playlists yet")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(CyberGlassColors.textPrimary)
                            Text("Make one here, then long-press any song and choose Add to playlist. Playlists imported in Downloads › Playlist Import appear here too, with every song that is now in your library.")
                                .font(.system(size: 13))
                                .foregroundStyle(CyberGlassColors.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                            NeonButton(title: "New playlist", systemImage: "plus") { creating = true }
                        }
                    }
                    ForEach(library.playlists) { playlist in
                        NavigationLink(value: playlist) {
                            PlaylistRow(playlist: playlist)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
            .harmonyChromeInset()
            .background(CyberGlassColors.page.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: LibraryPlaylist.self) { playlist in
                PlaylistDetailScreen(playlistId: playlist.id)
            }
        }
        .alert("New playlist", isPresented: $creating) {
            TextField("Name", text: $newName)
            Button("Create") {
                let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { _ = try? HarmonyLibraryStore.shared.createPlaylist(name: name) }
                newName = ""
                LibraryModel.shared.reload()
            }
            Button("Cancel", role: .cancel) { newName = "" }
        }
    }
}

/// A playlist with a mosaic of its first covers.
struct PlaylistRow: View {
    let playlist: LibraryPlaylist
    @ObservedObject private var library = LibraryModel.shared

    var body: some View {
        HStack(spacing: 14) {
            PlaylistMosaic(urls: playlist.coverTrackIds.map { library.track(id: $0)?.artworkURL }, size: 64)
            VStack(alignment: .leading, spacing: 3) {
                Text(playlist.name).font(.system(size: 16, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(1)
                Text("\(playlist.songCount) songs • \(Self.length(playlist.durationMs))")
                    .font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 12, weight: .bold)).foregroundStyle(CyberGlassColors.textQuiet)
        }
        .padding(12)
        .background(CyberGlassColors.card.opacity(CyberGlassColors.cardAlpha), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(CyberGlassColors.chipBorder, lineWidth: 1))
        .contentShape(Rectangle())
    }

    /// "1 h 12 min", "42 min".
    static func length(_ ms: Int) -> String {
        let minutes = ms / 60_000
        return minutes >= 60 ? "\(minutes / 60) h \(minutes % 60) min" : "\(minutes) min"
    }
}

/// Up to four covers in a square.
struct PlaylistMosaic: View {
    let urls: [URL?]
    let size: CGFloat

    var body: some View {
        Group {
            if urls.count >= 4 {
                let half = size / 2
                VStack(spacing: 0) {
                    HStack(spacing: 0) { tile(urls[0], half); tile(urls[1], half) }
                    HStack(spacing: 0) { tile(urls[2], half); tile(urls[3], half) }
                }
            } else {
                Artwork(url: urls.compactMap { $0 }.first, size: size)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.2, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: size * 0.2, style: .continuous).strokeBorder(CyberGlassColors.thumbRim, lineWidth: 1))
    }

    private func tile(_ url: URL?, _ side: CGFloat) -> some View {
        Group {
            if let image = ArtworkCache.shared.image(for: url, maxPixels: side * 3) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                NeonArtPlaceholder()
            }
        }
        .frame(width: side, height: side)
        .clipped()
    }
}

/// One playlist: Play / Shuffle, its songs (reorder, remove), rename, delete.
struct PlaylistDetailScreen: View {
    let playlistId: Int64

    @ObservedObject private var library = LibraryModel.shared
    @ObservedObject private var player = HarmonyAudioController.shared
    @Environment(\.dismiss) private var dismiss
    @State private var tracks: [LibraryTrack] = []
    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmDelete = false

    private var playlist: LibraryPlaylist? { library.playlists.first { $0.id == playlistId } }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 16) {
                        PlaylistMosaic(urls: tracks.prefix(4).map(\.artworkURL), size: 110)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(playlist?.name ?? "")
                                .font(.system(size: 22, weight: .heavy))
                                .foregroundStyle(CyberGlassColors.textPrimary)
                                .lineLimit(3)
                            Text("\(tracks.count) songs • \(PlaylistRow.length(tracks.reduce(0) { $0 + $1.durationMs }))")
                                .font(.system(size: 12.5))
                                .foregroundStyle(CyberGlassColors.textSecondary)
                        }
                    }
                    HStack(spacing: 10) {
                        NeonButton(title: "Play", systemImage: "play.fill", disabled: tracks.isEmpty) {
                            player.play(tracks: tracks, startAt: 0)
                        }
                        NeonButton(title: "Shuffle", systemImage: "shuffle", prominent: false, disabled: tracks.isEmpty) {
                            player.setShuffleMode(.random)
                            player.play(tracks: tracks, startAt: Int.random(in: 0..<max(1, tracks.count)))
                        }
                    }
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            Section {
                ForEach(tracks.indices, id: \.self) { index in
                    TrackRow(track: tracks[index], playing: player.currentTrack?.id == tracks[index].id)
                        .contentShape(Rectangle())
                        .onTapGesture { player.play(tracks: tracks, startAt: index) }
                        .listRowBackground(Color.clear)
                }
                .onDelete { offsets in
                    tracks.remove(atOffsets: offsets)
                    save()
                }
                .onMove { source, destination in
                    tracks.move(fromOffsets: source, toOffset: destination)
                    save()
                }
            }
            Color.clear.frame(height: 180).listRowBackground(Color.clear).listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(CyberGlassColors.page.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                EditButton()
                Menu {
                    Button { newName = playlist?.name ?? ""; renaming = true } label: { Label("Rename", systemImage: "pencil") }
                    Button(role: .destructive) { confirmDelete = true } label: { Label("Delete playlist", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .onAppear { tracks = HarmonyLibraryStore.shared.tracks(inPlaylist: playlistId) }
        .alert("Rename playlist", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Save") {
                let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { HarmonyLibraryStore.shared.renamePlaylist(id: playlistId, name: name) }
                LibraryModel.shared.reload()
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Delete this playlist?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                HarmonyLibraryStore.shared.deletePlaylist(id: playlistId)
                LibraryModel.shared.reload()
                dismiss()
            }
        } message: {
            Text("The songs stay in your library.")
        }
    }

    private func save() {
        HarmonyLibraryStore.shared.setPlaylistTracks(id: playlistId, trackIds: tracks.map(\.id))
        LibraryModel.shared.reload()
    }
}

/// Picks a playlist (or makes one) for [tracks].
struct AddToPlaylistSheet: View {
    let tracks: [LibraryTrack]

    @ObservedObject private var library = LibraryModel.shared
    @Environment(\.dismiss) private var dismiss
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                Section("New playlist") {
                    HStack {
                        TextField("Name", text: $newName)
                        Button("Create") {
                            let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !name.isEmpty, let id = try? HarmonyLibraryStore.shared.createPlaylist(name: name) else { return }
                            add(to: id)
                        }
                        .disabled(newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                if !library.playlists.isEmpty {
                    Section("Your playlists") {
                        ForEach(library.playlists) { playlist in
                            Button { add(to: playlist.id) } label: {
                                HStack {
                                    Text(playlist.name)
                                    Spacer()
                                    Text("\(playlist.songCount)").foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(tracks.count == 1 ? "Add “\(tracks[0].title)”" : "Add \(tracks.count) songs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
    }

    private func add(to playlistId: Int64) {
        HarmonyLibraryStore.shared.addToPlaylist(id: playlistId, trackIds: tracks.map(\.id))
        LibraryModel.shared.reload()
        dismiss()
    }
}
