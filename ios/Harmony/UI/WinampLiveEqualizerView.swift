import QuartzCore
import SwiftUI

/// One 60 fps frame of the equalizer's live state.
struct EqFrame: Equatable {
    /// What each band really applies now, dB: the slider, Auto's live offset and the transient ducking.
    var effectiveGains = [Float](repeating: 0, count: 10)
    /// Auto's live offset per band, dB (±4.5).
    var autoOffsets = [Float](repeating: 0, count: 10)
    /// 32 spectrum bars and their falling peak-hold dots, 0...1.
    var bars = [Float](repeating: 0, count: EqTelemetry.barCount)
    var peaks = [Float](repeating: 0, count: EqTelemetry.barCount)
    /// The live automatic preamp, dB.
    var livePreampDb: Float = 0
    var bassDuckDb: Float = 0
    var harshDuckDb: Float = 0
    var frozen = false
    var ceilingDb: Float = -0.15
    var heals: UInt32 = 0
}

/**
 * Polls MobileEqEngine at the display's rate (CADisplayLink, 60 fps): the
 * FFT bars and peaks the engine computes from the samples it hands over
 * lock-free, and the gains it actually applies. The analyzer runs only while
 * someone watches.
 */
@MainActor
final class EqTelemetry: ObservableObject {
    static let barCount = 32

    @Published private(set) var frame = EqFrame()

    private var link: CADisplayLink?
    private var gains = [Float](repeating: 0, count: 10)
    private var offsets = [Float](repeating: 0, count: 10)
    private var bars = [Float](repeating: 0, count: barCount)
    private var peaks = [Float](repeating: 0, count: barCount)

    private var bridge: HarmonyDspBridge { HarmonyAudioController.shared.bridge }

    func start() {
        guard link == nil else { return }
        bridge.setSpectrumBarCount(Self.barCount)
        bridge.setAnalyzerEnabled(true)
        let link = CADisplayLink(target: DisplayLinkTarget(self), selector: #selector(DisplayLinkTarget.tick))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() {
        link?.invalidate()
        link = nil
        bridge.setAnalyzerEnabled(false)
    }

    fileprivate func tick() {
        let bridge = self.bridge
        let preamp = gains.withUnsafeMutableBufferPointer { g in
            bars.withUnsafeMutableBufferPointer { b in
                peaks.withUnsafeMutableBufferPointer { p in
                    bridge.pollTelemetry(gains: g.baseAddress, bars: b.baseAddress, peaks: p.baseAddress, capacity: Self.barCount)
                }
            }
        }
        offsets.withUnsafeMutableBufferPointer { o in
            if let base = o.baseAddress { bridge.copyLiveAutoOffsets(into: base) }
        }
        frame = EqFrame(effectiveGains: gains, autoOffsets: offsets, bars: bars, peaks: peaks, livePreampDb: preamp,
                        bassDuckDb: bridge.bassDuckDb, harshDuckDb: bridge.harshDuckDb, frozen: bridge.tonalFrozen,
                        ceilingDb: bridge.limiterCeilingDb, heals: bridge.healCount)
    }
}

/// CADisplayLink retains its target; this keeps it from retaining the telemetry.
@MainActor
private final class DisplayLinkTarget: NSObject {
    weak var owner: EqTelemetry?

    init(_ owner: EqTelemetry) {
        self.owner = owner
    }

    @objc func tick() {
        owner?.tick()
    }
}

/**
 * The live equalizer, Winamp style, at 60 fps.
 *
 *  - 32 spectrum bars from the engine's FFT, with falling peak dots.
 *  - Ten sliders, 31 Hz to 16 kHz, ±12 dB. Each has your thumb and a glowing
 *    "ghost" thumb that moves on its own to where the engine really is (your
 *    gain, Auto's live offset, the transient ducking), with the difference
 *    drawn between them, and a cubic spline through each set of thumbs.
 *  - EQ ON, AUTO LIVE, FLAT, the preamp, and the live readouts (automatic
 *    headroom, bass and sibilance ducking, the quiet-passage hold, the
 *    limiter ceiling of the output).
 */
struct WinampLiveEqualizerView: View {
    @ObservedObject private var player = HarmonyAudioController.shared
    @StateObject private var telemetry = EqTelemetry()
    @Environment(\.dismiss) private var dismiss

    private static let labels = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"]
    private static let range: Float = 12

    var body: some View {
        ZStack {
            CyberGlassColors.page.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    controls
                    spectrum
                        .frame(height: 140)
                        .background(Color.black.opacity(0.35), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(CyberGlassColors.chipBorder, lineWidth: 1))
                    sliders
                        .frame(height: 280)
                    preamp
                    readouts
                }
                .padding(16)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear { telemetry.start() }
        .onDisappear { telemetry.stop() }
    }

    // MARK: - Header and controls

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("LIVE EQUALIZER")
                    .font(.system(size: 22, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.textPrimary)
                Text(outputBadge)
                    .font(.system(size: 10.5, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.sky)
            }
            Spacer()
            Button("Done") { dismiss() }
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(CyberGlassColors.cyan)
        }
    }

    /// "HI-RES USB DAC • 96kHz / 32-bit Float".
    private var outputBadge: String {
        let rate = player.route.sampleRate / 1000
        let khz = rate.rounded() == rate ? "\(Int(rate))kHz" : String(format: "%.1fkHz", rate)
        return "\(player.route.badge) • \(khz) / 32-bit Float"
    }

    private var controls: some View {
        HStack(spacing: 8) {
            ToggleChip(title: "EQ ON", isOn: player.equalizer.enabled) { player.equalizer.enabled.toggle() }
            ToggleChip(title: "AUTO LIVE", isOn: player.equalizer.auto) { player.equalizer.auto.toggle() }
            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                    player.equalizer.gains = [Float](repeating: 0, count: 10)
                    player.equalizer.preampDb = 0
                }
            } label: {
                Text("FLAT")
                    .font(.system(size: 11, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.textSilver)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(CyberGlassColors.chip, in: Capsule())
                    .overlay(Capsule().strokeBorder(CyberGlassColors.chipBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)
            Spacer(minLength: 0)
            VStack(alignment: .trailing, spacing: 1) {
                Text("LIVE PREAMP")
                    .font(.system(size: 8.5, weight: .heavy))
                    .foregroundStyle(CyberGlassColors.textQuiet)
                Text(String(format: "%+.1f dB", telemetry.frame.livePreampDb + player.equalizer.preampDb))
                    .font(.system(size: 13, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.mint)
            }
        }
    }

    // MARK: - Spectrum

    private var spectrum: some View {
        Canvas { context, size in
            let frame = telemetry.frame
            let count = frame.bars.count
            guard count > 0 else { return }
            let gap: CGFloat = 2.5
            let width = (size.width - gap * CGFloat(count + 1)) / CGFloat(count)
            let usable = size.height - 12
            let gradient = Gradient(colors: [CyberGlassColors.mint, CyberGlassColors.cyan, CyberGlassColors.violet, CyberGlassColors.purple])
            for i in 0..<count {
                let x = gap + CGFloat(i) * (width + gap)
                let level = CGFloat(max(0, min(1, frame.bars[i])))
                let height = max(2, level * usable)
                let rect = CGRect(x: x, y: size.height - 6 - height, width: width, height: height)
                context.fill(Path(roundedRect: rect, cornerRadius: min(2, width / 2)),
                             with: .linearGradient(gradient, startPoint: CGPoint(x: 0, y: size.height),
                                                   endPoint: CGPoint(x: 0, y: size.height - usable)))
                let peak = CGFloat(max(0, min(1, frame.peaks[i])))
                if peak > 0.01 {
                    let dot = CGRect(x: x, y: size.height - 6 - peak * usable - 3, width: width, height: 2.5)
                    context.fill(Path(dot), with: .color(.white.opacity(0.9)))
                }
            }
        }
        .padding(4)
        .accessibilityLabel("Live spectrum")
    }

    // MARK: - Sliders

    private var sliders: some View {
        GeometryReader { geo in
            let size = geo.size
            let top: CGFloat = 22
            let bottom: CGFloat = 26
            let track = size.height - top - bottom
            let column = size.width / 10
            let user = player.equalizer.gains
            let live = telemetry.frame.effectiveGains
            ZStack {
                Canvas { context, _ in
                    // The 0 dB line and ±6 dB guides.
                    for db: Float in [-6, 0, 6] {
                        let y = top + Self.y(for: db, height: track)
                        var line = Path()
                        line.move(to: CGPoint(x: 0, y: y))
                        line.addLine(to: CGPoint(x: size.width, y: y))
                        context.stroke(line, with: .color(.white.opacity(db == 0 ? 0.18 : 0.07)),
                                       style: StrokeStyle(lineWidth: 1, dash: db == 0 ? [] : [3, 4]))
                    }
                    let userPoints = (0..<10).map { CGPoint(x: column * (CGFloat($0) + 0.5), y: top + Self.y(for: user[$0], height: track)) }
                    let livePoints = (0..<10).map { CGPoint(x: column * (CGFloat($0) + 0.5), y: top + Self.y(for: live[$0], height: track)) }
                    // Slider tracks and the delta between your thumb and the engine's.
                    for i in 0..<10 {
                        let x = userPoints[i].x
                        context.fill(Path(roundedRect: CGRect(x: x - 2, y: top, width: 4, height: track), cornerRadius: 2),
                                     with: .color(CyberGlassColors.recessed))
                        let low = min(userPoints[i].y, livePoints[i].y)
                        let high = max(userPoints[i].y, livePoints[i].y)
                        if high - low > 1.5 {
                            let boost = live[i] > user[i]
                            context.fill(Path(roundedRect: CGRect(x: x - 3, y: low, width: 6, height: high - low), cornerRadius: 3),
                                         with: .color((boost ? CyberGlassColors.mint : CyberGlassColors.violet).opacity(0.55)))
                        }
                    }
                    // Cubic splines: the engine's (mint glow) under yours (cyan).
                    var glow = context
                    glow.addFilter(.blur(radius: 4))
                    glow.stroke(Self.spline(through: livePoints), with: .color(CyberGlassColors.mint.opacity(0.8)), lineWidth: 3)
                    context.stroke(Self.spline(through: livePoints), with: .color(CyberGlassColors.mint.opacity(0.9)),
                                   style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                    context.stroke(Self.spline(through: userPoints), with: .color(CyberGlassColors.cyan), lineWidth: 2)
                    // Ghost thumbs, then your thumbs.
                    for point in livePoints {
                        var halo = context
                        halo.addFilter(.blur(radius: 5))
                        halo.fill(Path(ellipseIn: CGRect(x: point.x - 9, y: point.y - 9, width: 18, height: 18)),
                                  with: .color(CyberGlassColors.mint.opacity(0.7)))
                        context.stroke(Path(ellipseIn: CGRect(x: point.x - 7, y: point.y - 7, width: 14, height: 14)),
                                       with: .color(CyberGlassColors.mint), lineWidth: 1.5)
                    }
                    for point in userPoints {
                        context.fill(Path(roundedRect: CGRect(x: point.x - 11, y: point.y - 6, width: 22, height: 12), cornerRadius: 6),
                                     with: .linearGradient(Gradient(colors: [CyberGlassColors.cyan, CyberGlassColors.azure]),
                                                           startPoint: CGPoint(x: point.x, y: point.y - 6),
                                                           endPoint: CGPoint(x: point.x, y: point.y + 6)))
                    }
                }
                // Values above, frequencies below.
                ForEach(0..<10, id: \.self) { i in
                    Text(String(format: "%+.0f", user[i]))
                        .font(.system(size: 9.5, weight: .bold, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.cyan)
                        .position(x: column * (CGFloat(i) + 0.5), y: 8)
                    Text(Self.labels[i])
                        .font(.system(size: 10, weight: .heavy, design: .monospaced))
                        .foregroundStyle(CyberGlassColors.textSecondary)
                        .position(x: column * (CGFloat(i) + 0.5), y: size.height - 9)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let band = min(9, max(0, Int(value.startLocation.x / column)))
                        let fraction = Float((value.location.y - top) / track)
                        let db = (Self.range - fraction * 2 * Self.range).clamped(-Self.range, Self.range)
                        // Steps of 0.5 dB, with a gentle snap to 0.
                        var stepped = (db * 2).rounded() / 2
                        if abs(stepped) < 0.6 { stepped = 0 }
                        if player.equalizer.gains[band] != stepped { player.equalizer.gains[band] = stepped }
                    }
            )
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Equalizer bands")
            .accessibilityValue(user.enumerated().map { "\(Self.labels[$0.offset]) \(String(format: "%+.0f", $0.element)) dB" }.joined(separator: ", "))
        }
    }

    /// Where [db] sits on a track of [height] (top = +12 dB).
    private static func y(for db: Float, height: CGFloat) -> CGFloat {
        CGFloat((range - db.clamped(-range, range)) / (2 * range)) * height
    }

    /// A smooth curve through [points] (Catmull-Rom as cubic Béziers).
    private static func spline(through points: [CGPoint]) -> Path {
        var path = Path()
        guard let first = points.first else { return path }
        path.move(to: first)
        for i in 0..<(points.count - 1) {
            let p0 = points[max(0, i - 1)]
            let p1 = points[i]
            let p2 = points[i + 1]
            let p3 = points[min(points.count - 1, i + 2)]
            let c1 = CGPoint(x: p1.x + (p2.x - p0.x) / 6, y: p1.y + (p2.y - p0.y) / 6)
            let c2 = CGPoint(x: p2.x - (p3.x - p1.x) / 6, y: p2.y - (p3.y - p1.y) / 6)
            path.addCurve(to: p2, control1: c1, control2: c2)
        }
        return path
    }

    // MARK: - Preamp and readouts

    private var preamp: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("PREAMP").font(.system(size: 11, weight: .heavy, design: .monospaced)).foregroundStyle(CyberGlassColors.textSecondary)
                Spacer()
                Text(String(format: "%+.1f dB", player.equalizer.preampDb))
                    .font(.system(size: 12, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CyberGlassColors.cyan)
            }
            Slider(value: Binding(get: { Double(player.equalizer.preampDb) },
                                  set: { player.equalizer.preampDb = Float(($0 * 2).rounded() / 2) }),
                   in: -24...12)
                .tint(CyberGlassColors.cyan)
            Text("On top of the live automatic headroom, which lowers the level only while the boosted bands carry energy.")
                .font(.system(size: 11))
                .foregroundStyle(CyberGlassColors.textQuiet)
        }
        .padding(14)
        .background(CyberGlassColors.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var readouts: some View {
        let frame = telemetry.frame
        return LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            Readout(label: "AUTO HEADROOM", value: String(format: "%+.1f dB", frame.livePreampDb), tint: CyberGlassColors.mint)
            Readout(label: "LIMITER CEILING", value: String(format: "%.2f dBFS", frame.ceilingDb), tint: CyberGlassColors.sky)
            Readout(label: "BASS DUCK", value: String(format: "%.1f dB", frame.bassDuckDb), tint: CyberGlassColors.violet)
            Readout(label: "SIBILANCE DUCK", value: String(format: "%.1f dB", frame.harshDuckDb), tint: CyberGlassColors.purple)
            Readout(label: "AUTO", value: !player.equalizer.auto ? "OFF" : frame.frozen ? "HOLD • QUIET" : "ADAPTING",
                    tint: player.equalizer.auto ? CyberGlassColors.cyan : CyberGlassColors.textQuiet)
            Readout(label: "SELF-HEALS", value: "\(frame.heals)", tint: frame.heals > 0 ? CyberGlassColors.amber : CyberGlassColors.textSecondary)
        }
    }
}

/// A glowing on/off capsule ("EQ ON", "AUTO LIVE").
private struct ToggleChip: View {
    let title: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Circle()
                    .fill(isOn ? CyberGlassColors.mint : CyberGlassColors.textQuiet)
                    .frame(width: 6, height: 6)
                    .shadow(color: isOn ? CyberGlassColors.mint : .clear, radius: 4)
                Text(title).font(.system(size: 11, weight: .heavy, design: .monospaced))
            }
            .foregroundStyle(isOn ? CyberGlassColors.obsidian : CyberGlassColors.textSecondary)
            .padding(.horizontal, 11)
            .padding(.vertical, 8)
            .background {
                if isOn {
                    Capsule().fill(CyberGlassColors.selectedChip)
                } else {
                    Capsule().fill(CyberGlassColors.chip)
                }
            }
            .shadow(color: isOn ? CyberGlassColors.cyan.opacity(0.5) : .clear, radius: 8)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(isOn ? "On" : "Off")
    }
}

private struct Readout: View {
    let label: String
    let value: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 9, weight: .heavy)).tracking(0.8).foregroundStyle(CyberGlassColors.textQuiet)
            Text(value).font(.system(size: 14, weight: .heavy, design: .monospaced)).foregroundStyle(tint)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(CyberGlassColors.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

extension Float {
    func clamped(_ low: Float, _ high: Float) -> Float { Swift.min(high, Swift.max(low, self)) }
}
