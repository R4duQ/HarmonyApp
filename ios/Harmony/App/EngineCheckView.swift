import SwiftUI

/// Runs the shared C++ engines on this iPhone and shows their verdicts.
@MainActor
final class EngineCheckModel: ObservableObject {
    enum Phase { case idle, running, done }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var results: [HarmonyEngineCheckResult] = []

    var allPassed: Bool { !results.isEmpty && results.allSatisfy(\.passed) }

    func run() {
        guard phase != .running else { return }
        phase = .running
        results = []
        Task.detached(priority: .userInitiated) {
            let eq = HarmonyEngineCheck.runEqualizerCheck()
            await MainActor.run { self.results.append(eq) }
            let brain = HarmonyEngineCheck.runShuffleBrainCheck()
            await MainActor.run {
                self.results.append(brain)
                self.phase = .done
            }
        }
    }
}

struct EngineCheckView: View {
    @StateObject private var model = EngineCheckModel()

    var body: some View {
        ZStack {
            CyberGlassColors.page.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    verdict
                    ForEach(Array(model.results.enumerated()), id: \.offset) { _, result in
                        ResultCard(result: result)
                    }
                    Button(action: model.run) {
                        Text(model.phase == .running ? "RUNNING…" : "RUN AGAIN")
                            .font(.system(size: 14, weight: .bold))
                            .tracking(1.2)
                            .foregroundStyle(CyberGlassColors.obsidian)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(CyberGlassColors.selectedChip, in: Capsule())
                            .shadow(color: CyberGlassColors.cyan.opacity(0.45), radius: 12)
                    }
                    .disabled(model.phase == .running)
                    .padding(.top, 4)
                }
                .padding(20)
            }
        }
        .onAppear { if model.phase == .idle { model.run() } }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("HARMONY")
                .font(.system(size: 30, weight: .heavy))
                .tracking(4)
                .foregroundStyle(CyberGlassColors.iridescent)
            Text("ENGINE CHECK • SHARED C++ CORE ON iOS")
                .font(.system(size: 11, weight: .semibold))
                .tracking(1.1)
                .foregroundStyle(CyberGlassColors.cyan.opacity(0.8))
        }
        .padding(.top, 12)
    }

    @ViewBuilder private var verdict: some View {
        let (text, color): (String, Color) = switch model.phase {
        case .idle, .running: ("Running the engines on this iPhone…", CyberGlassColors.textSecondary)
        case .done: model.allPassed
            ? ("All checks passed. The audio and shuffle engines run natively here.", CyberGlassColors.mint)
            : ("A check failed. See the report below.", CyberGlassColors.danger)
        }
        HStack(spacing: 10) {
            if model.phase == .running { ProgressView().tint(CyberGlassColors.cyan) }
            Text(text)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(color)
        }
    }
}

private struct ResultCard: View {
    let result: HarmonyEngineCheckResult

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(result.title)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                Spacer()
                Text(result.passed ? "PASS" : "FAIL")
                    .font(.system(size: 12, weight: .heavy, design: .monospaced))
                    .foregroundStyle(result.passed ? CyberGlassColors.obsidian : .white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(result.passed ? AnyShapeStyle(CyberGlassColors.selectedChip) : AnyShapeStyle(CyberGlassColors.danger), in: Capsule())
            }
            Text(String(format: "%.0f ms", result.elapsedMs))
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(CyberGlassColors.sky)
            ScrollView(.horizontal, showsIndicators: false) {
                Text(result.report)
                    .font(.system(size: 10.5, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.textSilver)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: true, vertical: false)
            }
        }
        .padding(16)
        .background(CyberGlassColors.card.opacity(CyberGlassColors.cardAlpha), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(CyberGlassColors.iridescent, lineWidth: 1))
    }
}
