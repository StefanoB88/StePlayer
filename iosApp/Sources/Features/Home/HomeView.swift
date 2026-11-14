import SwiftUI
import Shared

struct HeroItem: Identifiable, Equatable {
    let sourceId: Int64
    let itemId: String
    let title: String
    let image: String?
    let subtitle: String?
    let isSeries: Bool
    var id: String { (isSeries ? "s:" : "m:") + itemId }
}

@MainActor
@Observable
final class HomeModel {
    var loaded = false
    var hero: [HeroItem] = []
    var recentMovies: [Movie] = []
    var recentSeries: [Series] = []
    var continueWatching: [WatchProgress] = []
    var recentChannels: [WatchProgress] = []
    @ObservationIgnored private var subs: [FlowSubscription] = []
    @ObservationIgnored private var boundSource: Int64 = -2

    var isEmpty: Bool { loaded && continueWatching.isEmpty && recentChannels.isEmpty && recentMovies.isEmpty && recentSeries.isEmpty }

    func bind(_ core: StePlayerCore, sourceId: Int64) {
        guard boundSource != sourceId else { return }
        boundSource = sourceId
        guard sourceId >= 0 else { subs = []; loaded = true; return }
        // Only publish real changes: the core re-emits these lists on every write to the tables (sync, progress).
        subs = [
            observeList(core.catalog.movies(sourceId: sourceId, categoryId: "__recent__", limit: 30, offset: 0), as: Movie.self) { [weak self] list in
                guard let self else { return }
                if !self.loaded { self.loaded = true }
                guard list != self.recentMovies else { return }
                self.recentMovies = list
                self.rebuildHero()
            },
            observeList(core.catalog.series(sourceId: sourceId, categoryId: "__recent__", limit: 30, offset: 0), as: Series.self) { [weak self] list in
                guard let self, list != self.recentSeries else { return }
                self.recentSeries = list
                self.rebuildHero()
            },
            observeList(core.library.continueWatching(sourceId: sourceId, limit: 20), as: WatchProgress.self) { [weak self] list in
                guard let self, list != self.continueWatching else { return }
                self.continueWatching = list
            },
            observeList(core.library.recentChannels(sourceId: sourceId, limit: 15), as: WatchProgress.self) { [weak self] list in
                guard let self, list != self.recentChannels else { return }
                self.recentChannels = list
            },
        ]
    }

    /// Same mix as Android: 4 recent movies with a poster + 3 series with a cover.
    private func rebuildHero() {
        let movies = recentMovies.filter { !($0.poster ?? "").isEmpty }.prefix(4).map { m in
            HeroItem(sourceId: m.sourceId, itemId: m.id, title: m.name, image: m.poster,
                     subtitle: [m.year.map { String($0.int32Value) }, AFormat.rating(m.rating?.doubleValue).map { "★ \($0)" }]
                        .compactMap { $0 }.joined(separator: "  ·  "),
                     isSeries: false)
        }
        let series = recentSeries.filter { !($0.cover ?? "").isEmpty }.prefix(3).map { s in
            HeroItem(sourceId: s.sourceId, itemId: s.id, title: s.name, image: s.cover,
                     subtitle: [s.year.map { String($0.int32Value) }, s.genre].compactMap { $0 }.joined(separator: "  ·  "),
                     isSeries: true)
        }
        let fresh = Array(movies) + Array(series)
        if fresh != hero { hero = fresh }
    }
}

struct HomeView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var model = HomeModel()
    @State private var dismissedNotices: Set<String> = []

    private var wide: Bool { Theme.isTV || sizeClass == .regular }

    var body: some View {
        GeometryReader { geo in
            let topInset = geo.safeAreaInsets.top
            Group {
                if model.isEmpty || app.activeId < 0 {
                    VStack(spacing: 0) {
                        HomeTopBar(transparent: false, refresh: refresh)
                        EmptyState(symbol: "list.and.film", text: "La libreria è vuota. Sincronizza la lista dalle impostazioni.") {
                            PrimaryButton(text: "Impostazioni") { app.open(.settings) }
                        }
                    }
                } else {
                    content(topInset: topInset)
                }
            }
        }
        .screenBackground()
        .onAppear { model.bind(app.core, sourceId: app.activeId) }
        .onChange(of: app.activeId) { _, id in model.bind(app.core, sourceId: id) }
    }

    private func content(topInset rawInset: CGFloat) -> some View {
        // With a hero the scroll view runs under the status bar (Android edge-to-edge); without, it respects it.
        let topInset = model.hero.isEmpty ? 0 : rawInset
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .top) {
                    if !model.hero.isEmpty {
                        HeroCarousel(items: model.hero, wide: wide, topInset: topInset)
                    } else {
                        Color.clear.frame(height: 64 + topInset)
                    }
                    HomeTopBar(transparent: true, refresh: refresh)
                        .padding(.top, topInset)
                        .background(alignment: .top) {
                            LinearGradient(colors: [.black.opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
                                .frame(height: 64 + topInset)
                                .allowsHitTesting(false)
                        }
                }

                ForEach(app.expiryNotices.filter { !dismissedNotices.contains(noticeKey($0)) }, id: \.sourceId) { n in
                    ExpiryBanner(notice: n) { dismissedNotices.insert(noticeKey(n)) }
                }

                if !model.continueWatching.isEmpty {
                    SectionHeader(title: "Continua a guardare")
                    row {
                        ForEach(model.continueWatching, id: \.itemId) { p in
                            WideCard(title: p.title, imageURL: p.image, subtitle: p.subtitle, progress: Double(p.fraction)) {
                                Task { await app.playProgress(p) }
                            }
                            .frame(width: wide ? 300 : 240)
                            .contextMenu {
                                Button("Rimuovi", systemImage: "xmark") {
                                    Task { _ = try? await app.core.library.removeProgress(sourceId: p.sourceId, type: p.type, itemId: p.itemId) }
                                }
                            }
                        }
                    }
                }
                if !model.recentChannels.isEmpty {
                    SectionHeader(title: "Canali recenti")
                    row {
                        ForEach(model.recentChannels, id: \.itemId) { p in
                            WideCard(title: p.title, imageURL: p.image, live: true, fitImage: true) {
                                Task { await app.playProgress(p) }
                            }
                            .frame(width: wide ? 220 : 170)
                        }
                    }
                }
                if !model.recentMovies.isEmpty {
                    RowHeader(title: "Film aggiunti di recente") { app.tab = .movies }
                    row {
                        ForEach(model.recentMovies, id: \.id) { m in
                            PosterCard(title: m.name, imageURL: m.poster, subtitle: m.year.map { String($0.int32Value) },
                                       rating: AFormat.rating(m.rating?.doubleValue), width: wide ? 150 : 118) {
                                app.open(.movie(sourceId: m.sourceId, id: m.id))
                            }
                        }
                    }
                }
                if !model.recentSeries.isEmpty {
                    RowHeader(title: "Serie aggiornate di recente") { app.tab = .series }
                    row {
                        ForEach(model.recentSeries, id: \.id) { s in
                            PosterCard(title: s.name, imageURL: s.cover, subtitle: s.year.map { String($0.int32Value) },
                                       rating: AFormat.rating(s.rating?.doubleValue), width: wide ? 150 : 118) {
                                app.open(.series(sourceId: s.sourceId, id: s.id))
                            }
                        }
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .ignoresSafeArea(edges: model.hero.isEmpty ? [] : .top)
        .refreshable { refresh() }
    }

    private func row<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: Theme.isTV ? 28 : 12) { content() }
                .padding(.horizontal, Theme.gutter)
                .padding(.vertical, Theme.isTV ? 20 : 0)
        }
        .scrollClipDisabled()
        #if os(tvOS)
        .focusSection()
        #endif
    }

    private func noticeKey(_ n: ExpiryNotice) -> String { "\(n.sourceId):\(n.daysLeft):\(n.expired)" }

    /// "Aggiorna": re-downloads channels, movies, series and TV guide of the active list.
    private func refresh() {
        guard app.activeId >= 0, !app.isSyncing else { return }
        let id = app.activeId
        Task {
            await app.syncNow(id, blocking: false)
            app.showToast(app.syncError.map { "Aggiornamento non riuscito. \($0)" } ?? "Lista aggiornata")
        }
    }
}

private struct RowHeader: View {
    let title: String
    let onMore: () -> Void
    var body: some View {
        HStack(spacing: 0) {
            SectionHeader(title: title)
            Button(action: onMore) {
                Text("›").font(AppFont.headlineSmall).foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 12).padding(.vertical, 4)
                    .contentShape(Circle())
            }
            .buttonStyle(PressStyle(radius: 20, scale: 1.1))
            .accessibilityLabel("Mostra tutti")
            .padding(.trailing, Theme.gutter - 8)
        }
    }
}

private struct HomeTopBar: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    let transparent: Bool
    let refresh: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Text("StePlayer")
                .font(AppFont.titleLarge)
                .foregroundStyle(Theme.textPrimary)
                .fixedSize()
                .padding(.leading, 4)
            Spacer(minLength: 8)
            if app.sources.count > 1 {
                Menu {
                    ForEach(app.sources, id: \.id) { s in
                        Button(s.name) { switchSource(s.id) }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.left.arrow.right").font(.system(size: 15, weight: .semibold))
                        Text(app.active?.name ?? "").font(AppFont.labelLarge).lineLimit(1)
                    }
                    .foregroundStyle(Theme.textPrimary)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .frame(maxWidth: Theme.isTV ? 320 : 130)
                    .background(Theme.surfaceElevated, in: Capsule())
                }
                .accessibilityLabel("Cambia lista")
            }
            if app.activeId >= 0 {
                // Phone width with the list switcher too: icon only, or the switcher shrank to one letter.
                RefreshPill(refreshing: app.isSyncing, compact: app.sources.count > 1 && !Theme.isTV && sizeClass == .compact,
                            action: refresh)
            }
            SearchSettingsActions()
        }
        .frame(height: 64)
        .padding(.horizontal, 12)
    }

    private func switchSource(_ id: Int64) {
        Task {
            await app.activate(id)
            try? await app.core.sync.syncIfStale(sourceId: id, maxAgeMs: 12 * 3600 * 1000)
        }
    }
}

/// "Aggiorna" — circular-arrows icon + text, understandable by everyone; only the icon spins while refreshing.
private struct RefreshPill: View {
    let refreshing: Bool
    var compact = false
    let action: () -> Void
    @State private var angle: Double = 0

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 16, weight: .semibold))
                    .rotationEffect(.degrees(angle))
                if !compact {
                    // Always "Aggiorna": only the icon spins, so the pill never changes width while refreshing.
                    Text("Aggiorna").font(AppFont.labelLarge).lineLimit(1).fixedSize()
                }
            }
            .foregroundStyle(Theme.textPrimary)
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(Theme.surfaceElevated.opacity(0.85), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(PressStyle(radius: 40, scale: 1.06))
        .disabled(refreshing)
        .accessibilityLabel(refreshing ? "Aggiornamento in corso" : "Aggiorna la lista di canali, film, serie e guida TV")
        .onAppear { spin(refreshing) }
        .onChange(of: refreshing) { _, r in spin(r) }
    }

    private func spin(_ on: Bool) {
        if on {
            angle = 0
            withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: false)) { angle = 360 }
        } else {
            withAnimation(.default) { angle = 0 }
        }
    }
}

private struct HeroCarousel: View {
    @Environment(AppModel.self) private var app
    let items: [HeroItem]
    let wide: Bool
    let topInset: CGFloat
    @State private var index = 0

    var body: some View {
        let item = items[min(index, items.count - 1)]
        ZStack(alignment: .bottomLeading) {
            Color.clear
                .overlay {
                    ArtImage(url: item.image, title: item.title, showInitials: false, maxPixel: 1400)
                        .id(item.id)
                        .transition(.opacity)
                }
                .clipped()
                .overlay(Theme.scrimBottom)
                .overlay { if wide { Theme.scrimLeft } }
            VStack(alignment: .leading, spacing: 0) {
                Text(item.title)
                    .font(AppFont.displaySmall)
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(2)
                if let sub = item.subtitle, !sub.isEmpty {
                    Spacer().frame(height: 6)
                    Text(sub).font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary)
                }
                Spacer().frame(height: 16)
                HStack(spacing: 10) {
                    if item.isSeries {
                        // A series has nothing to "play" as a whole: one button, straight to the episodes.
                        PrimaryButton(text: "Episodi", icon: "list.bullet") {
                            app.open(.series(sourceId: item.sourceId, id: item.itemId))
                        }
                    } else {
                        PrimaryButton(text: "Guarda ora", icon: "play.fill") {
                            Task {
                                if let m = try? await app.core.catalog.movie(sourceId: item.sourceId, id: item.itemId) { app.play(.movie(m)) }
                            }
                        }
                        SecondaryButton(text: "Dettagli", icon: "info.circle.fill") {
                            app.open(.movie(sourceId: item.sourceId, id: item.itemId))
                        }
                    }
                }
                Spacer().frame(height: 14)
                HStack(spacing: 6) {
                    ForEach(items.indices, id: \.self) { i in
                        Capsule()
                            .fill(i == index ? Theme.primary : Color.white.opacity(0.3))
                            .frame(width: i == index ? 22 : 8, height: 4)
                    }
                }
            }
            .padding(.horizontal, Theme.section)
            .padding(.vertical, 20)
            .frame(maxWidth: wide ? 560 : .infinity, alignment: .leading)
        }
        .frame(height: (wide ? 420 : 480) + topInset)
        .clipped()
        .task(id: items.count) {
            while !Task.isCancelled && items.count > 1 {
                try? await Task.sleep(for: .seconds(7))
                guard !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: 0.5)) { index = (index + 1) % items.count }
            }
        }
    }
}

private struct ExpiryBanner: View {
    let notice: ExpiryNotice
    let onDismiss: () -> Void

    var body: some View {
        let color = notice.expired ? Theme.live : Theme.warning
        HStack(spacing: 12) {
            Image(systemName: "info.circle.fill").font(.system(size: 18)).foregroundStyle(color)
            VStack(alignment: .leading, spacing: 2) {
                Text(notice.sourceName).font(AppFont.labelLarge).foregroundStyle(color)
                Text(notice.message).font(AppFont.bodyMedium).foregroundStyle(Theme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            RoundIconButton(symbol: "xmark", label: "Chiudi", tint: Theme.textSecondary, action: onDismiss)
        }
        .padding(.leading, 14).padding(.vertical, 6).padding(.trailing, 4)
        .background(color.opacity(0.14), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, Theme.gutter).padding(.vertical, 8)
    }
}
