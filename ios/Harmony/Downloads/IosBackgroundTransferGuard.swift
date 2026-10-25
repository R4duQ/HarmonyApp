import AVFoundation
import Combine
import Network
import UIKit

/**
 * Keeps Soulseek transfers alive with the screen locked.
 *
 * iOS suspends an app soon after it leaves the screen, and a suspended app's
 * sockets stop: a Soulseek transfer (a TCP connection to another person's
 * computer) would freeze mid-file. Direct links don't need this: they run in
 * a background URLSession that iOS itself carries on.
 *
 *  - Tier 1, background task: while anything runs, Harmony holds a
 *    UIApplication background task. When iOS ends it, every transfer stops
 *    cleanly (its bytes stay on disk) and carries on from there when Harmony
 *    is back.
 *  - Tier 2, music playing: Harmony's own player keeps the app running, so
 *    nothing more is needed.
 *  - Tier 3, locked and paused: while transfers run, Harmony is in the
 *    background and no music plays, a silent audio engine (zeros, volume 0,
 *    mixing with other apps) keeps iOS from suspending it. It stops the
 *    moment the last transfer ends, together with the background task.
 *    "Keep downloads running when locked" turns this tier off.
 *
 * It also watches the network: when Wi-Fi gives way to cellular (or the
 * connection comes back), Soulseek signs in again and what failed meanwhile
 * is retried once.
 *
 * Tier 3 suits a sideloaded app: the App Store does not accept silent audio
 * used only to stay awake.
 */
@MainActor
final class IosBackgroundTransferGuard: ObservableObject {
    static let shared = IosBackgroundTransferGuard()

    enum Tier: String {
        case idle = "Idle"
        case foreground = "Active"
        case backgroundTask = "Background task"
        case playback = "Riding on playback"
        case silentAudio = "Silent keep-alive"
    }

    @Published private(set) var tier: Tier = .idle
    /// Every download still running or waiting (links included): the tab's badge.
    @Published private(set) var activeDownloads = 0
    @Published private(set) var networkAvailable = true
    @Published private(set) var onCellular = false
    @Published var keepAliveWhenLocked: Bool {
        didSet {
            UserDefaults.standard.set(keepAliveWhenLocked, forKey: Self.keepAliveKey)
            evaluate()
        }
    }

    /// "BACKGROUND GUARD: ACTIVE • SCREEN-LOCK SAFE" while it protects something.
    var badge: String? {
        guard socketWork > 0 else { return nil }
        if tier == .backgroundTask && !keepAliveWhenLocked { return "BACKGROUND GUARD: ACTIVE • KEEP THE SCREEN ON" }
        return "BACKGROUND GUARD: ACTIVE • SCREEN-LOCK SAFE"
    }

    private static let keepAliveKey = "downloads.keepAliveWhenLocked"
    /// Work that needs Harmony running: Soulseek transfers and playlist searches.
    private var socketWork = 0
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var silentEngine: AVAudioEngine?
    private var inBackground = false
    private var started = false
    private var pathLostAt: Date?
    private var lastInterface: NWInterface.InterfaceType?
    private let pathMonitor = NWPathMonitor()
    private var cancellables: Set<AnyCancellable> = []
    private var observers: [NSObjectProtocol] = []

    private init() {
        keepAliveWhenLocked = UserDefaults.standard.object(forKey: Self.keepAliveKey) as? Bool ?? true
    }

    func start() {
        guard !started else { return }
        started = true
        inBackground = UIApplication.shared.applicationState == .background

        // Any change to the transfers, the playlist matcher or the player: look again (after it lands).
        let changes: [ObservableObjectPublisher] = [
            SoulseekService.shared.objectWillChange,
            StreamIngestManager.shared.objectWillChange,
            LosslessTrackResolver.shared.objectWillChange,
            HarmonyAudioController.shared.objectWillChange,
        ]
        for publisher in changes {
            publisher.sink { [weak self] _ in
                Task { @MainActor in self?.evaluate() }
            }
            .store(in: &cancellables)
        }

        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.inBackground = true
                self?.evaluate()
            }
        })
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.inBackground = false
                SoulseekService.shared.resumeSuspended()
                self?.evaluate()
            }
        })

        pathMonitor.pathUpdateHandler = { [weak self] path in
            let available = path.status == .satisfied
            let interface: NWInterface.InterfaceType? =
                path.usesInterfaceType(.wifi) ? .wifi :
                path.usesInterfaceType(.cellular) ? .cellular :
                path.usesInterfaceType(.wiredEthernet) ? .wiredEthernet : nil
            Task { @MainActor in self?.pathChanged(available: available, interface: interface) }
        }
        pathMonitor.start(queue: DispatchQueue(label: "Harmony network path"))
        evaluate()
    }

    // MARK: - The tiers

    private func evaluate() {
        let soulseek = SoulseekService.shared
        socketWork = soulseek.activeTransferCount + (LosslessTrackResolver.shared.running ? 1 : 0)
        let downloads = HarmonyDownloadEngine.activeCount()
        if activeDownloads != downloads { activeDownloads = downloads }

        guard socketWork > 0 else {
            stopSilentAudio()
            endBackgroundTask()
            setTier(.idle)
            return
        }
        // Tier 1: always, while something runs.
        beginBackgroundTaskIfNeeded()
        guard inBackground else {
            stopSilentAudio()
            setTier(.foreground)
            return
        }
        if HarmonyAudioController.shared.isPlaying {
            // Tier 2: the player's session already keeps Harmony running.
            stopSilentAudio()
            setTier(.playback)
        } else if keepAliveWhenLocked {
            // Tier 3.
            startSilentAudio()
            setTier(silentEngine != nil ? .silentAudio : .backgroundTask)
        } else {
            stopSilentAudio()
            setTier(.backgroundTask)
        }
    }

    private func setTier(_ new: Tier) {
        if tier != new { tier = new }
    }

    private func beginBackgroundTaskIfNeeded() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "HarmonyActiveTransfer") { [weak self] in
            // iOS takes the time back now: stop the transfers cleanly, keeping their bytes.
            MainActor.assumeIsolated {
                SoulseekService.shared.suspendForBackground()
                self?.endBackgroundTask()
            }
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private func startSilentAudio() {
        guard silentEngine == nil else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            // Mixes with other apps' audio and never takes the lock screen's controls.
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            let engine = AVAudioEngine()
            let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)
            let source = Self.makeSilentSource()
            engine.attach(source)
            engine.connect(source, to: engine.mainMixerNode, format: format)
            engine.mainMixerNode.outputVolume = 0
            try engine.start()
            silentEngine = engine
        } catch {
            restorePlaybackCategory()
        }
    }

    private func stopSilentAudio() {
        guard let engine = silentEngine else { return }
        engine.stop()
        silentEngine = nil
        restorePlaybackCategory()
    }

    /// Back to the player's own session: no mixing, so the lock screen shows Harmony again.
    private func restorePlaybackCategory() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
        if !HarmonyAudioController.shared.isPlaying {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    /// A source of zeros, built outside the main actor: its block runs on the audio thread.
    private nonisolated static func makeSilentSource() -> AVAudioSourceNode {
        AVAudioSourceNode { _, _, _, audioBufferList -> OSStatus in
            for buffer in UnsafeMutableAudioBufferListPointer(audioBufferList) {
                if let data = buffer.mData { memset(data, 0, Int(buffer.mDataByteSize)) }
            }
            return noErr
        }
    }

    // MARK: - Network

    private func pathChanged(available: Bool, interface: NWInterface.InterfaceType?) {
        networkAvailable = available
        onCellular = interface == .cellular
        if !available {
            if pathLostAt == nil { pathLostAt = Date() }
            return
        }
        if let lost = pathLostAt {
            pathLostAt = nil
            SoulseekService.shared.networkRestored(lostAt: lost)
        } else if let previous = lastInterface, let interface, previous != interface {
            // Wi-Fi to cellular without a gap: sockets on the old interface die soon.
            SoulseekService.shared.networkRestored(lostAt: Date().addingTimeInterval(-30))
        }
        lastInterface = interface
    }
}
