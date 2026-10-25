import AVFoundation
import Foundation
import ShazamKit

/// A song recognised by ear.
struct RecognizedSong: Identifiable, Codable, Hashable {
    var id = UUID()
    var title: String
    var artist: String
    var album: String?
    var artworkURL: URL?
    var isrc: String?
    var appleMusicURL: URL?
    var recognizedAt = Date()

    var importTrack: ImportTrack {
        ImportTrack(title: title, artist: artist, album: album, isrc: isrc, coverArtURL: artworkURL)
    }
}

/**
 * Audio Recognition with Apple's ShazamKit: Harmony listens through the
 * microphone (up to 15 seconds, stopping at the first match) and shows what
 * is playing around you, with one tap to find it losslessly on Soulseek.
 *
 * The audio never leaves the phone: ShazamKit turns it into a signature on
 * the device and sends only that. Matching against the Shazam catalog needs
 * the ShazamKit App Service enabled for the app's bundle ID, which Apple
 * offers to paid developer accounts; a build signed with a free Apple ID gets
 * a clear message instead of a match.
 */
@MainActor
final class SongRecognitionService: ObservableObject {
    static let shared = SongRecognitionService()

    enum State: Equatable {
        case idle
        case listening(elapsed: Double)
        case matched(RecognizedSong)
        case noMatch
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var history: [RecognizedSong] = []
    /// Microphone loudness while listening, 0..1, for the pulsing ring.
    @Published private(set) var level: Float = 0

    private let engine = AVAudioEngine()
    private var session: SHSession?
    private let relay = ShazamRelay()
    private var timeout: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private let historyFile = HarmonyPaths.applicationSupport.appendingPathComponent("recognized.json")

    private init() {
        if let data = try? Data(contentsOf: historyFile), let saved = try? JSONDecoder().decode([RecognizedSong].self, from: data) {
            history = saved
        }
        relay.owner = self
    }

    var isListening: Bool {
        if case .listening = state { return true }
        return false
    }

    func toggle() {
        if isListening { stop(result: .idle) } else { Task { await listen() } }
    }

    func listen() async {
        guard !isListening else { return }
        guard await Self.microphoneAllowed() else {
            state = .failed("Allow Harmony to use the microphone in Settings › Privacy & Security › Microphone.")
            return
        }
        let audio = AVAudioSession.sharedInstance()
        do {
            try audio.setCategory(.playAndRecord, mode: .measurement, options: [.allowBluetoothA2DP, .defaultToSpeaker, .mixWithOthers])
            try audio.setActive(true)
        } catch {
            state = .failed("The microphone is busy: \(error.localizedDescription)")
            return
        }

        let session = SHSession()
        session.delegate = relay
        self.session = session
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self, session] buffer, time in
            session.matchStreamingBuffer(buffer, at: time)
            let level = Self.rms(buffer)
            Task { @MainActor [weak self] in self?.level = level }
        }
        do {
            engine.prepare()
            try engine.start()
        } catch {
            stop(result: .failed("Could not start listening: \(error.localizedDescription)"))
            return
        }
        state = .listening(elapsed: 0)
        let started = Date()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 250_000_000)
                guard let self, self.isListening else { return }
                self.state = .listening(elapsed: Date().timeIntervalSince(started))
            }
        }
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            guard let self, !Task.isCancelled, self.isListening else { return }
            self.stop(result: .noMatch)
        }
    }

    func clearHistory() {
        history = []
        saveHistory()
    }

    // MARK: - ShazamKit results (main actor)

    fileprivate func found(_ match: SHMatch) {
        guard isListening, let item = match.mediaItems.first else { return }
        let song = RecognizedSong(title: item.title ?? "Unknown title", artist: item.artist ?? "Unknown artist",
                                  album: nil, artworkURL: item.artworkURL, isrc: item.isrc, appleMusicURL: item.appleMusicURL)
        history.removeAll { $0.title == song.title && $0.artist == song.artist }
        history.insert(song, at: 0)
        if history.count > 50 { history.removeLast(history.count - 50) }
        saveHistory()
        stop(result: .matched(song))
    }

    fileprivate func notFound(_ error: Error?) {
        guard isListening, let error else { return }  // no error: keep listening for more audio
        let nsError = error as NSError
        if nsError.domain == SHErrorDomain, nsError.code == SHError.Code.matchAttemptFailed.rawValue {
            stop(result: .failed("""
                ShazamKit is not enabled for this build. Recognition needs the ShazamKit service on the app's \
                bundle ID (Apple Developer › Identifiers), which requires a paid developer account.
                """))
        } else if nsError.domain == SHErrorDomain {
            return  // a signature that matched nothing yet: keep listening until the timeout
        } else {
            stop(result: .failed(error.localizedDescription))
        }
    }

    // MARK: - Private

    private func stop(result: State) {
        timeout?.cancel()
        ticker?.cancel()
        engine.inputNode.removeTap(onBus: 0)
        if engine.isRunning { engine.stop() }
        session = nil
        level = 0
        state = result
        // Back to playback-only so the player keeps its normal routing.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
    }

    private func saveHistory() {
        if let data = try? JSONEncoder().encode(history) { try? data.write(to: historyFile, options: .atomic) }
    }

    private static func microphoneAllowed() async -> Bool {
        await withCheckedContinuation { continuation in
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
            }
        }
    }

    nonisolated private static func rms(_ buffer: AVAudioPCMBuffer) -> Float {
        guard let channel = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return 0 }
        var sum: Float = 0
        for i in 0..<Int(buffer.frameLength) { sum += channel[i] * channel[i] }
        return min(1, (sum / Float(buffer.frameLength)).squareRoot() * 8)
    }
}

/// SHSession's delegate (called off the main thread), forwarding to the service.
final class ShazamRelay: NSObject, SHSessionDelegate {
    weak var owner: SongRecognitionService?

    func session(_ session: SHSession, didFind match: SHMatch) {
        Task { @MainActor [weak owner] in owner?.found(match) }
    }

    func session(_ session: SHSession, didNotFindMatchFor signature: SHSignature, error: Error?) {
        Task { @MainActor [weak owner] in owner?.notFound(error) }
    }
}
