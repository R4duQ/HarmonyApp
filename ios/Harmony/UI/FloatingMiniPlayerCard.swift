import SwiftUI
import UIKit

/**
 * The floating mini player above the navigation pill: a smoked-glass card
 * with the record half out of its left edge (the cover as its label, a neon
 * ring around it, turning while the music plays), where the music plays,
 * the song, a live telemetry line, the neon progress line, a small live
 * waveform, the transport and the next song.
 *
 * Tap or swipe up: Now Playing. Swipe sideways: next or previous.
 */
struct FloatingMiniPlayerCard: View {
    @ObservedObject private var player = HarmonyAudioController.shared
    @ObservedObject private var router = HarmonyRouter.shared

    private let discSize: CGFloat = 70

    var body: some View {
        ZStack(alignment: .leading) {
            card
                .padding(.leading, discSize * 0.5)
            VinylDisc(artworkURL: player.currentTrack?.artworkURL, spinning: player.isPlaying, size: discSize)
                .offset(x: -4)
                .shadow(color: CyberGlassColors.violet.opacity(0.45), radius: 12)
        }
        .contentShape(Rectangle())
        .onTapGesture { router.showNowPlaying = true }
        .gesture(
            DragGesture(minimumDistance: 24)
                .onEnded { value in
                    if value.translation.height < -40, abs(value.translation.width) < 60 {
                        router.showNowPlaying = true
                    } else if value.translation.width < -70 {
                        player.skipToNext()
                    } else if value.translation.width > 70 {
                        player.skipToPrevious()
                    }
                }
        )
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Open Now Playing") { router.showNowPlaying = true }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                DevicePill(text: player.route.badge)
                Spacer(minLength: 4)
                MiniWaveform(active: player.isPlaying)
                    .frame(width: 34, height: 14)
            }
            Text(player.currentTrack?.title ?? "")
                .font(.system(size: 14.5, weight: .bold))
                .foregroundStyle(CyberGlassColors.textPrimary)
                .lineLimit(1)
            Text(telemetry)
                .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(CyberGlassColors.cyan.opacity(0.9))
                .lineLimit(1)
            NeonProgressBar(fraction: player.duration > 0 ? player.position / player.duration : 0, height: 3)
                .padding(.vertical, 2)
            HStack(spacing: 14) {
                TransportButton(systemImage: "backward.fill", label: "Previous") { player.skipToPrevious() }
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 15, weight: .heavy))
                        .foregroundStyle(CyberGlassColors.obsidian)
                        .frame(width: 34, height: 34)
                        .background(CyberGlassColors.selectedChip, in: Circle())
                        .shadow(color: CyberGlassColors.cyan.opacity(0.6), radius: 8)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
                TransportButton(systemImage: "forward.fill", label: "Next") { player.skipToNext() }
                Spacer(minLength: 0)
                if let next = player.upcoming.first {
                    UpNextCapsule(title: next.track.title)
                        .onTapGesture { player.playQueueItem(at: player.currentIndex + 1) }
                }
            }
        }
        .padding(.leading, discSize * 0.5 + 10)
        .padding(.trailing, 14)
        .padding(.vertical, 11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .background(CyberGlassColors.obsidian.opacity(0.55), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(CyberGlassColors.iridescentSoft, lineWidth: 1))
        .shadow(color: .black.opacity(0.5), radius: 16, y: 6)
        .environment(\.colorScheme, .dark)
    }

    /// "LIVE SPECTRAL CALIBRATION • VIBE MATCH 94%", or the format.
    private var telemetry: String {
        var parts: [String] = []
        if player.equalizer.enabled {
            parts.append(player.equalizer.auto ? "LIVE SPECTRAL CALIBRATION" : "EQ ON")
        }
        if let id = player.currentTrack?.id, let decision = player.decisions[id] {
            parts.append("VIBE MATCH \(decision.vibeMatchPct)%")
        } else if let track = player.currentTrack {
            parts.append(track.qualityLabel.uppercased())
        }
        return parts.joined(separator: " • ")
    }
}

/// A record: grooves, the cover as its label, a spindle hole and a neon ring.
/// It turns at 33⅓ rpm while [spinning].
struct VinylDisc: View {
    let artworkURL: URL?
    let spinning: Bool
    let size: CGFloat

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !spinning)) { context in
            let degrees = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.8) / 1.8 * 360
            disc.rotationEffect(.degrees(spinning ? degrees : 0))
        }
        .frame(width: size, height: size)
        .overlay(
            Circle()
                .strokeBorder(CyberGlassColors.iridescentSweep, lineWidth: 2.5)
                .shadow(color: CyberGlassColors.cyan.opacity(0.7), radius: 6)
        )
        .accessibilityHidden(true)
    }

    private var disc: some View {
        ZStack {
            Circle().fill(Color(hex: 0x07090E))
            ForEach(0..<6, id: \.self) { ring in
                Circle()
                    .stroke(Color.white.opacity(0.05 + Double(ring % 2) * 0.03), lineWidth: 0.7)
                    .padding(size * (0.06 + CGFloat(ring) * 0.035))
            }
            Group {
                if let image = ArtworkCache.shared.image(for: artworkURL, maxPixels: size * 2) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    NeonArtPlaceholder()
                }
            }
            .frame(width: size * 0.5, height: size * 0.5)
            .clipShape(Circle())
            Circle().fill(Color(hex: 0x07090E)).frame(width: size * 0.07, height: size * 0.07)
            // The sheen stays still while the record turns under it.
        }
    }
}

/// Five capsules dancing while music plays; still when paused.
struct MiniWaveform: View {
    let active: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !active)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(alignment: .center, spacing: 2.5) {
                ForEach(0..<5, id: \.self) { i in
                    let phase = Double(i) * 1.3
                    let level = active ? 0.35 + 0.65 * abs(sin(t * (3.1 + Double(i) * 0.7) + phase)) : 0.25
                    Capsule()
                        .fill(CyberGlassColors.progress)
                        .frame(width: 3, height: max(3, 14 * level))
                }
            }
            .frame(maxHeight: .infinity)
        }
        .accessibilityHidden(true)
    }
}

/// "PHONE SPEAKER", "BLUETOOTH • AirPods", "HI-RES USB DAC • 96kHz".
struct DevicePill: View {
    let text: String

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 8.5, weight: .bold))
            Text(text).font(.system(size: 8.5, weight: .heavy, design: .monospaced)).lineLimit(1)
        }
        .foregroundStyle(CyberGlassColors.sky)
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(CyberGlassColors.devicePill, in: Capsule())
        .overlay(Capsule().strokeBorder(CyberGlassColors.sky.opacity(0.3), lineWidth: 0.8))
    }

    private var icon: String {
        if text.hasPrefix("BLUETOOTH") { return "headphones" }
        if text.contains("USB") { return "cable.connector" }
        if text.hasPrefix("AIRPLAY") { return "airplayaudio" }
        if text.hasPrefix("CARPLAY") { return "car.fill" }
        if text.hasPrefix("WIRED") { return "headphones" }
        return "iphone"
    }
}

/// The recessed "Up next" capsule.
struct UpNextCapsule: View {
    let title: String

    var body: some View {
        HStack(spacing: 4) {
            Text("NEXT").font(.system(size: 8, weight: .heavy)).foregroundStyle(CyberGlassColors.violet)
            Text(title).font(.system(size: 10.5, weight: .semibold)).foregroundStyle(CyberGlassColors.textSilver).lineLimit(1)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .frame(maxWidth: 130, alignment: .leading)
        .background(CyberGlassColors.recessed, in: Capsule())
        .overlay(Capsule().strokeBorder(Color.black.opacity(0.4), lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Up next: \(title)")
    }
}

/// A plain glowing transport icon.
struct TransportButton: View {
    let systemImage: String
    let label: String
    var size: CGFloat = 15
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: size, weight: .bold))
                .foregroundStyle(CyberGlassColors.textSilver)
                .frame(width: size * 2, height: size * 2)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
