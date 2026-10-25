import SwiftUI

/// The five sections of the floating navigation pill.
enum HarmonyTab: String, CaseIterable, Identifiable {
    case home, library, playlists, discover, downloads

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "Home"
        case .library: return "Library"
        case .playlists: return "Playlists"
        case .discover: return "Discover"
        case .downloads: return "Downloads"
        }
    }

    var icon: String {
        switch self {
        case .home: return "house.fill"
        case .library: return "music.note.list"
        case .playlists: return "rectangle.stack.fill"
        case .discover: return "sparkles"
        case .downloads: return "arrow.down.circle.fill"
        }
    }
}

/// Where the app is: the section, and the full-screen player and its panels.
@MainActor
final class HarmonyRouter: ObservableObject {
    static let shared = HarmonyRouter()

    @Published var tab: HarmonyTab = .home
    @Published var showNowPlaying = false
    @Published var showEqualizer = false
    @Published var showQueue = false
    /// The Smart Shuffle Diagnostic Brain HUD over Now Playing.
    @Published var showBrainHUD = false

    private init() {}
}

/**
 * Harmony's Cyber-Glass shell: the five sections, with the floating mini
 * player and the frosted navigation pill over them. Sections stay alive once
 * opened, so each keeps its scroll position and searches.
 */
struct HarmonyShell: View {
    @ObservedObject private var router = HarmonyRouter.shared
    @ObservedObject private var player = HarmonyAudioController.shared
    @ObservedObject private var transferGuard = IosBackgroundTransferGuard.shared
    @State private var opened: Set<HarmonyTab> = [.home]

    var body: some View {
        ZStack(alignment: .bottom) {
            CyberGlassColors.page.ignoresSafeArea()
            ZStack {
                ForEach(HarmonyTab.allCases) { tab in
                    if opened.contains(tab) {
                        section(tab)
                            .opacity(router.tab == tab ? 1 : 0)
                            .allowsHitTesting(router.tab == tab)
                            .accessibilityHidden(router.tab != tab)
                    }
                }
            }
            VStack(spacing: 10) {
                if player.currentEntry != nil {
                    FloatingMiniPlayerCard()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                FloatingBottomNavBar(selection: $router.tab, downloadsBadge: transferGuard.activeDownloads)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 4)
            .animation(.spring(response: 0.42, dampingFraction: 0.82), value: player.currentEntry != nil)
            .ignoresSafeArea(.keyboard)
        }
        .onChange(of: router.tab) { tab in opened.insert(tab) }
        .fullScreenCover(isPresented: $router.showNowPlaying) {
            NowPlayingScreen()
        }
    }

    @ViewBuilder
    private func section(_ tab: HarmonyTab) -> some View {
        switch tab {
        case .home: HomeScreen()
        case .library: LibraryScreen()
        case .playlists: PlaylistsScreen()
        case .discover: DiscoverScreen()
        case .downloads: DownloadsScreen()
        }
    }
}

/// Room at the bottom of a scrolling section for the floating player and the pill above it.
struct HarmonyChromeInset: ViewModifier {
    @ObservedObject private var player = HarmonyAudioController.shared

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom) {
            Color.clear.frame(height: player.currentEntry != nil ? 196 : 92)
        }
    }
}

extension View {
    /// Keeps the last rows of a section clear of the mini player and the navigation pill.
    func harmonyChromeInset() -> some View { modifier(HarmonyChromeInset()) }
}
