import SwiftUI
import Shared

// Shared building blocks, 1:1 with the Android app (`ui/components/Components.kt`, `TopBar.kt`, `Focus.kt`).

// MARK: - Focus / press

/// Android `Modifier.focusHighlight`: on tvOS the focused element scales (default 1.06) and gets the
/// accent-gradient border; on touch devices a light press feedback.
struct PressStyle: ButtonStyle {
    var radius: CGFloat = Theme.cardRadius
    var scale: CGFloat = 1.06
    var circle = false

    func makeBody(configuration: Configuration) -> some View {
        FocusBody(configuration: configuration, radius: radius, scale: scale, circle: circle)
    }

    private struct FocusBody: View {
        let configuration: Configuration
        let radius: CGFloat
        let scale: CGFloat
        let circle: Bool
        @Environment(\.isFocused) private var isFocused
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            let focused = Theme.isTV && isFocused
            configuration.label
                .opacity(configuration.isPressed && !Theme.isTV ? 0.7 : 1)
                .overlay {
                    if focused {
                        if circle {
                            Circle().strokeBorder(Theme.accentGradient, lineWidth: 2.5)
                        } else {
                            RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Theme.accentGradient, lineWidth: 2.5)
                        }
                    }
                }
                .scaleEffect(focused ? scale : (configuration.isPressed ? 0.98 : 1))
                .animation(.easeOut(duration: 0.18), value: focused)
                .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
        }
    }
}

extension View {
    func pressStyle(radius: CGFloat = Theme.cardRadius, scale: CGFloat = 1.06) -> some View {
        buttonStyle(PressStyle(radius: radius, scale: scale))
    }
}

// MARK: - Artwork

/// Network artwork with initials-on-gradient placeholder (Android `ArtImage`).
struct ArtImage: View {
    let url: String?
    let title: String
    var contentMode: ContentMode = .fill
    var showInitials = true
    /// Largest displayed side in points: the image is decoded at that size × screen scale, no bigger.
    var points: CGFloat = 200
    /// Explicit pixel cap instead of `points` (full-width backdrops).
    var maxPixel: CGFloat?
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        ZStack {
            Theme.surfaceElevated
            CachedImage(url: url, maxPixel: maxPixel ?? points * max(1, displayScale), contentMode: contentMode) {
                ZStack {
                    LinearGradient(colors: [Theme.surfaceElevated, Theme.surface], startPoint: .topLeading, endPoint: .bottomTrailing)
                    if showInitials {
                        Text(Self.initials(title))
                            .font(AppFont.titleLarge)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
            }
        }
        .accessibilityLabel(title)
    }

    static func initials(_ title: String) -> String {
        let parts = title.split(whereSeparator: { $0 == " " || $0 == "-" || $0 == "_" })
            .filter { $0.first.map { $0.isLetter || $0.isNumber } ?? false }
            .prefix(2)
        let s = parts.compactMap { $0.first.map { String($0).uppercased() } }.joined()
        return s.isEmpty ? "•" : s
    }
}

/// A box with a fixed aspect ratio whose image fills it without affecting layout.
struct AspectBox<Content: View>: View {
    let ratio: CGFloat
    @ViewBuilder var content: () -> Content
    var body: some View {
        Color.clear
            .aspectRatio(ratio, contentMode: .fit)
            .overlay { content() }
            .clipped()
    }
}

// MARK: - Cards

struct PosterCard: View {
    let title: String
    let imageURL: String?
    var subtitle: String?
    var rating: String?
    var progress: Double?
    var width: CGFloat?
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: action) {
                AspectBox(ratio: 2.0 / 3.0) {
                    ArtImage(url: imageURL, title: title, points: (width ?? (Theme.isTV ? 240 : 130)) * 1.5)
                }
                .overlay(alignment: .topTrailing) {
                    if let rating {
                        HStack(spacing: 2) {
                            Image(systemName: "star.fill").font(.system(size: 10)).foregroundStyle(Theme.star)
                            Text(rating).font(AppFont.labelMedium).foregroundStyle(.white)
                        }
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 6))
                        .padding(6)
                    }
                }
                .overlay(alignment: .bottom) {
                    if let progress, progress > 0 { ProgressBar(progress: progress).padding(8) }
                }
                .clipShape(RoundedRectangle(cornerRadius: Theme.posterRadius, style: .continuous))
            }
            .pressStyle(radius: Theme.posterRadius)
            Spacer().frame(height: 8)
            Text(title).font(AppFont.titleSmall).foregroundStyle(Theme.textPrimary).lineLimit(1)
            if let subtitle {
                Text(subtitle).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
            }
        }
        .frame(width: width)
    }
}

/// 16:9 card for "continue watching" and channels on Home (Android `WideCard`).
struct WideCard: View {
    let title: String
    let imageURL: String?
    var subtitle: String?
    var progress: Double?
    var live = false
    var fitImage = false
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: action) {
                AspectBox(ratio: 16.0 / 9.0) {
                    ZStack {
                        Theme.surfaceElevated
                        ArtImage(url: imageURL, title: title, contentMode: fitImage ? .fit : .fill, points: fitImage ? 180 : 300)
                            .padding(fitImage ? 20 : 0)
                        LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
                    }
                }
                .overlay(alignment: .topLeading) { if live { LiveBadge().padding(8) } }
                .overlay(alignment: .bottom) {
                    if let progress, progress > 0 { ProgressBar(progress: progress).padding(10) }
                }
                .clipShape(RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous))
            }
            .pressStyle(radius: Theme.cardRadius)
            Spacer().frame(height: 8)
            Text(title).font(AppFont.titleSmall).foregroundStyle(Theme.textPrimary).lineLimit(1)
            if let subtitle {
                Text(subtitle).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
            }
        }
    }
}

// MARK: - Small pieces

struct ProgressBar: View {
    let progress: Double
    var height: CGFloat = 4
    var color: Color = Theme.primary

    var body: some View {
        // A tiny Layout instead of a GeometryReader: no extra layout pass in list cells.
        ProgressFillLayout(fraction: max(0, min(1, progress.isFinite ? progress : 0))) {
            Capsule().fill(Color.white.opacity(0.18))
            Capsule().fill(color)
        }
        .frame(height: height)
        .accessibilityElement()
        .accessibilityValue("\(Int((progress.isFinite ? progress : 0) * 100))%")
    }
}

/// Track (first subview) fills the proposed width; the fill (second subview) gets `fraction` of it.
private struct ProgressFillLayout: Layout {
    let fraction: Double

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (i, s) in subviews.enumerated() {
            let w = i == 0 ? bounds.width : bounds.width * fraction
            s.place(at: bounds.origin, proposal: ProposedViewSize(width: w, height: bounds.height))
        }
    }
}

/// Gives its content `fraction` of the proposed width, leading-aligned (replaces GeometryReader + width * f).
struct FractionWidth: Layout {
    let fraction: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        let h = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width * fraction, height: proposal.height)).height }.max() ?? 0
        return CGSize(width: width, height: h)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for s in subviews {
            s.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width * fraction, height: bounds.height))
        }
    }
}

/// Progress of an airing programme, refreshed once a minute by itself: the row around it never re-renders.
struct EpgProgressBar: View {
    let startAt: Int64
    let endAt: Int64
    var height: CGFloat = 3

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            ProgressBar(progress: Self.progress(startAt, endAt, now: Int64(context.date.timeIntervalSince1970 * 1000)), height: height)
        }
    }

    static func progress(_ start: Int64, _ end: Int64, now: Int64) -> Double {
        guard end > start else { return 0 }
        return min(1, max(0, Double(now - start) / Double(end - start)))
    }
}

struct LiveBadge: View {
    var body: some View {
        Text("LIVE")
            .font(AppFont.labelSmall).tracking(0.6)
            .foregroundStyle(.white)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(Theme.live, in: Capsule())
    }
}

struct ChipItem: Identifiable, Hashable {
    let id: String
    let title: String
}

/// Horizontally scrolling, fully rounded chips; the selected one uses the accent gradient.
struct CategoryChips: View {
    let items: [ChipItem]
    let selected: String?
    let onSelect: (String) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(items) { item in
                        let isSel = item.id == selected
                        Button { onSelect(item.id) } label: {
                            Text(item.title)
                                .font(AppFont.labelLarge)
                                .foregroundStyle(isSel ? Theme.onAccent : Theme.textSecondary)
                                .lineLimit(1)
                                .padding(.horizontal, 16).padding(.vertical, 9)
                                .background {
                                    if isSel { Capsule().fill(Theme.accentGradient) } else { Capsule().fill(Theme.surfaceElevated) }
                                }
                                .contentShape(Capsule())
                        }
                        .buttonStyle(PressStyle(radius: 40, scale: 1.04))
                        .id(item.id)
                    }
                }
                .padding(.horizontal, Theme.gutter)
                .padding(.vertical, Theme.isTV ? 12 : 2)
            }
            .onAppear { if let selected { proxy.scrollTo(selected, anchor: .center) } }
            .onChange(of: selected) { _, id in if let id { withAnimation { proxy.scrollTo(id, anchor: .center) } } }
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }
}

struct SectionHeader: View {
    let title: String
    var body: some View {
        Text(title)
            .font(AppFont.titleLarge)
            .foregroundStyle(Theme.textPrimary)
            .padding(.horizontal, Theme.gutter)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - Buttons

struct PrimaryButton: View {
    let text: String
    var icon: String?
    var enabled = true
    var fullWidth = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon).font(.system(size: 17, weight: .semibold)) }
                Text(text).font(AppFont.labelLarge.weight(.bold)).lineLimit(1).minimumScaleFactor(0.85)
            }
            .foregroundStyle(enabled ? Theme.onAccent : Color.white)
            .padding(.horizontal, 22).padding(.vertical, 13)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .background {
                if enabled { Capsule().fill(Theme.accentGradient) } else { Capsule().fill(Theme.outline) }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(PressStyle(radius: 40, scale: 1.04))
        .disabled(!enabled)
    }
}

struct SecondaryButton: View {
    let text: String
    var icon: String?
    var tint: Color = Theme.textPrimary
    var enabled = true
    var fullWidth = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon {
                    Image(systemName: icon).font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(enabled ? tint : Theme.textSecondary)
                }
                if !text.isEmpty {
                    Text(text).font(AppFont.labelLarge).lineLimit(1).minimumScaleFactor(0.85)
                        .foregroundStyle(enabled ? Theme.textPrimary : Theme.textSecondary)
                }
            }
            // Icon-only (favourite): narrower, so "Riproduci · Scarica · ♡" fits one row on a 375 pt phone.
            .padding(.horizontal, text.isEmpty ? 15 : 18).padding(.vertical, 13)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .background(Theme.surfaceElevated, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(PressStyle(radius: 40, scale: 1.04))
        .disabled(!enabled)
    }
}

/// Round icon button (Android `RoundIconButton`), 44 pt touch target.
struct RoundIconButton: View {
    let symbol: String
    let label: String
    var size: CGFloat = 44
    var background: Color = .clear
    var tint: Color = Theme.textPrimary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: size, height: size)
                .background(background, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(PressStyle(radius: size / 2, scale: 1.1, circle: true))
        .accessibilityLabel(label)
    }
}

struct EmptyState<Action: View>: View {
    let symbol: String
    let text: String
    @ViewBuilder var action: () -> Action

    var body: some View {
        VStack(spacing: 0) {
            Image(systemName: symbol)
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(Theme.textSecondary)
                .frame(width: 72, height: 72)
                .background(Theme.surfaceElevated, in: Circle())
            Spacer().frame(height: 16)
            Text(text)
                .font(AppFont.bodyLarge)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if Action.self != EmptyView.self {
                Spacer().frame(height: 20)
                action()
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

extension EmptyState where Action == EmptyView {
    init(symbol: String, text: String) {
        self.init(symbol: symbol, text: text) { EmptyView() }
    }
}

struct LoadingBox: View {
    var body: some View {
        ProgressView()
            .controlSize(.large)
            .tint(Theme.primary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Top bar

/// Android `AppTopBar`: 64 pt row under the status bar, optional back arrow, title, trailing actions.
struct AppTopBar<Actions: View>: View {
    let title: String
    var onBack: (() -> Void)?
    var large = false
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        HStack(spacing: 4) {
            if let onBack {
                RoundIconButton(symbol: "arrow.left", label: "Indietro", action: onBack)
            } else {
                Spacer().frame(width: 8)
            }
            Text(title)
                .font(large ? AppFont.headlineMedium : AppFont.titleLarge)
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.leading, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(1)
            actions()
        }
        .frame(minHeight: 64)
        .padding(.horizontal, 8)
    }
}

extension AppTopBar where Actions == EmptyView {
    init(title: String, onBack: (() -> Void)? = nil, large: Bool = false) {
        self.init(title: title, onBack: onBack, large: large) { EmptyView() }
    }
}

// MARK: - Dialog (Android AlertDialog look)

struct DialogCard<Content: View, Buttons: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content
    @ViewBuilder var buttons: () -> Buttons

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(AppFont.headlineSmall).foregroundStyle(Theme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            // Short screens (iPhone in landscape: ~375 pt, large text): the content scrolls between the title and
            // the buttons instead of the card running off the top and bottom of the screen.
            ViewThatFits(in: .vertical) {
                content()
                ScrollView { content() }.scrollBounceBehavior(.basedOnSize)
            }
            // Buttons side by side, or stacked (right-aligned) when they don't fit the card's width.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    buttons()
                }
                VStack(alignment: .trailing, spacing: 8) { buttons() }
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .padding(24)
        .frame(maxWidth: Theme.isTV ? 760 : 420, alignment: .leading)
        .background(Theme.surfaceElevated, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .padding(24)
    }
}

/// Android `TextButton`.
struct TextAction: View {
    let title: String
    var color: Color = Theme.primary
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(AppFont.labelLarge).foregroundStyle(color)
                .padding(.horizontal, 12).padding(.vertical, 10)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(radius: 20, scale: 1.04))
    }
}

/// Full-screen scrim + centered dialog.
struct DialogHost<Dialog: View>: View {
    let onDismiss: () -> Void
    @ViewBuilder var dialog: () -> Dialog
    var body: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()
                .onTapGesture(perform: onDismiss)
            dialog()
        }
        .transition(.opacity)
    }
}

/// Radio row used in option dialogs.
struct RadioRow: View {
    let label: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(selected ? Theme.primary : Theme.textSecondary)
                Text(label).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 10).padding(.horizontal, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(radius: 10, scale: 1.01))
    }
}

#if os(iOS)
import AVKit

/// AirPlay route picker (the iOS counterpart of Android's Cast button).
struct AirPlayButton: UIViewRepresentable {
    var tint: UIColor = .white
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = tint
        v.activeTintColor = UIColor(Theme.accent2)
        v.prioritizesVideoDevices = true
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
#endif

// MARK: - Flow layout (Compose FlowRow)

struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(ProposedViewSize(width: width, height: nil))
            if x > 0 && x + size.width > width { x = 0; y += lineHeight + lineSpacing; lineHeight = 0 }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: width.isFinite ? width : maxX, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += lineHeight + lineSpacing; lineHeight = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(width: min(size.width, bounds.width), height: size.height))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

/// Android `BackdropHeader`: artwork with the bottom scrim.
struct BackdropHeader: View {
    let image: String?
    let title: String
    let height: CGFloat
    var body: some View {
        Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .overlay { ArtImage(url: image, title: title, showInitials: false, maxPixel: 1400) }
            .clipped()
            .overlay(Theme.scrimBottom)
    }
}

/// "Label  value" line used on detail pages (Android `MetaLine`).
struct MetaLine: View {
    let label: String
    let value: String?
    var body: some View {
        if let value, !value.trimmed.isEmpty {
            (Text("\(label)  ").foregroundStyle(Theme.textSecondary) + Text(value).foregroundStyle(Theme.textPrimary))
                .font(AppFont.bodyMedium)
                .lineLimit(3)
                .padding(.vertical, 3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

#if os(iOS)
enum DownloadUI {
    static func icon(_ d: Download?) -> String {
        switch d?.status {
        case nil: return "arrow.down.to.line"
        case DownloadStatus.queued?, DownloadStatus.running?: return "arrow.down.circle.dotted"
        case DownloadStatus.paused?: return "pause.circle.fill"
        case DownloadStatus.completed?: return "checkmark.circle.fill"
        default: return "exclamationmark.circle"
        }
    }

    static func label(_ d: Download?) -> String {
        switch d?.status {
        case nil: return "Scarica"
        case DownloadStatus.queued?: return "In attesa"
        case DownloadStatus.running?: return "Download \(Int((d?.fraction ?? 0) * 100))%"
        case DownloadStatus.paused?: return "In pausa"
        case DownloadStatus.completed?: return "Scaricato"
        default: return "Riprova"
        }
    }

    static func tint(_ d: Download?) -> Color {
        switch d?.status {
        case DownloadStatus.completed?: return Theme.success
        case DownloadStatus.failed?: return Theme.live
        default: return Theme.textPrimary
        }
    }

    /// Same state machine as Android: start / resume / pause.
    @MainActor
    static func tap(_ d: Download?, app: AppModel, start: @escaping () async throws -> Void) {
        Task {
            do {
                switch d?.status {
                case nil:
                    try await start()
                    app.showToast("Download avviato")
                case DownloadStatus.paused?, DownloadStatus.failed?:
                    if let d { try await app.core.downloads.resume(id: d.id) }
                case DownloadStatus.queued?, DownloadStatus.running?:
                    if let d { try await app.core.downloads.pause(id: d.id) }
                default: break
                }
            } catch {
                app.showToast(readableError(error))
            }
        }
    }
}
#endif
