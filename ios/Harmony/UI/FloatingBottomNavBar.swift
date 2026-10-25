import SwiftUI

/// The frosted-glass navigation pill: five sections, the selected one glowing cyan,
/// and the number of running downloads on Downloads.
struct FloatingBottomNavBar: View {
    @Binding var selection: HarmonyTab
    let downloadsBadge: Int
    @Namespace private var glow

    var body: some View {
        HStack(spacing: 0) {
            ForEach(HarmonyTab.allCases) { tab in
                let selected = tab == selection
                Button {
                    guard tab != selection else { return }
                    UISelectionFeedbackGenerator().selectionChanged()
                    withAnimation(.spring(response: 0.34, dampingFraction: 0.8)) { selection = tab }
                } label: {
                    VStack(spacing: 3) {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: tab.icon)
                                .font(.system(size: 18, weight: selected ? .bold : .semibold))
                                .frame(width: 30, height: 24)
                                .shadow(color: selected ? CyberGlassColors.cyan.opacity(0.8) : .clear, radius: 8)
                            if tab == .downloads, downloadsBadge > 0 {
                                Text(downloadsBadge > 99 ? "99+" : "\(downloadsBadge)")
                                    .font(.system(size: 9.5, weight: .heavy, design: .rounded))
                                    .foregroundStyle(CyberGlassColors.obsidian)
                                    .padding(.horizontal, 4.5)
                                    .frame(minWidth: 16, minHeight: 16)
                                    .background(CyberGlassColors.selectedChip, in: Capsule())
                                    .shadow(color: CyberGlassColors.mint.opacity(0.7), radius: 5)
                                    .offset(x: 9, y: -6)
                            }
                        }
                        Text(tab.title)
                            .font(.system(size: 10, weight: selected ? .bold : .medium))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(selected ? CyberGlassColors.cyan : CyberGlassColors.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background {
                        if selected {
                            Capsule()
                                .fill(CyberGlassColors.cyan.opacity(0.12))
                                .overlay(Capsule().strokeBorder(CyberGlassColors.cyan.opacity(0.35), lineWidth: 1))
                                .matchedGeometryEffect(id: "selected", in: glow)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab == .downloads && downloadsBadge > 0 ? "\(tab.title), \(downloadsBadge) running" : tab.title)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(5)
        .background(.ultraThinMaterial, in: Capsule())
        .background(CyberGlassColors.navGlass.opacity(0.55), in: Capsule())
        .overlay(Capsule().strokeBorder(CyberGlassColors.navRim, lineWidth: 1))
        .shadow(color: .black.opacity(0.45), radius: 18, y: 8)
        .environment(\.colorScheme, .dark)
    }
}
