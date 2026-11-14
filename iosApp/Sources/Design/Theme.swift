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
    static let background = Color(hex: 0x0B0D12)
    static let surface = Color(hex: 0x141821)
    static let surfaceElevated = Color(hex: 0x1C2130)
    static let outline = Color(hex: 0x2A3142)
    static let textPrimary = Color(hex: 0xF5F7FA)
    static let textSecondary = Color(hex: 0x9AA3B2)
    static let primary = Color(hex: 0x7C5CFF)   // Accent
    static let cyan = Color(hex: 0x00D4FF)      // Accent2
    static let live = Color(hex: 0xFF3B5C)
    static let success = Color(hex: 0x2ED47A)
    static let warning = Color(hex: 0xFFB020)
    static let star = Color(hex: 0xFFC542)

    static let accentGradient = LinearGradient(colors: [primary, cyan], startPoint: .topLeading, endPoint: .bottomTrailing)
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
