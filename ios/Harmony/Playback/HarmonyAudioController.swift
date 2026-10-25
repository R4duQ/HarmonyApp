import AVFoundation
import Combine
import MediaPlayer
import SwiftUI
import UIKit

/// Where the sound is going: it picks the equalizer's output profile and the badge.
struct OutputRoute: Equatable {
    enum Kind: String {
        case speaker, wired, bluetooth, usbDac, airPlay, carPlay, other
    }

    var kind: Kind
    /// The device's own name ("AirPods Pro", "Chord Mojo 2").
    var name: String
    /// The hardware rate the engine runs at, Hz.
    var sampleRate: Double

    static let speaker = OutputRoute(kind: .speaker, name: "iPhone", sampleRate: 48_000)

    /// "PHONE SPEAKER", "BLUETOOTH • AirPods", "HI-RES USB DAC • 96kHz".
    var badge: String {
        switch kind {
        case .speaker: return "PHONE SPEAKER"
        case .wired: return "WIRED"
        case .bluetooth: return name.isEmpty ? "BLUETOOTH" : "BLUETOOTH • \(name)"
        case .usbDac:
            let khz = sampleRate / 1000
            let rate = khz.rounded() == khz ? "\(Int(khz))kHz" : String(format: "%.1fkHz", khz)
            return sampleRate > 48_000 ? "HI-RES USB DAC • \(rate)" : "USB DAC • \(rate)"
        case .airPlay: return name.isEmpty ? "AIRPLAY" : "AIRPLAY • \(name)"
        case .carPlay: return "CARPLAY"
        case .other: return name.uppercased()
        }
    }

    /// The equalizer's profile: high-pass, codec pad and ceiling (MobileEqEngine).
    var eqDevice: HarmonyOutputDevice {
        switch kind {
        case .speaker: return .phoneSpeaker
        case .bluetooth, .airPlay: return .bluetoothA2dp
        case .usbDac: return .usbDacHiRes
        case .wired, .carPlay, .other: return .wiredHeadphones
        }
    }
}

/// The equalizer as the listener set it (kept across launches).
struct EqualizerSettings: Codable, Equatable {
    var enabled = true
    /// Live spectral auto-calibration.
    var auto = false
    /// Ten bands, 31 Hz to 16 kHz, -12..+12 dB.
    var gains = [Float](repeating: 0, count: 10)
    /// -24..+12 dB on top of the live automatic headroom.
    var preampDb: Float = 0
}

enum RepeatMode: String, Codable, CaseIterable {
    case off, all, one
}

enum ShuffleMode: String, Codable, CaseIterable {
    case off
    /// The upcoming songs in a random order (the queue itself is reordered, as shown).
    case random
    /// Smart Shuffle: the 24-D brain picks what comes next.
    case smart
}

/// One song in the queue. The same song can be queued twice, so entries have their own token.
struct QueueEntry: Identifiable, Hashable {
    let token: Int64
    var track: LibraryTrack
    /// Picked by Smart Shuffle (a re-rank or a mode change may replace it).
    var generated = false
    /// Queued by the listener with Play next or Add to queue (never replaced).
    var userQueued = false

    var id: Int64 { token }
}

/**
 * Harmony's player on iOS: the audio session, the output route, the lock
 * screen and Control Center, the equalizer settings and the play queue.
 *
 * Audio runs through HarmonyDspBridge (AVAudioEngine into the shared C++
 * MobileEqEngine, gapless). This class decides what plays.
 *
 *  - Session: .playback, so music continues with the screen locked; calls
 *    and alarms pause it and resume it when they say so; unplugging
 *    headphones pauses, as Apple asks.
 *  - Route: speaker, wired, Bluetooth, AirPlay, CarPlay or a USB DAC. A DAC
 *    is asked to run at the song's own rate (up to 192 kHz), so hi-res files
 *    reach it untouched by resampling; the engine rebuilds at the new rate.
 *  - Queue: see HarmonyAudioController+Queue.swift.
 */
@MainActor
final class HarmonyAudioController: NSObject, ObservableObject {
    static let shared = HarmonyAudioController()

    // MARK: Published state

    @Published internal(set) var queue: [QueueEntry] = []
    /// Index of the song playing (or paused) in [queue]; -1 when empty.
    @Published internal(set) var currentIndex = -1
    @Published internal(set) var isPlaying = false
    /// Seconds into the song, refreshed four times a second.
    @Published internal(set) var position: Double = 0
    @Published internal(set) var duration: Double = 0
    @Published internal(set) var shuffleMode: ShuffleMode = .off
    @Published internal(set) var repeatMode: RepeatMode = .off
    @Published private(set) var route: OutputRoute = .speaker
    @Published private(set) var format: HarmonyTrackFormat?
    @Published var equalizer: EqualizerSettings {
        didSet { applyEqualizer(); saveEqualizer() }
    }
    /// Why each Smart Shuffle pick was made, by song id.
    @Published internal(set) var decisions: [Int64: ShuffleDecision] = [:]
    @Published internal(set) var lastAudit: ShuffleAuditSummary?
    @Published internal(set) var lastEvent: HarmonyShuffleEvent = .none
    @Published var lastError: String?

    var currentEntry: QueueEntry? { queue.indices.contains(currentIndex) ? queue[currentIndex] : nil }
    var currentTrack: LibraryTrack? { currentEntry?.track }
    var upcoming: ArraySlice<QueueEntry> { currentIndex + 1 < queue.count ? queue[(currentIndex + 1)...] : [] }

    // MARK: Internals (shared with the queue extension)

    let bridge = HarmonyDspBridge()
    let brain = SmartShuffleBrain()
    let store = HarmonyLibraryStore.shared
    var nextToken: Int64 = 1
    /// The token a load is waiting to hear start (that start is not a gapless move).
    var awaitingStartToken: Int64?
    /// The list exactly as the listener started it, to put back when shuffle goes off.
    var originalOrder: [LibraryTrack]?
    var brainLoaded = false
    var brainReloadTask: Task<Void, Never>?
    /// A Smart Shuffle batch is being picked (one at a time).
    var toppingUp = false
    /// Bumped whenever a new list starts, so late work for the old one is dropped.
    var queueGeneration = 0
    /// The queue was restored at launch but its song is not open in the engine yet.
    var needsLoad = false
    /// The token handed to the engine as the next song (-1 = none).
    var lineUpToken: Int64 = -1

    private var started = false
    private var engineStarted = false
    private var sessionConfigured = false
    private var resumeAfterInterruption = false
    private var positionTimer: Timer?
    private var observers: [NSObjectProtocol] = []
    private var artworkCache: (trackId: Int64, artwork: MPMediaItemArtwork)?

    private static let equalizerKey = "harmony.equalizer"
    static let modesKey = "harmony.playback.modes"
    static let queueKey = "harmony.playback.queue"

    private override init() {
        if let data = UserDefaults.standard.data(forKey: Self.equalizerKey),
           let saved = try? JSONDecoder().decode(EqualizerSettings.self, from: data) {
            equalizer = saved
        } else {
            equalizer = EqualizerSettings()
        }
        super.init()
        bridge.delegate = self
        applyEqualizer()
        restoreModes()
    }

    /// Call once at launch: session, remote commands, route, the last queue (paused) and the brain.
    func start() {
        guard !started else { return }
        started = true
        configureSession()
        observeSession()
        configureRemoteCommands()
        refreshRoute()
        restoreQueue()
        reloadBrain()
        observers.append(NotificationCenter.default.addObserver(
            forName: .harmonyLibraryChanged, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.scheduleBrainReload() }
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.saveQueue() }
        })
    }

    // MARK: - Transport

    func play() {
        guard currentEntry != nil else { return }
        if needsLoad {
            needsLoad = false
            load(index: currentIndex, startSeconds: position, autoplay: true)
            return
        }
        activateSession()
        if !engineStarted {
            do {
                try bridge.startEngine()
                engineStarted = true
            } catch {
                lastError = "The audio engine did not start: \(error.localizedDescription)"
                return
            }
        }
        bridge.play()
        isPlaying = bridge.isPlaying
        startPositionTimer()
        updateNowPlaying()
    }

    func pause() {
        bridge.pause()
        isPlaying = false
        position = bridge.positionSeconds
        updateNowPlaying()
        saveQueue()
    }

    func togglePlayPause() {
        isPlaying ? pause() : play()
    }

    func seek(to seconds: Double) {
        let target = max(0, min(seconds, duration > 0 ? duration - 0.25 : seconds))
        bridge.seek(toSeconds: target)
        position = target
        updateNowPlaying()
    }

    // MARK: - Loading songs

    /// Opens queue[index] (from [startSeconds]) and lines up the song after it.
    func load(index: Int, startSeconds: Double = 0, autoplay: Bool) {
        guard queue.indices.contains(index) else { return }
        let entry = queue[index]
        currentIndex = index
        needsLoad = false
        awaitingStartToken = entry.token
        lineUpToken = -1
        if !engineStarted {
            do {
                activateSession()
                try bridge.startEngine()
                engineStarted = true
            } catch {
                lastError = "The audio engine did not start: \(error.localizedDescription)"
                return
            }
        }
        preferSampleRate(for: entry.track)
        do {
            try bridge.load(url: entry.track.url, token: entry.token, startSeconds: startSeconds)
        } catch {
            lastError = "“\(entry.track.title)” could not be opened: \(error.localizedDescription)"
            awaitingStartToken = nil
            skipUnplayable(at: index)
            return
        }
        position = startSeconds
        duration = Double(entry.track.durationMs) / 1000
        updateNextSong()
        if autoplay { play() } else { updateNowPlaying() }
    }

    /// The song after the current one, for the gapless handover (repeat and the end of the queue included).
    func updateNextSong() {
        guard let current = currentEntry else {
            if lineUpToken != -1 { bridge.setNext(url: nil, token: -1) }
            lineUpToken = -1
            return
        }
        let next: QueueEntry?
        switch repeatMode {
        case .one: next = current
        case .all: next = currentIndex + 1 < queue.count ? queue[currentIndex + 1] : queue.first
        case .off: next = currentIndex + 1 < queue.count ? queue[currentIndex + 1] : nil
        }
        let token = next?.token ?? -1
        // Only a different song is handed over: the engine may already be decoding the one lined up.
        guard token != lineUpToken || needsLoad else { return }
        lineUpToken = token
        guard !needsLoad else { return }
        bridge.setNext(url: next?.track.url, token: token)
    }

    private func skipUnplayable(at index: Int) {
        guard index + 1 < queue.count else {
            isPlaying = false
            return
        }
        load(index: index + 1, autoplay: isPlaying)
    }

    func makeEntry(_ track: LibraryTrack, generated: Bool = false, userQueued: Bool = false) -> QueueEntry {
        defer { nextToken += 1 }
        return QueueEntry(token: nextToken, track: track, generated: generated, userQueued: userQueued)
    }

    // MARK: - Audio session

    private func configureSession() {
        guard !sessionConfigured else { return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            sessionConfigured = true
        } catch {
            lastError = "Audio session: \(error.localizedDescription)"
        }
    }

    private func activateSession() {
        configureSession()
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    private func observeSession() {
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init)
            Task { @MainActor in self?.handleInterruption(type: type, options: options) }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            Task { @MainActor in self?.handleRouteChange(reason: reason) }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.recoverFromMediaReset() }
        })
    }

    private func handleInterruption(type: AVAudioSession.InterruptionType?, options: AVAudioSession.InterruptionOptions?) {
        switch type {
        case .began:
            resumeAfterInterruption = isPlaying
            if isPlaying { pause() }
        case .ended:
            if resumeAfterInterruption, options?.contains(.shouldResume) == true { play() }
            resumeAfterInterruption = false
        default:
            break
        }
    }

    private func handleRouteChange(reason: AVAudioSession.RouteChangeReason?) {
        // Headphones unplugged or Bluetooth gone: stop rather than blare from the speaker.
        if reason == .oldDeviceUnavailable, isPlaying { pause() }
        refreshRoute()
        if let track = currentTrack { preferSampleRate(for: track) }
    }

    /// The media server restarted: everything audio has to be built again.
    private func recoverFromMediaReset() {
        let wasPlaying = isPlaying
        let resumeAt = bridge.positionSeconds
        bridge.stopEngine()
        engineStarted = false
        sessionConfigured = false
        configureSession()
        if currentEntry != nil { load(index: currentIndex, startSeconds: resumeAt, autoplay: wasPlaying) }
    }

    private func refreshRoute() {
        let session = AVAudioSession.sharedInstance()
        let output = session.currentRoute.outputs.first
        let rate = engineStarted ? bridge.outputSampleRate : session.sampleRate
        var kind = OutputRoute.Kind.speaker
        if let port = output?.portType {
            switch port {
            case .builtInSpeaker, .builtInReceiver: kind = .speaker
            case .headphones, .lineOut: kind = .wired
            case .bluetoothA2DP, .bluetoothLE, .bluetoothHFP: kind = .bluetooth
            case .usbAudio: kind = .usbDac
            case .airPlay: kind = .airPlay
            case .carAudio: kind = .carPlay
            default: kind = .other
            }
        }
        route = OutputRoute(kind: kind, name: kind == .speaker ? "iPhone" : (output?.portName ?? ""), sampleRate: rate)
        bridge.setOutputDevice(route.eqDevice)
        format = bridge.currentFormat
    }

    /**
     * A USB DAC plays the file at its own rate (44.1 to 192 kHz) when the DAC
     * allows it; elsewhere iOS keeps its usual rate (Bluetooth and the
     * speaker resample in hardware anyway).
     */
    private func preferSampleRate(for track: LibraryTrack) {
        let session = AVAudioSession.sharedInstance()
        guard session.currentRoute.outputs.first?.portType == .usbAudio, track.sampleRate > 0 else { return }
        let wanted = Double(min(track.sampleRate, 192_000))
        guard abs(session.sampleRate - wanted) > 1 else { return }
        // The engine rebuilds itself at the new rate (AVAudioEngineConfigurationChange).
        try? session.setPreferredSampleRate(wanted)
    }

    // MARK: - Equalizer

    private func applyEqualizer() {
        bridge.setEqEnabled(equalizer.enabled)
        bridge.setAutoMode(equalizer.auto)
        bridge.setUserPreampDb(equalizer.preampDb)
        for (band, gain) in equalizer.gains.prefix(10).enumerated() {
            bridge.setBandGain(max(-12, min(12, gain)), forBand: band)
        }
    }

    private func saveEqualizer() {
        if let data = try? JSONEncoder().encode(equalizer) {
            UserDefaults.standard.set(data, forKey: Self.equalizerKey)
        }
    }

    // MARK: - Position

    private func startPositionTimer() {
        guard positionTimer == nil else { return }
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tickPosition() }
        }
        RunLoop.main.add(timer, forMode: .common)
        positionTimer = timer
    }

    private func tickPosition() {
        position = bridge.positionSeconds
        let reported = bridge.durationSeconds
        if reported > 0 { duration = reported }
        if !isPlaying && !bridge.isPlaying {
            positionTimer?.invalidate()
            positionTimer = nil
        }
    }

    // MARK: - Lock screen and Control Center

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.play() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.skipToNext() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.skipToPrevious() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let seconds = event.positionTime
            Task { @MainActor in self?.seek(to: seconds) }
            return .success
        }
        center.changeShuffleModeCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangeShuffleModeCommandEvent else { return .commandFailed }
            let mode: ShuffleMode = event.shuffleType == .off ? .off : .smart
            Task { @MainActor in self?.setShuffleMode(mode) }
            return .success
        }
        center.changeRepeatModeCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangeRepeatModeCommandEvent else { return .commandFailed }
            let mode: RepeatMode = event.repeatType == .one ? .one : (event.repeatType == .all ? .all : .off)
            Task { @MainActor in self?.setRepeatMode(mode) }
            return .success
        }
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
    }

    func updateNowPlaying() {
        let center = MPNowPlayingInfoCenter.default()
        guard let track = currentTrack else {
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyPlaybackQueueIndex: currentIndex,
            MPNowPlayingInfoPropertyPlaybackQueueCount: queue.count,
        ]
        if let artwork = artwork(for: track) { info[MPMediaItemPropertyArtwork] = artwork }
        center.nowPlayingInfo = info
        center.playbackState = isPlaying ? .playing : .paused
        let commands = MPRemoteCommandCenter.shared()
        commands.changeShuffleModeCommand.currentShuffleType = shuffleMode == .off ? .off : .items
        commands.changeRepeatModeCommand.currentRepeatType = repeatMode == .one ? .one : (repeatMode == .all ? .all : .off)
    }

    private func artwork(for track: LibraryTrack) -> MPMediaItemArtwork? {
        if let cached = artworkCache, cached.trackId == track.id { return cached.artwork }
        guard let url = track.artworkURL, let artwork = Self.makeArtwork(contentsOf: url) else { return nil }
        artworkCache = (track.id, artwork)
        return artwork
    }

    /// Built outside the main actor: iOS calls the artwork's handler on a background queue.
    private nonisolated static func makeArtwork(contentsOf url: URL) -> MPMediaItemArtwork? {
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}

// MARK: - HarmonyDspBridgeDelegate

extension HarmonyAudioController: HarmonyDspBridgeDelegate {
    nonisolated func dspBridge(_ bridge: HarmonyDspBridge, didStartTrack token: Int64) {
        Task { @MainActor in self.trackDidStart(token: token) }
    }

    nonisolated func dspBridgeDidFinishQueue(_ bridge: HarmonyDspBridge) {
        Task { @MainActor in self.queueDidFinish() }
    }

    nonisolated func dspBridge(_ bridge: HarmonyDspBridge, didFailTrack token: Int64, reason: String) {
        Task { @MainActor in self.trackDidFail(token: token, reason: reason) }
    }

    nonisolated func dspBridge(_ bridge: HarmonyDspBridge, didMeasureVector vector: Data, forTrack token: Int64) {
        Task { @MainActor in self.passiveVector(vector, token: token) }
    }

    nonisolated func dspBridge(_ bridge: HarmonyDspBridge, didRebuildAtSampleRate sampleRate: Double) {
        Task { @MainActor in self.refreshRoute() }
    }

    /// Every song start: a load, or the gapless move into the next one.
    private func trackDidStart(token: Int64) {
        format = bridge.currentFormat
        refreshRoute()
        if token == awaitingStartToken {
            awaitingStartToken = nil
            songStarted()
            return
        }
        // Gapless: the song before ended on its own.
        if let finished = currentEntry {
            reportOutcome(of: finished, fraction: 1, natural: true)
        }
        if let index = queue.firstIndex(where: { $0.token == token }) {
            currentIndex = index
        }
        position = 0
        duration = currentTrack.map { Double($0.durationMs) / 1000 } ?? 0
        // The engine used up the song lined up; line up the one after it.
        lineUpToken = -1
        updateNextSong()
        songStarted()
    }

    private func queueDidFinish() {
        if let finished = currentEntry { reportOutcome(of: finished, fraction: 1, natural: true) }
        isPlaying = false
        position = 0
        updateNowPlaying()
        saveQueue()
    }

    private func trackDidFail(token: Int64, reason: String) {
        guard let index = queue.firstIndex(where: { $0.token == token }) else { return }
        lastError = "“\(queue[index].track.title)” could not be played: \(reason)"
        if index == currentIndex || index == currentIndex + 1 { skipUnplayable(at: index) }
    }

    /// A vector measured for free while the song played: kept when the song has none yet.
    private func passiveVector(_ data: Data, token: Int64) {
        guard let entry = queue.first(where: { $0.token == token }), !entry.track.hasVector else { return }
        let vector = data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        store.saveVector(trackId: entry.track.id, vector: vector)
        for index in queue.indices where queue[index].track.id == entry.track.id {
            queue[index].track.hasVector = true
        }
        scheduleBrainReload()
    }
}
