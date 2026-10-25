import SwiftUI

/**
 * The Smart Shuffle Diagnostic Brain, over Now Playing: the session vibe
 * S_t as 24 live bars, a light per self-audit (green passed, amber repaired,
 * red could not be repaired), the last listening event, why each upcoming
 * song was picked, and the 100-song synthetic self-test.
 */
struct ShuffleBrainDebugOverlay: View {
    @ObservedObject private var player = HarmonyAudioController.shared
    @Binding var visible: Bool

    @State private var session: [Float] = []
    @State private var hasSession = false
    @State private var selfTest: HarmonyEngineCheckResult?
    @State private var testing = false
    @State private var showReport = false

    private static let dimensionGroups: [(String, Range<Int>, Color)] = [
        ("SPECTRUM", 0..<10, CyberGlassColors.cyan),
        ("GROOVE", 10..<20, CyberGlassColors.violet),
        ("MACRO", 20..<24, CyberGlassColors.mint),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "brain.head.profile").foregroundStyle(CyberGlassColors.mint)
                Text("DIAGNOSTIC BRAIN")
                    .font(.system(size: 12, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                Spacer()
                Text(eventText)
                    .font(.system(size: 9.5, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.amber)
                Button { visible = false } label: {
                    Image(systemName: "xmark").font(.system(size: 11, weight: .bold)).foregroundStyle(CyberGlassColors.textSecondary)
                }
                .accessibilityLabel("Close the brain HUD")
            }
            sessionBars
            auditLights
            upcoming
            selfTestRow
        }
        .padding(14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(CyberGlassColors.mint.opacity(0.4), lineWidth: 1))
        .environment(\.colorScheme, .dark)
        .task {
            // S_t lives in the brain's actor: read it twice a second while the HUD is open.
            while !Task.isCancelled {
                session = await player.brain.sessionVector
                hasSession = await player.brain.hasSession
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    private var eventText: String {
        switch player.lastEvent {
        case .seeded: return "SEEDED"
        case .playEma: return "PLAY → S_t PULLED"
        case .fastSkip: return "FAST SKIP → REPELLED"
        case .manualAnchor: return "HAND PICK → ANCHORED"
        case .neutral: return "HELD"
        default: return player.shuffleMode == .smart ? "LISTENING" : "SMART SHUFFLE OFF"
        }
    }

    private var sessionBars: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(hasSession ? "S_t • SESSION VIBE (24-D)" : "S_t • NO SESSION YET")
                .font(.system(size: 9, weight: .heavy))
                .foregroundStyle(CyberGlassColors.textQuiet)
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<24, id: \.self) { d in
                    let value = d < session.count ? CGFloat(session[d]) : 0
                    let color = Self.dimensionGroups.first { $0.1.contains(d) }?.2 ?? CyberGlassColors.cyan
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        // S_t is unit length and signed: positive grows up, negative shows dimmer.
                        Capsule()
                            .fill(color.opacity(value >= 0 ? 0.95 : 0.4))
                            .frame(height: max(2, min(1, abs(value) * 2.2) * 42))
                            .shadow(color: color.opacity(0.6), radius: 3)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 44)
            .animation(.easeOut(duration: 0.35), value: session)
            HStack {
                ForEach(Self.dimensionGroups.indices, id: \.self) { index in
                    Text(Self.dimensionGroups[index].0)
                        .font(.system(size: 8, weight: .heavy, design: .monospaced))
                        .foregroundStyle(Self.dimensionGroups[index].2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private var auditLights: some View {
        let lines = player.lastAudit?.lines ?? []
        return VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text("SELF-AUDIT").font(.system(size: 9, weight: .heavy)).foregroundStyle(CyberGlassColors.textQuiet)
                Spacer()
                if let audit = player.lastAudit {
                    Text(String(format: "#%llu • %.2f ms • %.0f%% REAL VECTORS", audit.generation, audit.elapsedMs, audit.realVectorPct))
                        .font(.system(size: 8.5, weight: .bold, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.textQuiet)
                }
            }
            if lines.isEmpty {
                Text("The first Smart Shuffle queue runs the five checks.")
                    .font(.system(size: 11))
                    .foregroundStyle(CyberGlassColors.textSecondary)
            }
            ForEach(lines, id: \.name) { line in
                HStack(spacing: 8) {
                    Circle()
                        .fill(Self.statusColor(line.status))
                        .frame(width: 8, height: 8)
                        .shadow(color: Self.statusColor(line.status), radius: 4)
                    Text(line.name.uppercased())
                        .font(.system(size: 9.5, weight: .heavy, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.textSilver)
                        .frame(width: 128, alignment: .leading)
                    Text(line.detail)
                        .font(.system(size: 9.5, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.textSecondary)
                        .lineLimit(1)
                }
            }
        }
    }

    private var upcoming: some View {
        let picks = player.upcoming.prefix(5).compactMap { entry -> Pick? in
            player.decisions[entry.track.id].map { Pick(entry: entry, decision: $0) }
        }
        return VStack(alignment: .leading, spacing: 5) {
            if !picks.isEmpty {
                Text("WHY THESE ARE NEXT").font(.system(size: 9, weight: .heavy)).foregroundStyle(CyberGlassColors.textQuiet)
            }
            ForEach(picks) { pick in
                let entry = pick.entry
                let decision = pick.decision
                HStack(spacing: 8) {
                    Text("\(decision.vibeMatchPct)%")
                        .font(.system(size: 10, weight: .heavy, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.mint)
                        .frame(width: 34, alignment: .trailing)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(entry.track.title)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(CyberGlassColors.textPrimary)
                            .lineLimit(1)
                        Text(decision.reasonTag)
                            .font(.system(size: 9.5, design: .monospaced))
                            .foregroundStyle(CyberGlassColors.cyan)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    MixBar(decision: decision).frame(width: 60, height: 6)
                }
            }
        }
    }

    private var selfTestRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Button {
                    guard !testing else { return }
                    testing = true
                    Task {
                        let result = await Task.detached(priority: .userInitiated) { HarmonyEngineCheck.runShuffleBrainCheck() }.value
                        selfTest = result
                        testing = false
                    }
                } label: {
                    HStack(spacing: 6) {
                        if testing { ProgressView().scaleEffect(0.6).tint(CyberGlassColors.mint) }
                        Text(testing ? "RUNNING…" : "RUN SELF-TEST").font(.system(size: 10.5, weight: .heavy, design: .monospaced))
                    }
                    .foregroundStyle(CyberGlassColors.obsidian)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(CyberGlassColors.selectedChip, in: Capsule())
                }
                .buttonStyle(.plain)
                if let selfTest {
                    Text(selfTest.passed ? "PASS" : "FAIL")
                        .font(.system(size: 11, weight: .heavy, design: .monospaced))
                        .foregroundStyle(selfTest.passed ? CyberGlassColors.mint : CyberGlassColors.danger)
                    Button(showReport ? "Hide" : "Report") { showReport.toggle() }
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(CyberGlassColors.cyan)
                }
                Spacer()
            }
            if showReport, let selfTest {
                ScrollView {
                    Text(selfTest.report)
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.textSilver)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .frame(maxHeight: 160)
            }
        }
    }

    private static func statusColor(_ status: Int) -> Color {
        switch status {
        case 0: return CyberGlassColors.mint
        case 1: return CyberGlassColors.amber
        default: return CyberGlassColors.danger
        }
    }
}

/// An upcoming song the brain picked, with why.
private struct Pick: Identifiable {
    let entry: QueueEntry
    let decision: ShuffleDecision
    var id: Int64 { entry.id }
}

/// Vibe, habit, affinity and exploration shares of a pick, side by side.
private struct MixBar: View {
    let decision: ShuffleDecision

    var body: some View {
        GeometryReader { geo in
            let total = max(1, decision.vibePct + decision.markovPct + decision.affinityPct + decision.explorationPct)
            HStack(spacing: 0) {
                Rectangle().fill(CyberGlassColors.cyan).frame(width: geo.size.width * CGFloat(decision.vibePct / total))
                Rectangle().fill(CyberGlassColors.violet).frame(width: geo.size.width * CGFloat(decision.markovPct / total))
                Rectangle().fill(CyberGlassColors.mint).frame(width: geo.size.width * CGFloat(decision.affinityPct / total))
                Rectangle().fill(CyberGlassColors.amber).frame(width: geo.size.width * CGFloat(decision.explorationPct / total))
            }
            .clipShape(Capsule())
        }
        .accessibilityLabel(String(format: "Vibe %.0f%%, habit %.0f%%, affinity %.0f%%, exploration %.0f%%",
                                   decision.vibePct, decision.markovPct, decision.affinityPct, decision.explorationPct))
    }
}
