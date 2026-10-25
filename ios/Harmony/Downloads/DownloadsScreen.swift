import SwiftUI
import UIKit
import UniformTypeIdentifiers

/**
 * The Downloads tab, Cyber-Glass.
 *
 *  - Search / Fetch bar: a link downloads it, anything else searches Soulseek.
 *  - The background guard's badge while transfers run with the screen locked.
 *  - Four filters: Active Queue (running, waiting, paused, failed, with
 *    Pause / Resume / Retry / Cancel), Search & Discover (Soulseek, links,
 *    playlist import, recognition), Completed (with the 24-D brain badge) and
 *    Import Folder (folders and files from the Files app).
 */
struct DownloadsScreen: View {
    enum Filter: Hashable { case active, discover, completed, importFolder }
    enum Source: Hashable { case soulseek, links, playlists, recognize }

    @ObservedObject private var soulseek = SoulseekService.shared
    @ObservedObject private var links = StreamIngestManager.shared
    @ObservedObject private var resolver = LosslessTrackResolver.shared
    @ObservedObject private var transferGuard = IosBackgroundTransferGuard.shared
    @State private var filter: Filter = .discover
    @State private var source: Source = .soulseek
    @State private var fetchText = ""
    @State private var fetchError: String?

    var body: some View {
        let items = HarmonyDownloadEngine.items()
        let queue = items.filter { $0.status.isActive || $0.status == .paused || $0.status == .failed }
        let completed = items.filter { $0.status == .completed }
        ZStack {
            CyberGlassColors.page.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    if let badge = transferGuard.badge {
                        GuardBadge(text: badge, detail: guardDetail)
                    }
                    fetchBar
                    CapsuleTabs(tabs: [
                        (Filter.active, queue.isEmpty ? "Active Queue" : "Active Queue · \(queue.count)", "arrow.down.circle"),
                        (Filter.discover, "Search & Discover", "sparkle.magnifyingglass"),
                        (Filter.completed, completed.isEmpty ? "Completed" : "Completed · \(completed.count)", "checkmark.seal"),
                        (Filter.importFolder, "Import Folder", "folder.badge.plus"),
                    ], selection: $filter)
                    switch filter {
                    case .active: ActiveQueueSection(items: queue)
                    case .discover: discover
                    case .completed: CompletedSection(items: completed)
                    case .importFolder: ImportFolderPanel()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Downloads")
                .font(.system(size: 32, weight: .heavy))
                .foregroundStyle(CyberGlassColors.textPrimary)
            Text("LOSSLESS INGEST • 24D SMART SHUFFLE INDEXING")
                .font(.system(size: 10.5, weight: .semibold))
                .tracking(1.1)
                .foregroundStyle(CyberGlassColors.cyan.opacity(0.8))
        }
        .padding(.top, 12)
    }

    private var guardDetail: String {
        var parts = ["\(transferGuard.activeDownloads) running", transferGuard.tier.rawValue]
        if !transferGuard.networkAvailable {
            parts.append("waiting for the network")
        } else if transferGuard.onCellular {
            parts.append("on cellular")
        }
        return parts.joined(separator: " • ")
    }

    @ViewBuilder private var discover: some View {
        CapsuleTabs(tabs: [
            (Source.soulseek, "Soulseek P2P", "point.3.connected.trianglepath.dotted"),
            (Source.links, "Links", "link"),
            (Source.playlists, "Playlist Import", "music.note.list"),
            (Source.recognize, "Recognize", "waveform.badge.mic"),
        ], selection: $source)
        switch source {
        case .soulseek: SoulseekPanel()
        case .links: LinksPanel()
        case .playlists: PlaylistImportPanel()
        case .recognize: RecognizePanel(openImport: { source = .playlists })
        }
    }

    private var fetchBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                GlassField(placeholder: "Search Soulseek, or paste a link", text: $fetchText,
                           systemImage: "sparkle.magnifyingglass", onSubmit: fetch)
                Button(action: pasteAndFetch) {
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(CyberGlassColors.obsidian)
                        .frame(width: 48, height: 48)
                        .background(CyberGlassColors.selectedChip, in: Circle())
                        .shadow(color: CyberGlassColors.cyan.opacity(0.55), radius: 10)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Paste and fetch")
            }
            if let fetchError {
                Text(fetchError).font(.system(size: 12)).foregroundStyle(CyberGlassColors.danger)
            }
        }
    }

    private func pasteAndFetch() {
        if fetchText.isEmpty, let pasted = UIPasteboard.general.string, !pasted.isEmpty { fetchText = pasted }
        fetch()
    }

    /// A link is downloaded; any other text is a Soulseek search.
    private func fetch() {
        let text = fetchText.trimmingCharacters(in: .whitespacesAndNewlines)
        fetchError = nil
        guard !text.isEmpty else { return }
        if StreamIngestManager.extractURL(from: text) != nil {
            fetchError = links.enqueue(text)
            if fetchError == nil {
                fetchText = ""
                filter = .active
            }
            return
        }
        guard text.count >= 2 else { return }
        filter = .discover
        source = .soulseek
        if soulseek.isConnected {
            soulseek.searchForUI(text)
        } else {
            Task {
                if await soulseek.ensureConnected() {
                    soulseek.searchForUI(text)
                } else {
                    fetchError = "Sign in to Soulseek below to search."
                }
            }
        }
    }
}

// MARK: - Active queue and completed

private struct ActiveQueueSection: View {
    let items: [DownloadItem]
    @ObservedObject private var transferGuard = IosBackgroundTransferGuard.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            list
            CyberCard {
                Toggle(isOn: $transferGuard.keepAliveWhenLocked) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Keep downloads running when locked")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(CyberGlassColors.textPrimary)
                        Text("With nothing playing, Harmony keeps a silent audio session open while Soulseek transfers run, so iOS doesn't freeze them. It stops the moment the last transfer ends. Off: transfers pause when iOS suspends Harmony and continue when you come back.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(CyberGlassColors.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(CyberGlassColors.cyan)
            }
        }
    }

    @ViewBuilder private var list: some View {
        if items.isEmpty {
            CyberCard {
                Text("Nothing downloading")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                Text("Search Soulseek or paste a link above. Up to \(SoulseekService.maxConcurrent) Soulseek transfers run at once; the others wait their turn. A paused or interrupted transfer continues from the bytes it already has.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(CyberGlassColors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        } else {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeading(title: "Transfers", trailing: "\(items.filter { $0.status.isActive }.count) active")
                ForEach(items) { item in
                    DownloadItemCard(item: item,
                                     onPause: { HarmonyDownloadEngine.pause(item) },
                                     onResume: { HarmonyDownloadEngine.resume(item) },
                                     onRetry: { HarmonyDownloadEngine.retry(item) },
                                     onCancel: { HarmonyDownloadEngine.cancel(item) })
                }
            }
        }
    }
}

private struct CompletedSection: View {
    let items: [DownloadItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if items.isEmpty {
                CyberCard {
                    Text("Finished downloads appear here, tagged, in your library and indexed for Smart Shuffle.")
                        .font(.system(size: 13))
                        .foregroundStyle(CyberGlassColors.textSecondary)
                }
            } else {
                HStack {
                    SectionHeading(title: "In your library", trailing: "\(items.filter(\.indexed).count) indexed")
                }
                ForEach(items) { item in DownloadItemCard(item: item) }
                NeonButton(title: "Clear finished", systemImage: "trash", prominent: false) {
                    HarmonyDownloadEngine.clearFinished()
                }
            }
        }
    }
}

// MARK: - Import folder

private struct ImportFolderPanel: View {
    @ObservedObject private var library = IosMusicLibraryManager.shared
    @State private var picking = false
    @State private var importing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            CyberCard {
                Text("Bring your own music")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                Text("A folder from the Files app (iCloud Drive, a USB drive, another app) is read where it is, without copying, and stays available after restarts. Files you import are copied into Files › On My iPhone › Harmony › Imported. Either way, every song is tagged, added and indexed for Smart Shuffle.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(CyberGlassColors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 10) {
                    NeonButton(title: "Add a folder", systemImage: "folder.badge.plus") { picking = true }
                    NeonButton(title: "Import files", systemImage: "square.and.arrow.down", prominent: false) { importing = true }
                }
                if let scan = library.scan {
                    Text("Reading \(scan.location) • \(scan.seen) checked, \(scan.added) new")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.cyan)
                } else if let summary = library.lastScanSummary {
                    Text(summary).font(.system(size: 12)).foregroundStyle(CyberGlassColors.mint)
                }
            }
            if !library.folders.isEmpty {
                SectionHeading(title: "Folders", trailing: "\(library.folders.count)")
                ForEach(library.folders) { folder in
                    let reachable = !library.unavailableFolderIds.contains(folder.id)
                    HStack(spacing: 12) {
                        Image(systemName: reachable ? "folder.fill" : "exclamationmark.triangle.fill")
                            .foregroundStyle(reachable ? CyberGlassColors.cyan : CyberGlassColors.amber)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(folder.name).font(.system(size: 14, weight: .semibold)).foregroundStyle(CyberGlassColors.textPrimary)
                            Text(reachable ? "Read in place" : "Not reachable: add it again")
                                .font(.system(size: 11.5)).foregroundStyle(CyberGlassColors.textSecondary)
                        }
                        Spacer()
                        ControlPill(title: "Read", systemImage: "arrow.clockwise") { library.rescan(folder: folder) }
                        ControlPill(title: "Remove", systemImage: "trash", destructive: true) { library.removeFolder(folder) }
                    }
                    .padding(12)
                    .background(CyberGlassColors.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
        .sheet(isPresented: $picking) {
            FolderPicker { url in library.addFolder(url) }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            for url in urls { IncomingLinks.handle(url) }
        }
    }
}

// MARK: - Soulseek

private struct SoulseekPanel: View {
    @ObservedObject private var service = SoulseekService.shared
    @State private var password = ""
    @State private var remember = true
    @State private var query = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            CyberCard {
                HStack(spacing: 10) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 9, height: 9)
                        .shadow(color: statusColor, radius: 5)
                    Text(service.statusMessage)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(CyberGlassColors.textSilver)
                    Spacer()
                    if service.isConnected {
                        NeonButton(title: "Disconnect", prominent: false) { service.disconnect() }
                    }
                }
                if !service.isConnected {
                    GlassField(placeholder: "Soulseek username", text: $service.username, systemImage: "person")
                    GlassField(placeholder: "Password", text: $password, systemImage: "key", secure: true,
                               onSubmit: { service.connect(password: password, remember: remember) })
                    Toggle("Remember on this iPhone (Keychain)", isOn: $remember)
                        .font(.system(size: 13))
                        .foregroundStyle(CyberGlassColors.textSecondary)
                        .tint(CyberGlassColors.cyan)
                    HStack(spacing: 10) {
                        NeonButton(title: service.status == .connecting ? "Connecting…" : "Connect", systemImage: "bolt.fill",
                                   disabled: service.status == .connecting) {
                            service.connect(password: password, remember: remember)
                        }
                        if service.hasSavedPassword {
                            NeonButton(title: "Use saved", prominent: false) { Task { _ = await service.ensureConnected() } }
                        }
                    }
                    Text("Soulseek allows one session per account: signing in here signs out your other clients. New to Soulseek? Pick any free username and password; the account is created on first sign-in.")
                        .font(.system(size: 11.5))
                        .foregroundStyle(CyberGlassColors.textQuiet)
                }
            }

            if service.isConnected {
                GlassField(placeholder: "Artist - Song, or an album", text: $query, onSubmit: { service.searchForUI(query) })
                HStack(spacing: 10) {
                    Menu {
                        ForEach(HarmonySlskFormat.allCases, id: \.rawValue) { option in
                            Button(option.label) { service.format = option }
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "slider.horizontal.3")
                            Text(service.format.label)
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(CyberGlassColors.cyan)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background(CyberGlassColors.chip, in: Capsule())
                        .overlay(Capsule().strokeBorder(CyberGlassColors.chipBorder, lineWidth: 1))
                    }
                    Spacer()
                    NeonButton(title: service.searching ? "Searching…" : "Search", systemImage: "magnifyingglass",
                               disabled: service.searching || query.count < 2) {
                        service.searchForUI(query)
                    }
                }
                if service.searching || !service.results.isEmpty {
                    SectionHeading(title: "Best sources", trailing: service.lastSearchStats)
                }
                if service.searching && service.results.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView().tint(CyberGlassColors.cyan)
                        Text("Asking peers…").font(.system(size: 13)).foregroundStyle(CyberGlassColors.textSecondary)
                    }
                }
                ForEach(service.results, id: \.identifier) { candidate in
                    CandidateRow(candidate: candidate) { service.startDownload(candidate) }
                }
            }
        }
    }

    private var statusColor: Color {
        switch service.status {
        case .connected: return CyberGlassColors.mint
        case .connecting: return CyberGlassColors.amber
        case .error: return CyberGlassColors.danger
        default: return CyberGlassColors.textQuiet
        }
    }
}

private struct CandidateRow: View {
    let candidate: HarmonySlskCandidate
    let onDownload: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle().stroke(CyberGlassColors.sky.opacity(0.15), lineWidth: 3)
                Circle()
                    .trim(from: 0, to: CGFloat(candidate.score) / 100)
                    .stroke(CyberGlassColors.iridescentSweep, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text("\(candidate.score)")
                    .font(.system(size: 12, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.textPrimary)
            }
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 5) {
                Text((candidate.leafName as NSString).deletingPathExtension)
                    .font(.system(size: 14.5, weight: .bold))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                    .lineLimit(2)
                Text("\(candidate.username) · \(candidate.recommendation)")
                    .font(.system(size: 12))
                    .foregroundStyle(CyberGlassColors.textSecondary)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    InfoChip(text: quality, accent: candidate.fileExtension == "flac" ? CyberGlassColors.mint : CyberGlassColors.sky)
                    if candidate.durationSeconds > 0 { InfoChip(text: Formatters.duration(seconds: candidate.durationSeconds)) }
                    InfoChip(text: Formatters.megabytes(candidate.size), accent: CyberGlassColors.textSecondary)
                }
            }
            Spacer(minLength: 0)
            Button(action: onDownload) {
                Image(systemName: "arrow.down")
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(CyberGlassColors.obsidian)
                    .frame(width: 38, height: 38)
                    .background(CyberGlassColors.selectedChip, in: Circle())
                    .shadow(color: CyberGlassColors.cyan.opacity(0.45), radius: 8)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Download")
        }
        .padding(12)
        .background(CyberGlassColors.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(CyberGlassColors.chipBorder, lineWidth: 1))
    }

    private var quality: String {
        let ext = candidate.fileExtension.uppercased()
        if candidate.bitDepth > 0 || candidate.sampleRate > 0 {
            let rate = candidate.sampleRate > 0 ? String(format: "%g", Double(candidate.sampleRate) / 1000) : "?"
            return "\(ext) \(candidate.bitDepth > 0 ? "\(candidate.bitDepth)" : "?")/\(rate)"
        }
        if candidate.bitrateKbps > 0 { return "\(ext) \(candidate.bitrateKbps)k" }
        return ext
    }
}

// MARK: - Links

private struct LinksPanel: View {
    @ObservedObject private var links = StreamIngestManager.shared

    var body: some View {
        CyberCard {
            Text("Direct links")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(CyberGlassColors.textPrimary)
            Text("Paste a link that downloads an audio file (FLAC, ALAC, WAV, AIFF, MP3, M4A): a Bandcamp or label download, archive.org, your own server. Harmony checks the file, stores WAV/AIFF losslessly, tags it and measures its 24D vector. The download continues with Harmony closed. Tip: add a Shortcut that opens harmony://import?url=… to send links from the share sheet.")
                .font(.system(size: 12.5))
                .foregroundStyle(CyberGlassColors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Picker("Store WAV/AIFF as", selection: $links.losslessStorage) {
                ForEach(LosslessStorage.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            if links.jobs.contains(where: { !$0.stage.isActive }) {
                NeonButton(title: "Clear finished", prominent: false) { links.clearFinished() }
            }
        }
    }
}

// MARK: - Playlist import

private struct PlaylistImportPanel: View {
    enum Source: Hashable { case spotify, deezer, text }

    @ObservedObject private var importer = PlaylistImporter.shared
    @ObservedObject private var resolver = LosslessTrackResolver.shared
    @State private var source: Source = .spotify
    @State private var deezerLink = ""
    @State private var pasted = ""
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            CapsuleTabs(tabs: [(Source.spotify, "Spotify", "dot.radiowaves.left.and.right"),
                               (Source.deezer, "Deezer link", "link"),
                               (Source.text, "Paste a list", "text.alignleft")], selection: $source)
            CyberCard {
                switch source {
                case .spotify: spotify
                case .deezer: deezer
                case .text: text
                }
                if let error = importer.errorMessage {
                    Text(error).font(.system(size: 12)).foregroundStyle(CyberGlassColors.danger)
                }
            }
            if !resolver.items.isEmpty { ResolverCard() }
        }
    }

    @ViewBuilder private var spotify: some View {
        if importer.spotifyConnected {
            HStack {
                Text("Your Spotify playlists").font(.system(size: 16, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary)
                Spacer()
                NeonButton(title: "Sign out", prominent: false) { importer.disconnectSpotify() }
            }
            if importer.loading { ProgressView().tint(CyberGlassColors.cyan) }
            ForEach(importer.playlists) { playlist in
                Button {
                    Task { await loadSpotify(playlist) }
                } label: {
                    HStack(spacing: 12) {
                        AsyncImage(url: playlist.imageURL) { image in image.resizable().scaledToFill() } placeholder: {
                            NeonArtPlaceholder()
                        }
                        .frame(width: 46, height: 46)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(playlist.name).font(.system(size: 14.5, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(1)
                            Text("\(playlist.trackCount) songs · \(playlist.owner)").font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary)
                        }
                        Spacer()
                        Image(systemName: playlist.importable ? "chevron.right" : "lock.fill")
                            .foregroundStyle(playlist.importable ? CyberGlassColors.cyan : CyberGlassColors.textQuiet)
                    }
                }
                .buttonStyle(.plain)
                .disabled(!playlist.importable || busy)
            }
            if importer.playlists.isEmpty && !importer.loading {
                NeonButton(title: "Load playlists", systemImage: "arrow.clockwise") { Task { await importer.loadSpotifyPlaylists() } }
            }
        } else {
            Text("Import from Spotify").font(.system(size: 16, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary)
            Text("Spotify needs your own developer app (developer.spotify.com › Dashboard › Create app). Add the redirect URI \(PlaylistImporter.redirectURI) and paste its Client ID here. The same app works for Harmony on Android.")
                .font(.system(size: 12.5))
                .foregroundStyle(CyberGlassColors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            GlassField(placeholder: "Spotify Client ID", text: $importer.spotifyClientId, systemImage: "key")
            NeonButton(title: "Sign in to Spotify", systemImage: "person.badge.key") { Task { await importer.connectSpotify() } }
        }
    }

    @ViewBuilder private var deezer: some View {
        Text("Any public Deezer playlist").font(.system(size: 16, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary)
        GlassField(placeholder: "https://www.deezer.com/…/playlist/…", text: $deezerLink, systemImage: "link")
        NeonButton(title: busy ? "Loading…" : "Load playlist", systemImage: "arrow.down.doc", disabled: busy || deezerLink.isEmpty) {
            Task {
                busy = true
                defer { busy = false }
                do {
                    let (name, tracks) = try await importer.deezerTracks(link: deezerLink)
                    resolver.load(tracks, name: name)
                } catch {
                    importer.errorMessage = error.localizedDescription
                }
            }
        }
    }

    @ViewBuilder private var text: some View {
        Text("One song per line: Artist - Title").font(.system(size: 16, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary)
        TextEditor(text: $pasted)
            .scrollContentBackground(.hidden)
            .font(.system(size: 14, design: .monospaced))
            .foregroundStyle(CyberGlassColors.textPrimary)
            .frame(minHeight: 140)
            .padding(8)
            .background(CyberGlassColors.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(CyberGlassColors.searchRim, lineWidth: 1))
        NeonButton(title: "Use this list", systemImage: "checklist") {
            let tracks = importer.pastedTracks(pasted)
            if tracks.isEmpty {
                importer.errorMessage = "No \"Artist - Title\" lines found."
            } else {
                importer.errorMessage = nil
                resolver.load(tracks, name: "Pasted list")
            }
        }
    }

    private func loadSpotify(_ playlist: RemotePlaylist) async {
        busy = true
        defer { busy = false }
        do {
            resolver.load(try await importer.spotifyTracks(playlistId: playlist.id), name: playlist.name)
        } catch {
            importer.errorMessage = error.localizedDescription
        }
    }
}

private struct ResolverCard: View {
    @ObservedObject private var resolver = LosslessTrackResolver.shared

    var body: some View {
        CyberCard {
            VStack(alignment: .leading, spacing: 3) {
                Text(resolver.playlistName).font(.system(size: 17, weight: .heavy)).foregroundStyle(CyberGlassColors.textPrimary)
                Text(resolver.summary).font(.system(size: 12, design: .monospaced)).foregroundStyle(CyberGlassColors.sky)
            }
            Toggle("MP3 when no FLAC exists", isOn: $resolver.allowMp3Fallback)
                .font(.system(size: 13))
                .foregroundStyle(CyberGlassColors.textSecondary)
                .tint(CyberGlassColors.cyan)
                .disabled(resolver.running)
            if resolver.running {
                NeonButton(title: "Stop", systemImage: "stop.fill", prominent: false) { resolver.cancel() }
            } else {
                NeonButton(title: "Find lossless on Soulseek", systemImage: "sparkles") { resolver.start() }
            }
            ForEach(resolver.items) { item in
                HStack(spacing: 10) {
                    stateIcon(item.state)
                        .frame(width: 22)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.track.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(1)
                        Text("\(item.track.artist) · \(item.state.label)")
                            .font(.system(size: 11.5))
                            .foregroundStyle(stateColor(item.state))
                            .lineLimit(1)
                    }
                    Spacer()
                    if let ms = item.track.durationMs {
                        Text(Formatters.duration(seconds: ms / 1000))
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(CyberGlassColors.sky)
                    }
                }
                .padding(.vertical, 4)
                Rectangle().fill(CyberGlassColors.divider).frame(height: 1)
            }
        }
    }

    @ViewBuilder private func stateIcon(_ state: LosslessTrackResolver.State) -> some View {
        switch state {
        case .searching, .downloading, .indexing: ProgressView().tint(CyberGlassColors.cyan).scaleEffect(0.7)
        case .done, .inLibrary: Image(systemName: "checkmark.circle.fill").foregroundStyle(CyberGlassColors.mint)
        case .notFound: Image(systemName: "questionmark.circle").foregroundStyle(CyberGlassColors.textQuiet)
        case .failed: Image(systemName: "exclamationmark.circle.fill").foregroundStyle(CyberGlassColors.danger)
        case .waiting: Image(systemName: "circle").foregroundStyle(CyberGlassColors.textQuiet)
        }
    }

    private func stateColor(_ state: LosslessTrackResolver.State) -> Color {
        switch state {
        case .done, .inLibrary: return CyberGlassColors.mint
        case .failed: return CyberGlassColors.danger
        case .searching, .downloading, .indexing: return CyberGlassColors.cyan
        default: return CyberGlassColors.textSecondary
        }
    }
}

// MARK: - Recognition

private struct RecognizePanel: View {
    let openImport: () -> Void
    @ObservedObject private var service = SongRecognitionService.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            CyberCard {
                VStack(spacing: 14) {
                    Button { service.toggle() } label: {
                        ZStack {
                            Circle()
                                .fill(CyberGlassColors.cyan.opacity(0.10))
                                .frame(width: 170, height: 170)
                                .scaleEffect(1 + CGFloat(service.level) * 0.35)
                                .animation(.easeOut(duration: 0.12), value: service.level)
                            Circle()
                                .strokeBorder(CyberGlassColors.iridescentSweep, lineWidth: 2)
                                .frame(width: 140, height: 140)
                                .shadow(color: CyberGlassColors.violet.opacity(0.6), radius: 14)
                            Circle()
                                .fill(CyberGlassColors.playButton)
                                .frame(width: 112, height: 112)
                                .shadow(color: CyberGlassColors.violet.opacity(0.6), radius: 16)
                            Image(systemName: service.isListening ? "stop.fill" : "waveform")
                                .font(.system(size: 38, weight: .bold))
                                .foregroundStyle(.white)
                        }
                    }
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity)
                    Text(statusText)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(statusColor)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }
                if case .matched(let song) = service.state {
                    RecognizedRow(song: song) { find(song) }
                }
            }
            if !service.history.isEmpty {
                SectionHeading(title: "Recognised")
                ForEach(service.history) { song in RecognizedRow(song: song) { find(song) } }
            }
        }
    }

    private var statusText: String {
        switch service.state {
        case .idle: return "Tap to listen to the music around you"
        case .listening(let elapsed): return String(format: "Listening… %.0f s", elapsed)
        case .matched: return "Found it"
        case .noMatch: return "No match. Try closer to the speaker."
        case .failed(let why): return why
        }
    }

    private var statusColor: Color {
        switch service.state {
        case .failed: return CyberGlassColors.danger
        case .matched: return CyberGlassColors.mint
        default: return CyberGlassColors.textSecondary
        }
    }

    private func find(_ song: RecognizedSong) {
        LosslessTrackResolver.shared.load([song.importTrack], name: "\(song.artist) - \(song.title)")
        LosslessTrackResolver.shared.start()
        openImport()
    }
}

private struct RecognizedRow: View {
    let song: RecognizedSong
    let onFind: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: song.artworkURL) { image in image.resizable().scaledToFill() } placeholder: { NeonArtPlaceholder() }
                .frame(width: 50, height: 50)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(CyberGlassColors.thumbRim, lineWidth: 1))
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).font(.system(size: 15, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(1)
                Text(song.artist).font(.system(size: 13)).foregroundStyle(CyberGlassColors.textSecondary).lineLimit(1)
            }
            Spacer()
            NeonButton(title: "FLAC", systemImage: "arrow.down", action: onFind)
        }
    }
}

/// The neon waveform shown where there is no cover art.
struct NeonArtPlaceholder: View {
    var body: some View {
        ZStack {
            CyberGlassColors.artWell
            HStack(spacing: 2.5) {
                ForEach([0.3, 0.55, 0.85, 1.0, 0.85, 0.55, 0.3], id: \.self) { h in
                    Capsule()
                        .fill(LinearGradient(colors: [CyberGlassColors.cyan, CyberGlassColors.violet, CyberGlassColors.purple],
                                             startPoint: .top, endPoint: .bottom))
                        .frame(width: 3, height: 22 * h)
                        .shadow(color: CyberGlassColors.cyan.opacity(0.5), radius: 3)
                }
            }
        }
    }
}
