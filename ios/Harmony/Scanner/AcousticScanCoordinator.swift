import Foundation
import UIKit

/// Indexes the library's 24-D acoustic vectors in the background without
/// warming the phone or draining it.
///
/// Scans run on a utility-priority queue, at most two at a time, and the pace
/// follows the device:
///
/// | Condition                         | Concurrent scans |
/// |-----------------------------------|------------------|
/// | thermal `.nominal`                | 2                |
/// | thermal `.fair`, or Low Power Mode| 1                |
/// | thermal `.serious`                | paused           |
/// | thermal `.critical`               | paused, in-flight scans cancelled |
/// | battery under 20% and not charging| paused           |
///
/// Each scan reads only three 1.5 s windows (FastAcousticScanner_iOS), so a
/// song costs a few tens of milliseconds of decoding.
@MainActor
final class AcousticScanCoordinator: ObservableObject {
    struct Item: Hashable, Sendable {
        let trackId: Int64
        let url: URL
    }

    enum PauseReason: String {
        case thermal = "The phone is warm, so indexing is paused."
        case critical = "The phone is hot, so indexing stopped for now."
        case battery = "The battery is low, so indexing is paused until it charges."
    }

    struct Progress: Equatable {
        var total = 0
        var done = 0
        var failed = 0
        var pauseReason: PauseReason?
        var isActive: Bool { done + failed < total }
        var fraction: Double { total == 0 ? 1 : Double(done + failed) / Double(total) }
    }

    @Published private(set) var progress = Progress()

    /// A measured vector (24 floats), on the main actor.
    var onVector: ((Int64, [Float]) -> Void)?
    /// A song that could not be measured (it is not retried this session).
    var onFailure: ((Int64, String) -> Void)?

    private let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "Harmony acoustic scanner"
        queue.qualityOfService = .utility
        queue.maxConcurrentOperationCount = 2
        return queue
    }()
    private var queued = Set<Int64>()
    private var observers: [NSObjectProtocol] = []

    init() {
        let center = NotificationCenter.default
        let refresh: @Sendable (Notification) -> Void = { [weak self] _ in
            Task { @MainActor in self?.applyDeviceState() }
        }
        observers.append(center.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main, using: refresh))
        observers.append(center.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main, using: refresh))
        UIDevice.current.isBatteryMonitoringEnabled = true
        observers.append(center.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main, using: refresh))
        observers.append(center.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main, using: refresh))
        applyDeviceState()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        queue.cancelAllOperations()
    }

    /// Adds songs to measure; ones already queued this session are ignored.
    func enqueue(_ items: [Item]) {
        let fresh = items.filter { queued.insert($0.trackId).inserted }
        guard !fresh.isEmpty else { return }
        if !progress.isActive { progress = Progress(pauseReason: progress.pauseReason) }
        progress.total += fresh.count
        for item in fresh {
            let operation = BlockOperation()
            operation.addExecutionBlock { [weak operation, weak self] in
                guard let operation, !operation.isCancelled else { return }
                let result = HarmonyFastAcousticScanner.scanFile(at: item.url) { operation.isCancelled }
                let vector: [Float]? = result.vector.map { data in
                    data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
                }
                let reason = result.failureReason ?? "Unknown"
                let cancelled = operation.isCancelled
                Task { @MainActor in
                    guard let self else { return }
                    if cancelled {
                        // Thermal stop: allow it to be queued again later.
                        self.queued.remove(item.trackId)
                        self.progress.total = max(self.progress.done + self.progress.failed, self.progress.total - 1)
                        return
                    }
                    if let vector, vector.count == Int(HarmonyAcousticDims) {
                        self.progress.done += 1
                        self.onVector?(item.trackId, vector)
                    } else {
                        self.progress.failed += 1
                        self.onFailure?(item.trackId, reason)
                    }
                }
            }
            queue.addOperation(operation)
        }
    }

    /// Stops everything (the library was replaced, the app is closing).
    func cancelAll() {
        queue.cancelAllOperations()
        queued.removeAll()
        progress = Progress(pauseReason: progress.pauseReason)
    }

    private func applyDeviceState() {
        let thermal = ProcessInfo.processInfo.thermalState
        let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        let device = UIDevice.current
        let charging = device.batteryState == .charging || device.batteryState == .full
        let lowBattery = device.batteryLevel >= 0 && device.batteryLevel < 0.20 && !charging

        switch thermal {
        case .critical:
            progress.pauseReason = .critical
            queue.isSuspended = true
            // Stop the in-flight scans now; they are re-queued when it cools down.
            queue.operations.filter(\.isExecuting).forEach { $0.cancel() }
        case .serious:
            progress.pauseReason = .thermal
            queue.isSuspended = true
        default:
            if lowBattery {
                progress.pauseReason = .battery
                queue.isSuspended = true
            } else {
                progress.pauseReason = nil
                queue.maxConcurrentOperationCount = (thermal == .fair || lowPower) ? 1 : 2
                queue.isSuspended = false
            }
        }
    }
}
