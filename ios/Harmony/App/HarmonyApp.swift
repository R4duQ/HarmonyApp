import SwiftUI
import UIKit

/// Harmony for iOS: the Cyber-Glass shell (Home, Library, Playlists,
/// Discover, Downloads) under a floating mini player and navigation pill.
@main
struct HarmonyApp: App {
    @UIApplicationDelegateAdaptor(HarmonyAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in IncomingLinks.handle(url) }
        }
    }
}

struct RootView: View {
    var body: some View {
        HarmonyShell()
            .tint(CyberGlassColors.cyan)
            .task {
                HarmonyAudioController.shared.start()
                MornyeService.shared.start()
                IosBackgroundTransferGuard.shared.start()
                IosMusicLibraryManager.shared.start()
                LibraryIngest.shared.indexMissingVectors()
            }
    }
}

final class HarmonyAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Background 24-D indexing: registered before launch ends, as iOS requires,
        // and asked for again each time Harmony goes to the background.
        BackgroundIndexing.register()
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification,
                                               object: nil, queue: .main) { _ in
            Task { @MainActor in BackgroundIndexing.scheduleIfNeeded() }
        }
        return true
    }

    /// iOS relaunches Harmony to finish link downloads that ran while it was closed.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == StreamIngestManager.sessionIdentifier else {
            completionHandler()
            return
        }
        Task { @MainActor in
            StreamIngestManager.shared.backgroundCompletionHandler = completionHandler
        }
    }
}

/// Links and files handed to Harmony from other apps.
@MainActor
enum IncomingLinks {
    static func handle(_ url: URL) {
        if MornyeService.shared.handleCallback(url) { return }
        if url.isFileURL {
            importFile(url)
            return
        }
        // harmony://import?url=https://… (from a Shortcut in the share sheet, or Safari)
        guard url.scheme?.lowercased() == "harmony",
              let link = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "url" })?.value
        else { return }
        StreamIngestManager.shared.enqueue(link)
    }

    /// "Open in Harmony" / "Copy to Harmony" from Files, Mail, AirDrop…: copied into Documents/Imported, then added.
    private static func importFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        // Already in our own folder (Files › On My iPhone › Harmony): add it where it is.
        if HarmonyPaths.relativePath(for: url) != nil {
            Task { _ = try? await LibraryIngest.shared.ingest(fileAt: url, source: "Files") }
            return
        }
        let destination = HarmonyPaths.uniqueFile(in: HarmonyPaths.imported, base: url.deletingPathExtension().lastPathComponent,
                                                  extension: url.pathExtension.isEmpty ? "audio" : url.pathExtension)
        do {
            try FileManager.default.copyItem(at: url, to: destination)
        } catch {
            return
        }
        Task {
            guard !HarmonyTranscoder.sniffContainer(atPath: destination.path).isEmpty else {
                try? FileManager.default.removeItem(at: destination)
                return
            }
            _ = try? await LibraryIngest.shared.ingest(fileAt: destination, source: "Imported")
        }
    }
}
