import SwiftUI
import Shared

/// Bottom-bar / rail destinations (Android `Tab`).
enum AppTab: String, CaseIterable, Hashable {
    case home, live, movies, series, downloads

    var title: String {
        switch self {
        case .home: return "Home"
        case .live: return "Live"
        case .movies: return "Film"
        case .series: return "Serie"
        case .downloads: return "Download"
        }
    }

    var symbol: String {
        switch self {
        case .home: return "house.fill"
        case .live: return "tv"
        case .movies: return "film"
        case .series: return "play.rectangle.on.rectangle"
        case .downloads: return "arrow.down.to.line"
        }
    }

    static var visible: [AppTab] {
        Theme.isTV ? allCases.filter { $0 != .downloads } : allCases
    }
}

/// Full-screen destinations pushed over the shell (Android `Routes`).
enum Route: Hashable {
    case search
    case settings
    case movie(sourceId: Int64, id: String)
    case series(sourceId: Int64, id: String)
    case guide(sourceId: Int64, categoryId: String)
    case categories(sourceId: Int64, type: String)
    case connectionTest(sourceId: Int64)
    case subtitles
    case addSource
    case editSource(id: Int64)
    case pairing
    case sendToTv
}

/// Root of the ready app: a navigation stack (custom top bars) hosting the tab shell.
struct MainShellRoot: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.path) {
            MainShell()
                .hideSystemNavigationBar()
                .navigationDestination(for: Route.self) { route in
                    destination(route)
                        .hideSystemNavigationBar()
                        .screenBackground()
                }
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .search: SearchView()
        case .settings: SettingsView()
        case let .movie(sid, id): MovieDetailView(sourceId: sid, id: id)
        case let .series(sid, id): SeriesDetailView(sourceId: sid, id: id)
        case let .guide(sid, cat): GuideView(sourceId: sid, categoryId: cat)
        case let .categories(sid, type): CategoryVisibilityView(sourceId: sid, typeKey: type)
        case let .connectionTest(sid): ConnectionTestView(sourceId: sid)
        case .subtitles: SubtitleSettingsView()
        case .addSource: AddSourceView(mode: .add)
        case let .editSource(id): AddSourceView(mode: .edit(id))
        case .pairing: PairingScreen()
        case .sendToTv:
            #if os(iOS)
            SendToTvView()
            #else
            EmptyView()
            #endif
        }
    }
}

/// Bottom bar on phones, side rail on tablets and TV (Android `MainShell`).
struct MainShell: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var wide: Bool { Theme.isTV || sizeClass == .regular }

    var body: some View {
        Group {
            if wide {
                HStack(spacing: 0) {
                    NavRail()
                    content.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else {
                content
                    .safeAreaInset(edge: .bottom, spacing: 0) { BottomBar() }
            }
        }
        .screenBackground()
    }

    @ViewBuilder private var content: some View {
        ZStack {
            switch app.tab {
            case .home: HomeView()
            case .live: LiveView()
            case .movies: CatalogView(kind: .movies)
            case .series: CatalogView(kind: .series)
            case .downloads:
                #if os(iOS)
                DownloadsView()
                #else
                EmptyView()
                #endif
            }
        }
        .animation(.easeInOut(duration: 0.18), value: app.tab)
    }
}

private struct BottomBar: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.visible, id: \.self) { tab in
                let sel = tab == app.tab
                Button { app.tab = tab } label: {
                    VStack(spacing: 2) {
                        Image(systemName: tab.symbol)
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(sel ? Theme.primary : Theme.textSecondary)
                            .frame(height: 24)
                            .padding(.horizontal, 16).padding(.vertical, 4)
                            .background(sel ? Theme.primary.opacity(0.18) : .clear, in: Capsule())
                        Text(tab.title)
                            .font(AppFont.labelMedium)
                            .foregroundStyle(sel ? Theme.textPrimary : Theme.textSecondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(radius: 12, scale: 1.0))
                .accessibilityAddTraits(sel ? .isSelected : [])
            }
        }
        .frame(height: 64)
        .background(Theme.surface.ignoresSafeArea(edges: .bottom))
    }
}

private struct NavRail: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        VStack(spacing: 6) {
            Text("S")
                .font(AppFont.titleLarge)
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(Theme.accentGradient, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            Spacer().frame(height: 16)
            RailItem(symbol: "magnifyingglass", label: "Cerca", selected: false) { app.open(.search) }
            ForEach(AppTab.visible, id: \.self) { tab in
                RailItem(symbol: tab.symbol, label: tab.title, selected: tab == app.tab) { app.tab = tab }
            }
            Spacer(minLength: 0)
            RailItem(symbol: "gearshape.fill", label: "Impostazioni", selected: false) { app.open(.settings) }
        }
        .padding(.vertical, 16)
        .frame(width: Theme.isTV ? 150 : 92)
        .frame(maxHeight: .infinity)
        .background(Theme.surface.ignoresSafeArea())
        #if os(tvOS)
        .focusSection()
        #endif
    }
}

private struct RailItem: View {
    let symbol: String
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol)
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(selected ? Theme.primary : Theme.textSecondary)
                    .frame(height: 26)
                Text(label)
                    .font(AppFont.labelMedium)
                    .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(width: Theme.isTV ? 130 : 76)
            .padding(.vertical, 10)
            .background(selected ? Theme.primary.opacity(0.18) : .clear, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(radius: 16, scale: 1.05))
    }
}

/// Search + settings icons shown in the phone top bars (Android shows them on every root tab).
struct SearchSettingsActions: View {
    @Environment(AppModel.self) private var app
    var body: some View {
        if !Theme.isTV {
            RoundIconButton(symbol: "magnifyingglass", label: "Cerca") { app.open(.search) }
            RoundIconButton(symbol: "gearshape.fill", label: "Impostazioni") { app.open(.settings) }
        }
    }
}

#if os(iOS)
/// Keeps the edge-swipe "back" gesture working although screens hide the system navigation bar.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
#endif
