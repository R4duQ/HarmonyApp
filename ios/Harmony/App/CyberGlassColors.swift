import SwiftUI

/// Harmony's Cyber-Glass palette, the same tokens as the Android app's
/// CyberGlassColors (core/ui/.../theme/CyberGlassColors.kt).
enum CyberGlassColors {
    // Surfaces
    static let obsidian = Color(hex: 0x0B0F17)
    static let obsidianDeep = Color(hex: 0x0E131F)
    static let surface = Color(hex: 0x131926)
    static let chip = Color(hex: 0x161F30)
    static let chipBorder = Color(hex: 0x2A3950)
    static let artWell = Color(hex: 0x121A2A)
    static let card = Color(hex: 0x111827)
    static let cardAlpha = 0.92
    static let recessed = Color(hex: 0x1A2333)
    static let devicePill = Color(hex: 0x162A3A)
    static let navGlass = Color(hex: 0x131B2E)
    static let navAlpha = 0.90

    // Accents
    static let cyan = Color(hex: 0x00E5FF)
    static let mint = Color(hex: 0x00F5D4)
    static let azure = Color(hex: 0x00BBF9)
    static let sky = Color(hex: 0x38BDF8)
    static let violet = Color(hex: 0x8B5CF6)
    static let purple = Color(hex: 0xA855F7)
    static let indigo = Color(hex: 0x6D28D9)
    static let indigoDeep = Color(hex: 0x4C1D95)
    static let amber = Color(hex: 0xFBBF24)
    static let danger = Color(hex: 0xF43F5E)

    // Text
    static let textPrimary = Color.white
    static let textSilver = Color(hex: 0xE2E8F0)
    static let textPlaceholder = Color(hex: 0xCBD5E1)
    static let textSecondary = Color(hex: 0x94A3B8)
    static let textQuiet = Color(hex: 0x64748B)

    // Lines
    static let divider = Color.white.opacity(0x14 / 255.0)
    static let thumbRim = cyan.opacity(0x44 / 255.0)
    static let navRim = sky.opacity(0x40 / 255.0)

    // Gradients
    static let page = LinearGradient(colors: [obsidian, obsidianDeep], startPoint: .top, endPoint: .bottom)
    static let selectedChip = LinearGradient(colors: [mint, azure], startPoint: .leading, endPoint: .trailing)
    static let searchRim = LinearGradient(colors: [indigo, indigoDeep], startPoint: .leading, endPoint: .trailing)
    static let iridescent = LinearGradient(colors: [violet, cyan, mint, purple], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let iridescentSoft = LinearGradient(colors: [violet.opacity(0.6), cyan.opacity(0.55), mint.opacity(0.5), purple.opacity(0.55)],
                                               startPoint: .topLeading, endPoint: .bottomTrailing)
    static let iridescentSweep = AngularGradient(colors: [violet, cyan, mint, purple, violet], center: .center)
    static let progress = LinearGradient(colors: [violet, cyan, mint], startPoint: .leading, endPoint: .trailing)
    static let playButton = LinearGradient(colors: [violet, purple], startPoint: .topLeading, endPoint: .bottomTrailing)
}

extension Color {
    /// An opaque sRGB colour from 0xRRGGBB.
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}
