import SwiftUI

/// Harmony for iOS.
///
/// This first build boots into the engine check: it runs the shared C++
/// equalizer and Smart Shuffle engines on the iPhone and shows their verdicts,
/// so the toolchain and the sideloaded build are proven before the player
/// (library, playback, mini player, equalizer) is layered on top.
@main
struct HarmonyApp: App {
    var body: some Scene {
        WindowGroup {
            EngineCheckView()
                .preferredColorScheme(.dark)
        }
    }
}
