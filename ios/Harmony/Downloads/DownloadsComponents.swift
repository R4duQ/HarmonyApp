import SwiftUI

// Cyber-Glass building blocks for the Downloads screen (the same tokens as
// the Android app's dark theme: obsidian, electric cyan, neon violet).

/// A smoked-glass card with an iridescent rim.
struct CyberCard<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(CyberGlassColors.card.opacity(CyberGlassColors.cardAlpha), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(CyberGlassColors.iridescentSoft, lineWidth: 1))
    }
}

/// The glowing gradient capsule button.
struct NeonButton: View {
    let title: String
    var systemImage: String?
    var prominent = true
    var disabled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 14, weight: .bold)) }
                Text(title).font(.system(size: 14, weight: .bold)).lineLimit(1)
            }
            .foregroundStyle(prominent ? CyberGlassColors.obsidian : CyberGlassColors.cyan)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background {
                if prominent {
                    Capsule().fill(CyberGlassColors.selectedChip)
                } else {
                    Capsule().fill(CyberGlassColors.chip)
                }
            }
            .overlay(Capsule().strokeBorder(prominent ? Color.clear : CyberGlassColors.cyan.opacity(0.5), lineWidth: 1))
            .shadow(color: CyberGlassColors.cyan.opacity(prominent && !disabled ? 0.45 : 0), radius: 10)
            .opacity(disabled ? 0.45 : 1)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }
}

/// Filter capsules: the selected one glows cyan-mint with dark bold text.
struct CapsuleTabs<Tab: Hashable>: View {
    let tabs: [(Tab, String, String)]  // value, title, SF Symbol
    @Binding var selection: Tab

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(tabs.indices, id: \.self) { index in
                    let tab = tabs[index]
                    let selected = tab.0 == selection
                    Button {
                        withAnimation(.spring(response: 0.32, dampingFraction: 0.8)) { selection = tab.0 }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: tab.2).font(.system(size: 12, weight: .bold))
                            Text(tab.1).font(.system(size: 13, weight: selected ? .bold : .medium))
                        }
                        .foregroundStyle(selected ? CyberGlassColors.obsidian : CyberGlassColors.textSilver)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background {
                            if selected {
                                Capsule().fill(CyberGlassColors.selectedChip)
                            } else {
                                Capsule().fill(CyberGlassColors.chip)
                            }
                        }
                        .overlay(Capsule().strokeBorder(selected ? Color.clear : CyberGlassColors.chipBorder, lineWidth: 1))
                        .shadow(color: CyberGlassColors.cyan.opacity(selected ? 0.5 : 0), radius: 9)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 2)
        }
    }
}

/// A dark-glass text field with the violet gradient rim.
struct GlassField: View {
    let placeholder: String
    @Binding var text: String
    var systemImage = "magnifyingglass"
    var secure = false
    var onSubmit: () -> Void = {}

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(CyberGlassColors.textPlaceholder)
            Group {
                if secure {
                    SecureField("", text: $text, prompt: Text(placeholder).foregroundColor(CyberGlassColors.textQuiet))
                } else {
                    TextField("", text: $text, prompt: Text(placeholder).foregroundColor(CyberGlassColors.textQuiet))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            }
            .font(.system(size: 15))
            .foregroundStyle(CyberGlassColors.textPrimary)
            .tint(CyberGlassColors.cyan)
            .submitLabel(.go)
            .onSubmit(onSubmit)
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(CyberGlassColors.textQuiet)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(CyberGlassColors.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(CyberGlassColors.searchRim, lineWidth: 1))
    }
}

/// A small quality or info chip ("FLAC 24/96", "320 kbps", "3:41").
struct InfoChip: View {
    let text: String
    var accent: Color = CyberGlassColors.sky

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold, design: .monospaced))
            .foregroundStyle(accent)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(accent.opacity(0.12), in: Capsule())
            .overlay(Capsule().strokeBorder(accent.opacity(0.3), lineWidth: 1))
    }
}

/// The neon progress line with a laser spark at its head.
struct NeonProgressBar: View {
    let fraction: Double
    var height: CGFloat = 5

    var body: some View {
        GeometryReader { geo in
            let width = max(0, min(1, fraction)) * geo.size.width
            ZStack(alignment: .leading) {
                Capsule().fill(CyberGlassColors.sky.opacity(0.12))
                Capsule().fill(CyberGlassColors.progress).frame(width: width)
                if fraction > 0, fraction < 1 {
                    Circle()
                        .fill(Color.white)
                        .frame(width: height + 3, height: height + 3)
                        .shadow(color: CyberGlassColors.cyan, radius: 6)
                        .offset(x: max(0, width - (height + 3) / 2))
                }
            }
        }
        .frame(height: height)
    }
}

/// One download: title, artist and source, format, the laser progress line,
/// live speed, its controls, and the 24-D brain badge once indexed.
struct DownloadItemCard: View {
    let item: DownloadItem
    var onPause: () -> Void = {}
    var onResume: () -> Void = {}
    var onRetry: () -> Void = {}
    var onCancel: () -> Void = {}

    private var failed: Bool { item.status == .failed }
    private var live: Bool { item.status.isActive || item.status == .paused }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous).fill(CyberGlassColors.artWell)
                    Image(systemName: icon)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(failed ? AnyShapeStyle(CyberGlassColors.danger) : AnyShapeStyle(CyberGlassColors.iridescent))
                }
                .frame(width: 44, height: 44)
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(CyberGlassColors.thumbRim, lineWidth: 1))
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title).font(.system(size: 15, weight: .bold)).foregroundStyle(CyberGlassColors.textPrimary).lineLimit(1)
                    Text([item.artist, item.source].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.system(size: 12)).foregroundStyle(CyberGlassColors.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                FormatBadge(text: item.formatBadge)
            }
            if live { NeonProgressBar(fraction: item.progress) }
            HStack(spacing: 8) {
                Text(statusLine)
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(0.9)
                    .foregroundStyle(failed ? CyberGlassColors.danger : CyberGlassColors.cyan.opacity(0.85))
                if item.bytesPerSecond > 0 {
                    Text(Formatters.speed(item.bytesPerSecond))
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.sky)
                }
                Spacer()
                if item.indexed {
                    BrainBadge(text: "24D Brain Indexed ✔", glowing: false)
                } else if item.status == .indexing24D {
                    BrainBadge(text: "24D…", glowing: true)
                }
            }
            if let detail = item.detail, !detail.isEmpty {
                Text(detail).font(.system(size: 12))
                    .foregroundStyle(failed ? CyberGlassColors.danger.opacity(0.9) : CyberGlassColors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if item.canPause || item.canResume || item.canRetry || item.canCancel {
                HStack(spacing: 8) {
                    if item.canPause { ControlPill(title: "Pause", systemImage: "pause.fill", action: onPause) }
                    if item.canResume { ControlPill(title: "Resume", systemImage: "play.fill", prominent: true, action: onResume) }
                    if item.canRetry { ControlPill(title: "Retry", systemImage: "arrow.clockwise", prominent: true, action: onRetry) }
                    Spacer()
                    if item.canCancel { ControlPill(title: "Cancel", systemImage: "xmark", destructive: true, action: onCancel) }
                }
            }
        }
        .padding(14)
        .background(CyberGlassColors.card.opacity(CyberGlassColors.cardAlpha), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(
            item.status.isActive ? AnyShapeStyle(CyberGlassColors.iridescentSoft) : AnyShapeStyle(CyberGlassColors.chipBorder), lineWidth: 1))
    }

    private var icon: String {
        switch item.status {
        case .failed: return "exclamationmark.triangle.fill"
        case .completed: return item.indexed ? "checkmark.seal.fill" : "checkmark.circle.fill"
        case .paused: return "pause.circle.fill"
        case .searching: return "magnifyingglass"
        default: return "waveform"
        }
    }

    /// "DOWNLOADING • 42%", "QUEUED", "COMPLETED".
    private var statusLine: String {
        let name = item.status.rawValue.uppercased()
        switch item.status {
        case .downloading, .paused:
            return item.totalBytes > 0 ? "\(name) • \(item.progressPct)%" : name
        default:
            return name
        }
    }
}

/// "FLAC", "HI-RES", "MP3 320" in a small glowing capsule.
struct FormatBadge: View {
    let text: String

    private var accent: Color {
        switch text {
        case "HI-RES": return CyberGlassColors.violet
        case "FLAC", "ALAC", "WAV", "AIFF": return CyberGlassColors.mint
        default: return CyberGlassColors.sky
        }
    }

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .heavy, design: .monospaced))
            .foregroundStyle(accent)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(accent.opacity(0.14), in: Capsule())
            .overlay(Capsule().strokeBorder(accent.opacity(0.45), lineWidth: 1))
            .shadow(color: accent.opacity(0.35), radius: 5)
    }
}

/// The mint "24D Brain Indexed ✔" capsule.
struct BrainBadge: View {
    let text: String
    let glowing: Bool

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "brain.head.profile").font(.system(size: 10, weight: .bold))
            Text(text).font(.system(size: 10, weight: .heavy)).tracking(0.4)
        }
        .foregroundStyle(CyberGlassColors.obsidian)
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(CyberGlassColors.selectedChip, in: Capsule())
        .shadow(color: CyberGlassColors.mint.opacity(0.5), radius: glowing ? 8 : 4)
    }
}

/// A small transfer control: Pause, Resume, Retry, Cancel.
struct ControlPill: View {
    let title: String
    let systemImage: String
    var prominent = false
    var destructive = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: systemImage).font(.system(size: 11, weight: .bold))
                Text(title).font(.system(size: 12, weight: .bold))
            }
            .foregroundStyle(prominent ? CyberGlassColors.obsidian : destructive ? CyberGlassColors.danger : CyberGlassColors.cyan)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background {
                if prominent {
                    Capsule().fill(CyberGlassColors.selectedChip)
                } else {
                    Capsule().fill(CyberGlassColors.recessed)
                }
            }
            .overlay(Capsule().strokeBorder(prominent ? Color.clear : (destructive ? CyberGlassColors.danger : CyberGlassColors.cyan).opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

/// The neon "BACKGROUND GUARD: ACTIVE • SCREEN-LOCK SAFE" strip, with a pulsing dot.
struct GuardBadge: View {
    let text: String
    let detail: String
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(CyberGlassColors.mint)
                .frame(width: 8, height: 8)
                .shadow(color: CyberGlassColors.mint, radius: pulse ? 8 : 2)
                .scaleEffect(pulse ? 1.25 : 0.9)
                .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: pulse)
            VStack(alignment: .leading, spacing: 2) {
                Text(text)
                    .font(.system(size: 10.5, weight: .heavy, design: .monospaced))
                    .tracking(0.6)
                    .foregroundStyle(CyberGlassColors.mint)
                Text(detail)
                    .font(.system(size: 10.5))
                    .foregroundStyle(CyberGlassColors.textSecondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "lock.shield.fill").foregroundStyle(CyberGlassColors.mint.opacity(0.8))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(CyberGlassColors.mint.opacity(0.08), in: Capsule())
        .overlay(Capsule().strokeBorder(CyberGlassColors.mint.opacity(0.45), lineWidth: 1))
        .shadow(color: CyberGlassColors.mint.opacity(0.25), radius: 10)
        .onAppear { pulse = true }
    }
}

/// A section heading in the Cyber-Glass style.
struct SectionHeading: View {
    let title: String
    var trailing: String?

    var body: some View {
        HStack {
            Text(title.uppercased())
                .font(.system(size: 12, weight: .heavy))
                .tracking(1.4)
                .foregroundStyle(CyberGlassColors.textSecondary)
            Spacer()
            if let trailing {
                Text(trailing).font(.system(size: 11, weight: .medium, design: .monospaced)).foregroundStyle(CyberGlassColors.textQuiet)
            }
        }
        .padding(.top, 4)
    }
}

enum Formatters {
    static func duration(seconds: Int) -> String {
        guard seconds > 0 else { return "" }
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    static func megabytes(_ bytes: UInt64) -> String {
        String(format: "%.1f MB", Double(bytes) / 1_000_000)
    }

    /// "3.4 MB/s", "820 KB/s".
    static func speed(_ bytesPerSecond: Double) -> String {
        bytesPerSecond >= 1_000_000 ? String(format: "%.1f MB/s", bytesPerSecond / 1_000_000)
            : String(format: "%.0f KB/s", bytesPerSecond / 1000)
    }
}
