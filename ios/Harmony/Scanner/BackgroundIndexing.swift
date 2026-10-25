import BackgroundTasks
import Foundation
import UIKit

/**
 * Lets iOS finish the 24-D library indexing while Harmony is closed.
 *
 *  - A processing task (`processing` background mode) runs when iOS chooses,
 *    typically at night with the phone charging and locked. It may last
 *    several minutes, enough for a large library.
 *  - An app refresh task (`fetch` background mode) gets about 30 seconds now
 *    and then, for a few songs at a time.
 *
 * Both measure only the songs still missing their vector, through the same
 * AcousticScanCoordinator as in the foreground, so its thermal and battery
 * rules apply. When iOS takes the time back, the scans in flight are
 * cancelled and simply picked up next time. Both tasks are scheduled again
 * each time Harmony goes to the background, as long as songs are left.
 */
@MainActor
enum BackgroundIndexing {
    /// Listed in Info.plist under BGTaskSchedulerPermittedIdentifiers (project.yml).
    static let processingIdentifier = "com.harmony.app.ios.index24d"
    static let refreshIdentifier = "com.harmony.app.ios.refresh"

    private static var current: Run?

    /// Must run before the app finishes launching (HarmonyAppDelegate).
    static func register() {
        for identifier in [processingIdentifier, refreshIdentifier] {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
                Task { @MainActor in start(task) }
            }
        }
    }

    /// Asks iOS for time later when songs are still missing their vector.
    static func scheduleIfNeeded() {
        guard !LibraryIngest.shared.store.tracksWithoutVectors().isEmpty else { return }
        let processing = BGProcessingTaskRequest(identifier: processingIdentifier)
        processing.requiresExternalPower = true
        processing.requiresNetworkConnectivity = false
        let refresh = BGAppRefreshTaskRequest(identifier: refreshIdentifier)
        refresh.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        // Fails on the Simulator and when Background App Refresh is off; the
        // foreground indexing covers those cases.
        try? BGTaskScheduler.shared.submit(processing)
        try? BGTaskScheduler.shared.submit(refresh)
    }

    private static func start(_ task: BGTask) {
        current?.finish(success: false)
        let run = Run(task: task)
        current = run
        task.expirationHandler = { [weak run] in
            Task { @MainActor in run?.expire() }
        }
        run.begin()
    }

    fileprivate static func ended(_ run: Run) {
        if current === run { current = nil }
        scheduleIfNeeded()
    }

    /// One background task: queue what is missing, then wait until it is done or iOS ends it.
    @MainActor
    fileprivate final class Run {
        private let task: BGTask
        private var finished = false

        init(task: BGTask) {
            self.task = task
        }

        func begin() {
            let ingest = LibraryIngest.shared
            ingest.indexMissingVectors()
            Task { @MainActor [weak self] in
                // The scans report on the main actor; look once a second.
                while let self, !self.finished {
                    let progress = ingest.scanner.progress
                    if !progress.isActive {
                        self.finish(success: true)
                        return
                    }
                    if progress.pauseReason != nil {
                        // Too warm or the battery is low: hand the time back.
                        self.finish(success: false)
                        return
                    }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
        }

        func expire() {
            LibraryIngest.shared.scanner.cancelAll()
            finish(success: false)
        }

        func finish(success: Bool) {
            guard !finished else { return }
            finished = true
            task.setTaskCompleted(success: success)
            BackgroundIndexing.ended(self)
        }
    }
}
