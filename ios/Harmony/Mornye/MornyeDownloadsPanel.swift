import SwiftUI

struct MornyeDownloadsPanel: View {
    @ObservedObject private var service = MornyeService.shared
    @State private var query = ""
    @State private var provider = ""
    @State private var quality: MornyeQuality = .hiRes

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            CyberCard {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Mornye").font(.system(size: 22, weight: .heavy))
                            .foregroundStyle(CyberGlassColors.textPrimary)
                        Text("Tidal · Qobuz · Deezer · Amazon")
                            .font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary)
                    }
                    Spacer()
                    if service.isPreparing || service.isSearching { ProgressView().tint(CyberGlassColors.cyan) }
                }
                GlassField(placeholder: "Artist, song, album or provider link", text: $query,
                           systemImage: "magnifyingglass", onSubmit: search)
                HStack {
                    Picker("Provider", selection: $provider) {
                        Text("Auto · try available sources").tag("")
                        ForEach(MornyeProvider.order, id: \.self) { id in
                            Text(MornyeProvider.names[id] ?? id).tag(id)
                        }
                    }
                    .tint(CyberGlassColors.cyan)
                    Spacer()
                }
                Picker("Quality", selection: $quality) {
                    ForEach(MornyeQuality.allCases) { value in Text(value.title).tag(value) }
                }
                .pickerStyle(.segmented)
                NeonButton(title: service.isSearching ? "Searching…" : "Search Mornye", systemImage: "sparkle.magnifyingglass",
                           disabled: service.isSearching || query.trimmingCharacters(in: .whitespacesAndNewlines).count < 2,
                           action: search)
                Text("Downloads join Active Queue and are added to your library with tags, artwork and 24D indexing.")
                    .font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let pending = service.verification {
                CyberCard {
                    Text("\(MornyeProvider.names[pending.providerID] ?? pending.providerID) verification")
                        .font(.system(size: 15, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary)
                    Text("Complete the provider's check in the browser. Harmony resumes the waiting download when you return.")
                        .font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary)
                    NeonButton(title: "Verify provider", systemImage: "checkmark.shield", action: service.verify)
                }
            }
            if let error = service.error {
                CyberCard {
                    Text(error).font(.system(size: 12)).foregroundStyle(CyberGlassColors.danger)
                        .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                }
            }
            if !service.results.isEmpty {
                SectionHeading(title: "Mornye results", trailing: "\(service.results.count) songs")
                LazyVStack(spacing: 10) {
                    ForEach(service.results) { track in
                        CyberCard(padding: 12) {
                            HStack(spacing: 12) {
                                AsyncImage(url: track.coverURL) { image in image.resizable().scaledToFill() } placeholder: {
                                    CyberGlassColors.surface.overlay(Image(systemName: "music.note").foregroundStyle(CyberGlassColors.cyan))
                                }
                                .frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 10))
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(track.title).font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(CyberGlassColors.textPrimary).lineLimit(2)
                                    Text(track.artist).font(.system(size: 12))
                                        .foregroundStyle(CyberGlassColors.textSecondary).lineLimit(1)
                                    Text([MornyeProvider.names[track.providerID] ?? track.providerID, track.album]
                                        .filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(.system(size: 10)).foregroundStyle(CyberGlassColors.textQuiet).lineLimit(1)
                                }
                                Spacer(minLength: 0)
                                Button {
                                    service.enqueue(track, quality: quality, provider: provider)
                                } label: {
                                    Image(systemName: "arrow.down.circle.fill").font(.system(size: 28))
                                        .foregroundStyle(CyberGlassColors.cyan)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Download \(track.title) with Mornye")
                                .disabled(service.jobs.contains { $0.track.id == track.id && ($0.stage.active || $0.stage == .paused) })
                            }
                        }
                    }
                }
            } else if !service.isSearching && !service.isPreparing {
                Text("Search for a song or paste a Tidal, Qobuz, Deezer or Amazon Music link.")
                    .font(.system(size: 13)).foregroundStyle(CyberGlassColors.textSecondary)
            }
        }
        .task { service.activate() }
    }

    private func search() { service.search(query, provider: provider) }
}
