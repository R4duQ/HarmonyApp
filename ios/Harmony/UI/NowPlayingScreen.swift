import SwiftUI

/**
 * Now Playing, full screen: the record turning, the song and where it plays,
 * a seek line, the transport with shuffle and repeat, and the way into the
 * live equalizer, the queue and the Smart Shuffle brain HUD.
 */
struct NowPlayingScreen: View {
    @ObservedObject private var player = HarmonyAudioController.shared
    @ObservedObject private var router = HarmonyRouter.shared
    @Environment(\.dismiss) private var dismiss
    @State private var scrubbing: Double?
    @State private var showEqualizer = false
    @State private var showQueue = false

    var body: some View {
        GeometryReader { geo in
            content(discSize: max(180, min(geo.size.width - 70, geo.size.height * 0.4, 330)))
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showEqualizer) { WinampLiveEqualizerView() }
        .sheet(isPresented: $showQueue) { QueueSheet() }
        .onChange(of: player.currentEntry == nil) { empty in if empty { dismiss() } }
    }

    private func content(discSize: CGFloat) -> some View {
        ZStack(alignment: .top) {
            background
            VStack(spacing: 18) {
                topBar
                Spacer(minLength: 0)
                VinylDisc(artworkURL: player.currentTrack?.artworkURL, spinning: player.isPlaying, size: discSize)
                    .shadow(color: CyberGlassColors.violet.opacity(0.5), radius: 30)
                    .gesture(DragGesture(minimumDistance: 30).onEnded { value in
                        if value.translation.width < -60 { player.skipToNext() }
                        if value.translation.width > 60 { player.skipToPrevious() }
                    })
                Spacer(minLength: 0)
                songInfo
                seekBar
                transport
                bottomActions
            }
            .padding(.horizontal, 22)
            .padding(.bottom, 12)
            if router.showBrainHUD {
                ShuffleBrainDebugOverlay(visible: $router.showBrainHUD)
                    .padding(.horizontal, 12)
                    .padding(.top, 56)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: router.showBrainHUD)
        .gesture(DragGesture(minimumDistance: 40).onEnded { value in
            if value.translation.height > 120, abs(value.translation.width) < 80 { dismiss() }
        })
    }

    private var background: some View {
        ZStack {
            CyberGlassColors.obsidian
            if let image = ArtworkCache.shared.image(for: player.currentTrack?.artworkURL, maxPixels: 600) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .blur(radius: 60)
                    .opacity(0.45)
                    .clipped()
            }
            LinearGradient(colors: [CyberGlassColors.obsidian.opacity(0.2), CyberGlassColors.obsidian.opacity(0.95)],
                           startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
    }

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.down").font(.system(size: 18, weight: .bold)).frame(width: 40, height: 40)
            }
            .accessibilityLabel("Close")
            Spacer()
            VStack(spacing: 2) {
                Text(player.isPlaying ? "NOW PLAYING" : "PAUSED")
                    .font(.system(size: 10.5, weight: .heavy))
                    .tracking(1.4)
                    .foregroundStyle(CyberGlassColors.mint)
                Text(sourceLine)
                    .font(.system(size: 11))
                    .foregroundStyle(CyberGlassColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            Button { router.showBrainHUD.toggle() } label: {
                Image(systemName: "brain.head.profile")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(router.showBrainHUD ? CyberGlassColors.mint : CyberGlassColors.textSilver)
                    .frame(width: 40, height: 40)
            }
            .accessibilityLabel("Smart Shuffle brain HUD")
        }
        .foregroundStyle(CyberGlassColors.textSilver)
    }

    private var sourceLine: String {
        switch player.shuffleMode {
        case .smart: return "Smart Shuffle • \(player.queue.count) in queue"
        case .random: return "Shuffled • \(player.currentIndex + 1) of \(player.queue.count)"
        case .off: return "\(player.currentIndex + 1) of \(player.queue.count)"
        }
    }

    private var songInfo: some View {
        VStack(spacing: 8) {
            Text(player.currentTrack?.title ?? "")
                .font(.system(size: 24, weight: .heavy))
                .foregroundStyle(CyberGlassColors.textPrimary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
            Text(artistLine)
                .font(.system(size: 15))
                .foregroundStyle(CyberGlassColors.textSecondary)
                .lineLimit(1)
            HStack(spacing: 6) {
                DevicePill(text: player.route.badge)
                if let track = player.currentTrack {
                    InfoChip(text: track.qualityLabel, accent: track.lossless ? CyberGlassColors.mint : CyberGlassColors.sky)
                }
                if let id = player.currentTrack?.id, let decision = player.decisions[id] {
                    InfoChip(text: "VIBE \(decision.vibeMatchPct)%", accent: CyberGlassColors.violet)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var artistLine: String {
        guard let track = player.currentTrack else { return "" }
        return track.album.isEmpty ? track.artist : "\(track.artist) • \(track.album)"
    }

    private var seekBar: some View {
        let duration = max(player.duration, 0.1)
        let position = scrubbing ?? player.position
        return VStack(spacing: 6) {
            GeometryReader { geo in
                let fraction = CGFloat(max(0, min(1, position / duration)))
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.1)).frame(height: 5)
                    Capsule().fill(CyberGlassColors.progress).frame(width: geo.size.width * fraction, height: 5)
                        .shadow(color: CyberGlassColors.cyan.opacity(0.6), radius: 6)
                    Circle()
                        .fill(Color.white)
                        .frame(width: scrubbing == nil ? 12 : 18, height: scrubbing == nil ? 12 : 18)
                        .shadow(color: CyberGlassColors.cyan, radius: 8)
                        .offset(x: geo.size.width * fraction - (scrubbing == nil ? 6 : 9))
                }
                .frame(height: 24)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        scrubbing = Double(max(0, min(1, value.location.x / geo.size.width))) * duration
                    }
                    .onEnded { _ in
                        if let target = scrubbing { player.seek(to: target) }
                        scrubbing = nil
                    })
            }
            .frame(height: 24)
            HStack {
                Text(Self.time(position))
                Spacer()
                Text("-" + Self.time(max(0, duration - position)))
            }
            .font(.system(size: 11, weight: .semibold, design: .monospaced))
            .foregroundStyle(CyberGlassColors.textSecondary)
        }
        .accessibilityElement()
        .accessibilityLabel("Position")
        .accessibilityValue("\(Self.time(position)) of \(Self.time(duration))")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: player.seek(to: player.position + 10)
            case .decrement: player.seek(to: player.position - 10)
            @unknown default: break
            }
        }
    }

    private static func time(_ seconds: Double) -> String {
        let total = Int(seconds.rounded(.down))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    private var transport: some View {
        HStack {
            Menu {
                Picker("Shuffle", selection: Binding(get: { player.shuffleMode }, set: { player.setShuffleMode($0) })) {
                    Label("Off", systemImage: "arrow.right").tag(ShuffleMode.off)
                    Label("Random", systemImage: "shuffle").tag(ShuffleMode.random)
                    Label("Smart Shuffle", systemImage: "sparkles").tag(ShuffleMode.smart)
                }
            } label: {
                Image(systemName: player.shuffleMode == .smart ? "sparkles" : "shuffle")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(player.shuffleMode == .off ? CyberGlassColors.textQuiet : CyberGlassColors.cyan)
                    .shadow(color: player.shuffleMode == .off ? .clear : CyberGlassColors.cyan.opacity(0.7), radius: 6)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Shuffle: \(player.shuffleMode.rawValue)")
            Spacer()
            TransportButton(systemImage: "backward.fill", label: "Previous", size: 24) { player.skipToPrevious() }
            Spacer()
            Button { player.togglePlayPause() } label: {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 30, weight: .heavy))
                    .foregroundStyle(CyberGlassColors.obsidian)
                    .frame(width: 78, height: 78)
                    .background(CyberGlassColors.selectedChip, in: Circle())
                    .shadow(color: CyberGlassColors.cyan.opacity(0.6), radius: 18)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
            Spacer()
            TransportButton(systemImage: "forward.fill", label: "Next", size: 24) { player.skipToNext() }
            Spacer()
            Button { player.cycleRepeat() } label: {
                Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(player.repeatMode == .off ? CyberGlassColors.textQuiet : CyberGlassColors.cyan)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Repeat: \(player.repeatMode.rawValue)")
        }
    }

    private var bottomActions: some View {
        HStack(spacing: 10) {
            ActionChip(icon: "slider.vertical.3", title: player.equalizer.auto ? "EQ • AUTO" : "EQ") { showEqualizer = true }
            ActionChip(icon: "list.bullet", title: "Queue") { showQueue = true }
            if player.shuffleMode == .smart {
                ActionChip(icon: "arrow.triangle.2.circlepath", title: "Regenerate") { player.regenerateSmartQueue() }
            }
        }
    }
}

private struct ActionChip: View {
    let icon: String
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 13, weight: .bold))
                Text(title).font(.system(size: 13, weight: .bold))
            }
            .foregroundStyle(CyberGlassColors.cyan)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(CyberGlassColors.chip.opacity(0.85), in: Capsule())
            .overlay(Capsule().strokeBorder(CyberGlassColors.cyan.opacity(0.35), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// The queue: what played, what plays, what's next (drag to reorder, swipe to remove).
struct QueueSheet: View {
    @ObservedObject private var player = HarmonyAudioController.shared
    @Environment(\.dismiss) private var dismiss
    @State private var showHistory = false

    var body: some View {
        NavigationStack {
            List {
                if player.currentIndex > 0 {
                    Section {
                        if showHistory {
                            ForEach(0..<player.currentIndex, id: \.self) { index in
                                row(player.queue[index], dimmed: true)
                                    .onTapGesture { player.playQueueItem(at: index) }
                            }
                        }
                    } header: {
                        Button(showHistory ? "Hide history" : "Show history (\(player.currentIndex))") { showHistory.toggle() }
                            .font(.system(size: 12, weight: .semibold))
                    }
                }
                if let current = player.currentEntry {
                    Section("Now playing") {
                        row(current, dimmed: false).listRowBackground(CyberGlassColors.cyan.opacity(0.08))
                    }
                }
                Section {
                    let start = player.currentIndex + 1
                    ForEach(Array(player.upcoming)) { entry in
                        row(entry, dimmed: false)
                            .onTapGesture {
                                if let index = player.queue.firstIndex(where: { $0.token == entry.token }) {
                                    player.playQueueItem(at: index)
                                }
                            }
                    }
                    .onDelete { offsets in
                        for offset in offsets.sorted(by: >) { player.removeFromQueue(at: start + offset) }
                    }
                    .onMove { source, destination in
                        guard let first = source.first else { return }
                        player.moveInQueue(from: start + first, to: start + destination)
                    }
                } header: {
                    HStack {
                        Text("Up next • \(player.upcoming.count)")
                        Spacer()
                        if !player.upcoming.isEmpty {
                            Button("Clear") { player.clearUpcoming() }.font(.system(size: 12, weight: .semibold))
                        }
                    }
                } footer: {
                    if player.shuffleMode == .smart {
                        Text("Smart Shuffle adds songs at the end as you listen. Skipping never rewrites what is already queued.")
                    }
                }
            }
            .navigationTitle("Queue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { EditButton() }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func row(_ entry: QueueEntry, dimmed: Bool) -> some View {
        HStack(spacing: 12) {
            Artwork(url: entry.track.artworkURL, size: 42)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.track.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                if let decision = player.decisions[entry.track.id], entry.generated {
                    Text(decision.reasonTag).font(.system(size: 11, design: .monospaced)).foregroundStyle(CyberGlassColors.cyan).lineLimit(1)
                } else {
                    Text(entry.userQueued ? "\(entry.track.artist) • queued by you" : entry.track.artist)
                        .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if entry.generated {
                Image(systemName: "sparkles").font(.system(size: 11)).foregroundStyle(CyberGlassColors.violet)
            }
        }
        .opacity(dimmed ? 0.55 : 1)
        .contentShape(Rectangle())
    }
}
