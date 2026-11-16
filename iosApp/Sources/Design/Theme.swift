import SwiftUI

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xff) / 255,
                  green: Double((hex >> 8) & 0xff) / 255,
                  blue: Double(hex & 0xff) / 255,
                  opacity: opacity)
    }
}

/// Design tokens, identical to the Android app (`ui/theme/Theme.kt` → Palette / Dimens / Typography).
enum Theme {
    // Palette
    // "Oro e notte" (same colours as the Origami app icon): navy surfaces, gold → amber accent.
    static let background = Color(hex: 0x0A0E1F)
    static let surface = Color(hex: 0x121831)
    static let surfaceElevated = Color(hex: 0x1A2140)
    static let outline = Color(hex: 0x2B3456)
    static let textPrimary = Color(hex: 0xF5F7FA)
    static let textSecondary = Color(hex: 0x9DA5BF)
    static let primary = Color(hex: 0xFFA21F)   // Accent (amber)
    static let accent2 = Color(hex: 0xFFD25C)   // Accent2 (gold)
    static let accentDeep = Color(hex: 0xD1650A)
    /// Programme on air in the guide: a lighter navy (amber at low alpha turns brown on navy).
    static let surfaceLive = Color(hex: 0x283563)
    /// Text and icons drawn on the accent (white would not read on gold).
    static let onAccent = Color(hex: 0x1B1204)
    static let live = Color(hex: 0xFF3B5C)
    static let success = Color(hex: 0x2ED47A)
    static let warning = Color(hex: 0xFFB020)
    static let star = Color(hex: 0xFFC542)

    static let accentGradient = LinearGradient(colors: [accent2, primary], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let scrimBottom = LinearGradient(colors: [.clear, background.opacity(0.85), background], startPoint: .top, endPoint: .bottom)
    static let scrimLeft = LinearGradient(colors: [background, background.opacity(0.6), .clear], startPoint: .leading, endPoint: .trailing)

    // Dimens
    static let cardRadius: CGFloat = 16
    static let posterRadius: CGFloat = 12
    static let gutter: CGFloat = 16
    static let section: CGFloat = 24

    #if os(tvOS)
    static let isTV = true
    #else
    static let isTV = false
    #endif

    static let quick = Animation.easeOut(duration: 0.18)
}

/// Android Material type scale mapped onto Dynamic Type text styles (same point sizes ±1, scales with the user's text size).
enum AppFont {
    static let displaySmall = Font.largeTitle.weight(.heavy)      // 34
    static let headlineMedium = Font.title.bold()                  // 28
    static let headlineSmall = Font.title2.bold()                  // 22
    static let titleLarge = Font.title3.bold()                     // 20
    static let titleMedium = Font.callout.weight(.semibold)        // 16
    static let titleSmall = Font.subheadline.weight(.semibold)     // 14
    static let bodyLarge = Font.callout                            // 16
    static let bodyMedium = Font.subheadline                       // 14
    static let bodySmall = Font.caption                            // 12
    static let labelLarge = Font.subheadline.weight(.semibold)     // 14
    static let labelMedium = Font.caption.weight(.semibold)        // 12
    static let labelSmall = Font.caption2.bold()                   // 10
}

/// Android `Format` helpers (ui texts must match the Android app).
enum AFormat {
    static func rating(_ r: Double?) -> String? {
        guard let r, r > 0 else { return nil }
        return String(format: "%.1f", r <= 5 ? r * 2 : r).replacingOccurrences(of: ".", with: ",")
    }

    /// 01:23:45 or 23:45
    static func duration(ms: Int64) -> String { Fmt.clock(Double(max(0, ms)) / 1000) }

    /// "12,4 MB/s", "850 KB/s"
    static func speed(_ bps: Int64) -> String {
        let v = Double(bps)
        let s = v >= 1_048_576 ? String(format: "%.1f MB/s", v / 1_048_576) : String(format: "%.0f KB/s", v / 1024)
        return s.replacingOccurrences(of: ".", with: ",")
    }

    /// "40 s rimanenti", "12 min rimanenti", "1 h 05 min rimanenti"
    static func eta(_ seconds: Int64) -> String {
        if seconds < 60 { return "\(seconds) s rimanenti" }
        if seconds < 3600 { return "\((seconds + 59) / 60) min rimanenti" }
        return String(format: "%d h %02d min rimanenti", seconds / 3600, (seconds % 3600) / 60)
    }

    static func bytes(_ b: Int64) -> String {
        let gb: Double = 1_073_741_824, mb: Double = 1_048_576, kb: Double = 1024
        let v = Double(b)
        let s: String
        if v >= gb { s = String(format: "%.2f GB", v / gb) }
        else if v >= mb { s = String(format: "%.0f MB", v / mb) }
        else if v >= kb { s = String(format: "%.0f KB", v / kb) }
        else { return "\(b) B" }
        return s.replacingOccurrences(of: ".", with: ",")
    }
}

extension View {
    /// Standard screen background.
    func screenBackground() -> some View {
        background(Theme.background.ignoresSafeArea())
    }

    /// Screens draw their own Android-style top bars.
    @ViewBuilder
    func hideSystemNavigationBar() -> some View {
        #if os(iOS)
        toolbar(.hidden, for: .navigationBar)
        #else
        toolbar(.hidden, for: .automatic)
        #endif
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// The StePlayer mark ("Origami"): three folded facets on a navy squircle, same drawing as the app icon.
struct BrandMark: View {
    var size: CGFloat

    var body: some View {
        Canvas { ctx, sz in
            let s = sz.width / 100
            let bg = Path(roundedRect: CGRect(origin: .zero, size: sz), cornerRadius: 23 * s, style: .continuous)
            ctx.fill(bg, with: .radialGradient(Gradient(colors: [Color(hex: 0x1C2550), Color(hex: 0x0A0F26)]),
                                               center: CGPoint(x: 30 * s, y: 20 * s), startRadius: 0, endRadius: 100 * s))
            func facet(_ points: [CGPoint], _ color: Color) {
                var p = Path()
                p.addLines(points.map { CGPoint(x: $0.x * s, y: $0.y * s) })
                p.closeSubpath()
                ctx.fill(p, with: .color(color))
                ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 3 * s, lineJoin: .round))
            }
            facet([CGPoint(x: 33, y: 23), CGPoint(x: 77, y: 50), CGPoint(x: 46, y: 50)], Theme.accent2)
            facet([CGPoint(x: 33, y: 77), CGPoint(x: 77, y: 50), CGPoint(x: 46, y: 50)], Theme.primary)
            facet([CGPoint(x: 33, y: 23), CGPoint(x: 46, y: 50), CGPoint(x: 33, y: 77)], Theme.accentDeep)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
