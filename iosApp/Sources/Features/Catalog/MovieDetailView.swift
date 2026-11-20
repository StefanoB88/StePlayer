import SwiftUI
import Shared

/// Android `MovieDetailScreen`.
struct MovieDetailView: View {
    let sourceId: Int64
    let id: String
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    @State private var detail: MovieDetail?
    @State private var notFound = false
    @State private var progress: WatchProgress?
    @State private var isFavorite = false
    @State private var download: Download?
    /// The title has reached the top bar: the bar turns solid with the title.
    @State private var heroAway = false

    /// Big iPhones in landscape are "regular" wide but only ~430 pt tall: the tall tablet header hid the title and
    /// the play button below the fold, so they keep the phone layout.
    private var wide: Bool { Theme.isTV || (sizeClass == .regular && verticalSizeClass != .compact) }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                if notFound {
                    EmptyState(symbol: "film", text: "Nessun film in questa categoria")
                } else if let d = detail {
                    content(d, topInset: geo.safeAreaInsets.top,
                            compact: DetailLayout.compact(geo, sizeClass: sizeClass, verticalSizeClass: verticalSizeClass))
                } else {
                    LoadingBox()
                }
                DetailTopBar(title: detail?.movie.name ?? "", solid: detail == nil || heroAway,
                             topInset: detail == nil ? 0 : geo.safeAreaInsets.top)
                    .ignoresSafeArea(edges: detail == nil ? [] : .top)
            }
        }
        .task { await load() }
        .task { await refreshProgress() }
        // Android refreshes on resume: the full-screen player closing doesn't re-run `.task`, so refresh here
        // (shortly after, so the position saved when the player closed is already stored).
        .onChange(of: app.playing == nil) { _, closed in
            guard closed else { return }
            Task {
                try? await Task.sleep(for: .milliseconds(400))
                await refreshProgress()
            }
        }
    }

    private func content(_ d: MovieDetail, topInset: CGFloat, compact: Bool) -> some View {
        let m = d.movie
        return ZStack(alignment: .top) {
            if compact { DetailBackdropBackground(image: d.backdrop ?? m.poster, title: m.name) }
            ScrollView {
                if compact {
                    // iPad in landscape: poster, title, buttons and plot start right under the top bar.
                    CompactDetailHeader(poster: m.poster, title: m.name, topInset: topInset) {
                        titleBlock(d, topInset: topInset)
                        Spacer().frame(height: 16)
                        actionsBlock(d, maxPlotWidth: .infinity)
                    }
                    .padding(.bottom, 24)
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        BackdropHeader(image: d.backdrop ?? m.poster, title: m.name, height: (wide ? 380 : 300) + topInset)
                        HStack(alignment: .bottom, spacing: 24) {
                            if wide {
                                AspectBox(ratio: 2.0 / 3.0) { ArtImage(url: m.poster, title: m.name, points: Theme.isTV ? 360 : 270) }
                                    .frame(width: Theme.isTV ? 240 : 180)
                                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                            titleBlock(d, topInset: topInset)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.horizontal, 24)
                        .offset(y: -120)

                        actionsBlock(d, maxPlotWidth: wide ? 700 : .infinity)
                            .padding(.horizontal, 24)
                            .offset(y: -100)
                    }
                }
            }
            .ignoresSafeArea(edges: .top)
        }
    }

    /// Title + meta line (year, duration, rating, container).
    private func titleBlock(_ d: MovieDetail, topInset: CGFloat) -> some View {
        let m = d.movie
        return VStack(alignment: .leading, spacing: 8) {
            Text(m.name).font(AppFont.headlineMedium).foregroundStyle(Theme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .onTitleUnderTopBar(barBottom: topInset + 64) { away in
                    if away != heroAway { heroAway = away }
                }
            let meta = [m.year.map { String($0.int32Value) },
                        Fmt.minutes(seconds: d.durationSec?.intValue),
                        AFormat.rating(m.rating?.doubleValue).map { "★ \($0)" },
                        m.extension?.uppercased()].compactMap { $0 }.joined(separator: "   ·   ")
            if !meta.isEmpty { Text(meta).font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary) }
        }
    }

    /// Resume bar, play / download / favourite buttons, plot and credits.
    private func actionsBlock(_ d: MovieDetail, maxPlotWidth: CGFloat) -> some View {
        let m = d.movie
        let canResume = (progress?.positionMs ?? 0) > 30_000 && (progress?.fraction ?? 1) < 0.95
        return VStack(alignment: .leading, spacing: 0) {
            if canResume, let p = progress {
                ProgressBar(progress: Double(p.fraction))
                    .frame(maxWidth: wide ? 320 : .infinity)
                    .padding(.bottom, 12)
            }
            FlowLayout(spacing: 10, lineSpacing: 10) {
                if canResume, let p = progress {
                    PrimaryButton(text: "Riprendi da \(AFormat.duration(ms: p.positionMs))", icon: "play.fill") { app.play(.movie(m)) }
                    SecondaryButton(text: "Dall'inizio", icon: "arrow.counterclockwise") { app.play(.movie(m), fromStart: true) }
                } else {
                    PrimaryButton(text: "Riproduci", icon: "play.fill") { app.play(.movie(m), fromStart: true) }
                }
                #if os(iOS)
                SecondaryButton(text: DownloadUI.label(download), icon: DownloadUI.icon(download), tint: DownloadUI.tint(download)) {
                    DownloadUI.tap(download, app: app) { _ = try await app.core.downloads.downloadMovie(movie: m) }
                }
                #endif
                SecondaryButton(text: "", icon: isFavorite ? "heart.fill" : "heart", tint: isFavorite ? Theme.live : Theme.textPrimary) {
                    Task { _ = try? await app.core.library.toggleFavorite(sourceId: sourceId, type: .movie, itemId: id) }
                }
                .accessibilityLabel(isFavorite ? "Rimuovi dai preferiti" : "Aggiungi ai preferiti")
            }
            Spacer().frame(height: 20)
            if let plot = d.plot {
                Text(plot).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary.opacity(0.9))
                    .frame(maxWidth: maxPlotWidth, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer().frame(height: 16)
            }
            MetaLine(label: "Genere", value: d.genre)
            MetaLine(label: "Regia", value: d.director)
            MetaLine(label: "Cast", value: d.cast)
        }
    }

    private func load() async {
        let core = app.core
        // Held by this task (cancelled when the screen goes away), not by view state: no retain cycle.
        let subs = [
            observe(core.library.isFavorite(sourceId: sourceId, type: .movie, itemId: id)) { (v: KotlinBoolean) in isFavorite = v.boolValue },
            observeList(core.downloads.observe(id: DownloadRepository.companion.idFor(sourceId: sourceId, type: "MOVIE", itemId: id)), as: Download.self) { list in
                if list.first != download { download = list.first }
            },
        ]
        if detail == nil {
            if let movie = try? await core.catalog.movie(sourceId: sourceId, id: id) {
                detail = MovieDetail(movie: movie, plot: nil, genre: nil, cast: nil, director: nil, releaseDate: nil, durationSec: nil, backdrop: nil, trailerYoutubeId: nil)
                if let full = try? await core.catalog.movieDetail(movie: movie), !Task.isCancelled { detail = full }
            } else if !Task.isCancelled {
                notFound = true
            }
        }
        await holdUntilCancelled(subs)
    }

    private func refreshProgress() async {
        progress = try? await app.core.library.progress(sourceId: sourceId, type: .movie, itemId: id)
    }
}

/// iPad in landscape is wide but short (iPad mini 1133x744 pt, 11" 1210x834 pt, 13" 1376x1032 pt): the tall hero
/// layout spent ~300 pt on artwork and pushed the series' season chips and episodes below the bottom edge (iPad
/// mini) or to the very bottom with a longer plot. So iPad landscape uses the compact layout (fixed full-screen
/// backdrop, poster + title + buttons right under the top bar), like Android TV and tablets in landscape.
/// iPad portrait, Split View / Slide Over (compact width), iPhone and Apple TV keep their layout.
enum DetailLayout {
    static func compact(_ geo: GeometryProxy, sizeClass: UserInterfaceSizeClass?, verticalSizeClass: UserInterfaceSizeClass?) -> Bool {
        guard !Theme.isTV, sizeClass == .regular, verticalSizeClass == .regular else { return false }
        let w = geo.size.width + geo.safeAreaInsets.leading + geo.safeAreaInsets.trailing
        let h = geo.size.height + geo.safeAreaInsets.top + geo.safeAreaInsets.bottom
        return w > h
    }
}

/// Artwork filling the whole screen behind a compact detail page (it stays put while the page scrolls).
struct DetailBackdropBackground: View {
    let image: String?
    let title: String
    var body: some View {
        Color.clear
            .overlay { ArtImage(url: image, title: title, showInitials: false, maxPixel: 1400) }
            .clipped()
            .overlay(Theme.background.opacity(0.35))
            .overlay(Theme.scrimLeft)
            .overlay(Theme.scrimBottom)
            .ignoresSafeArea()
            .accessibilityHidden(true)
    }
}

/// Compact detail header: poster on the left, `content` (title, meta, buttons, plot) on the right, starting right
/// under the top bar. The text column stops at 760 pt: a plot running the full width of an iPad is hard to read.
struct CompactDetailHeader<Content: View>: View {
    let poster: String?
    let title: String
    let topInset: CGFloat
    @ViewBuilder var content: () -> Content
    var body: some View {
        HStack(alignment: .top, spacing: 28) {
            AspectBox(ratio: 2.0 / 3.0) { ArtImage(url: poster, title: title, points: 260) }
                .frame(width: 170)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            VStack(alignment: .leading, spacing: 0, content: content)
                .frame(maxWidth: 760, alignment: .leading)
            Spacer(minLength: 0)
        }
        .padding(.top, topInset + 64 + 8)
        .padding(.horizontal, 24)
    }
}

/// Back arrow + AirPlay over the artwork (Android detail `AppTopBar("", onBack) { CastButton() }`).
/// Over the artwork the arrow (and AirPlay) float on a small dark scrim; once the page title reaches the bar a
/// solid bar (screen background, back arrow + title) fades in, so the arrow never overlaps the text underneath.
struct DetailTopBar: View {
    @Environment(AppModel.self) private var app
    var title: String = ""
    /// The page title has reached the bar (or there is no artwork): solid bar with the title.
    var solid = true
    /// Status bar height, drawn by this bar's background when it runs under it.
    var topInset: CGFloat = 0

    var body: some View {
        HStack(spacing: 4) {
            Button { app.back() } label: {
                Image(systemName: "arrow.left")
                    .font(.system(size: 44 * 0.42, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .frame(width: 40, height: 40)
                    .background(Color.black.opacity(solid ? 0 : 0.45), in: Circle())
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(PressStyle(radius: 22, scale: 1.1, circle: true))
            .accessibilityLabel("Indietro")
            Text(title)
                .font(AppFont.titleLarge)
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.leading, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .opacity(solid ? 1 : 0)
                .accessibilityHidden(!solid)
            #if os(iOS)
            AirPlayButton(tint: UIColor(Theme.textPrimary))
                .frame(width: 40, height: 40)
                .background(Color.black.opacity(solid ? 0 : 0.45), in: Circle())
                .frame(width: 44, height: 44)
            #endif
        }
        .frame(minHeight: 64)
        .padding(.horizontal, 8)
        .padding(.top, topInset)
        .background(Theme.background.opacity(solid ? 1 : 0))
        .animation(.easeInOut(duration: 0.15), value: solid)
    }
}

extension View {
    /// Put on a detail page's title: reports whether it has reached the bottom edge of the top bar
    /// (`barBottom`, in screen coordinates), i.e. when the bar must turn solid so the arrow never covers it.
    func onTitleUnderTopBar(barBottom: CGFloat, _ action: @escaping (Bool) -> Void) -> some View {
        onGeometryChange(for: Bool.self) { proxy in
            proxy.frame(in: .global).minY < barBottom
        } action: { action($0) }
    }
}
