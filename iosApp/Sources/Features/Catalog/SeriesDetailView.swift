import SwiftUI
import Shared

/// Android `SeriesDetailScreen`.
struct SeriesDetailView: View {
    let sourceId: Int64
    let id: String
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var series: Series?
    @State private var detail: SeriesDetail?
    @State private var loadingEpisodes = true
    @State private var notFound = false
    @State private var season: Int32?
    @State private var progress: [String: WatchProgress] = [:]
    @State private var downloads: [String: Download] = [:]
    @State private var isFavorite = false
    /// The title has reached the top bar: the bar turns solid with the title.
    @State private var heroAway = false

    private var wide: Bool { Theme.isTV || sizeClass == .regular }
    private var seasons: [Int32] { detail?.seasons.map { $0.int32Value } ?? [] }
    private var selectedSeason: Int32? { season.flatMap { seasons.contains($0) ? $0 : nil } ?? seasons.first }
    private var episodes: [Episode] { selectedSeason.flatMap { detail?.episodesOf(season: $0) } ?? [] }

    /// Resume the last watched episode, else the next one, else the first (Android `upNext`).
    private var upNext: (Episode, Int64)? {
        guard let all = detail?.episodes, let first = all.first else { return nil }
        guard let last = progress.values.max(by: { $0.updatedAt < $1.updatedAt }),
              let idx = all.firstIndex(where: { $0.id == last.itemId }) else { return (first, 0) }
        if last.fraction < 0.95 { return (all[idx], last.positionMs) }
        return (idx + 1 < all.count ? all[idx + 1] : all[idx], 0)
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                if notFound {
                    EmptyState(symbol: "play.rectangle.on.rectangle", text: "Nessuna serie in questa categoria")
                } else if let s = series {
                    content(s, topInset: geo.safeAreaInsets.top)
                } else {
                    LoadingBox()
                }
                DetailTopBar(title: series?.name ?? "", solid: series == nil || heroAway,
                             topInset: series == nil ? 0 : geo.safeAreaInsets.top)
                    .ignoresSafeArea(edges: series == nil ? [] : .top)
            }
        }
        .task { await load() }
    }

    private func content(_ s: Series, topInset: CGFloat) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                BackdropHeader(image: detail?.backdrop ?? s.cover, title: s.name, height: (wide ? 360 : 280) + topInset)
                HStack(alignment: .bottom, spacing: 24) {
                    if wide {
                        AspectBox(ratio: 2.0 / 3.0) { ArtImage(url: s.cover, title: s.name, points: Theme.isTV ? 330 : 240) }
                            .frame(width: Theme.isTV ? 220 : 160)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        Text(s.name).font(AppFont.headlineMedium).foregroundStyle(Theme.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                            .onTitleUnderTopBar(barBottom: topInset + 64) { away in
                                if away != heroAway { heroAway = away }
                            }
                        let meta = [s.year.map { String($0.int32Value) }, AFormat.rating(s.rating?.doubleValue).map { "★ \($0)" }, s.genre]
                            .compactMap { $0 }.joined(separator: "   ·   ")
                        if !meta.isEmpty { Text(meta).font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 24)
                .offset(y: -90)

                VStack(alignment: .leading, spacing: 0) {
                    FlowLayout(spacing: 10, lineSpacing: 10) {
                        if let next = upNext {
                            let (ep, pos) = next
                            let label = "S\(ep.season) E\(ep.number)"
                            PrimaryButton(text: (pos > 30_000 ? "Riprendi da \(AFormat.duration(ms: pos))" : "Riproduci") + "  ·  \(label)", icon: "play.fill") {
                                play(ep, fromStart: pos <= 30_000)
                            }
                        }
                        SecondaryButton(text: "", icon: isFavorite ? "heart.fill" : "heart", tint: isFavorite ? Theme.live : Theme.textPrimary) {
                            Task { _ = try? await app.core.library.toggleFavorite(sourceId: sourceId, type: .series, itemId: id) }
                        }
                        .accessibilityLabel(isFavorite ? "Rimuovi dai preferiti" : "Aggiungi ai preferiti")
                    }
                    Spacer().frame(height: 16)
                    if let plot = s.plot {
                        Text(plot).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary.opacity(0.9)).lineLimit(6)
                        Spacer().frame(height: 10)
                    }
                    MetaLine(label: "Cast", value: detail?.cast)
                    MetaLine(label: "Regia", value: detail?.director)
                }
                .padding(.horizontal, 24)
                .offset(y: -70)

                if seasons.count > 1 {
                    CategoryChips(items: seasons.map { ChipItem(id: String($0), title: "Stagione \($0)") },
                                  selected: selectedSeason.map { String($0) }) { season = Int32($0) }
                        .frame(height: Theme.isTV ? 80 : 40)
                        .offset(y: -50)
                }

                if loadingEpisodes {
                    ProgressView().tint(Theme.primary).frame(maxWidth: .infinity).padding(32)
                } else if episodes.isEmpty {
                    Text("Nessun episodio disponibile").foregroundStyle(Theme.textSecondary).padding(24)
                } else {
                    ForEach(episodes, id: \.id) { ep in
                        EpisodeRow(episode: ep, progress: progress[ep.id], download: downloads[ep.id],
                                   onPlay: { play(ep, fromStart: !((progress[ep.id]?.fraction ?? 1) < 0.95)) },
                                   onDownload: {
                                       #if os(iOS)
                                       DownloadUI.tap(downloads[ep.id], app: app) { _ = try await app.core.downloads.downloadEpisode(series: s, episode: ep) }
                                       #endif
                                   })
                    }
                    .offset(y: -40)
                }
            }
        }
        .ignoresSafeArea(edges: .top)
    }

    private func play(_ ep: Episode, fromStart: Bool) {
        guard let s = detail?.series ?? series else { return }
        app.play(.episode(s, ep, all: detail?.episodes ?? [ep]), fromStart: fromStart)
    }

    private func load() async {
        let core = app.core
        // Held by this task (cancelled when the screen goes away), not by view state: no retain cycle.
        let subs = [
            observe(core.library.isFavorite(sourceId: sourceId, type: .series, itemId: id)) { (v: KotlinBoolean) in isFavorite = v.boolValue },
            observe(core.library.seriesProgress(sourceId: sourceId, seriesId: id)) { (dict: NSDictionary) in
                progress = dict as? [String: WatchProgress] ?? [:]
            },
            observeList(core.downloads.downloads, as: Download.self) { [id] list in
                let mine = Dictionary(list.filter { $0.type == ContentType.episode && $0.parentId == id }.map { ($0.itemId, $0) },
                                      uniquingKeysWith: { a, _ in a })
                if mine != downloads { downloads = mine }
            },
        ]
        if series == nil {
            if let s = try? await core.catalog.seriesById(sourceId: sourceId, id: id) {
                series = s
                if let d = try? await core.catalog.seriesDetail(series: s) { series = d.series; detail = d }
                loadingEpisodes = false
            } else if !Task.isCancelled {
                notFound = true
                loadingEpisodes = false
            }
        }
        await holdUntilCancelled(subs)
    }
}

private struct EpisodeRow: View {
    let episode: Episode
    let progress: WatchProgress?
    let download: Download?
    let onPlay: () -> Void
    let onDownload: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onPlay) {
                HStack(spacing: 12) {
                    AspectBox(ratio: 16.0 / 9.0) { ArtImage(url: episode.image, title: episode.title, points: Theme.isTV ? 240 : 140) }
                        .frame(width: Theme.isTV ? 240 : 140)
                        .overlay(alignment: .bottom) {
                            if let p = progress, p.fraction > 0 { ProgressBar(progress: Double(p.fraction), height: 3).padding(6) }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    VStack(alignment: .leading, spacing: 0) {
                        Text("\(episode.number). \(episode.title)").font(AppFont.titleSmall).foregroundStyle(Theme.textPrimary).lineLimit(2)
                            .multilineTextAlignment(.leading)
                        if let d = Fmt.minutes(seconds: episode.durationSec?.intValue) {
                            Text(d).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
                        }
                        if let plot = episode.plot {
                            Text(plot).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(2)
                                .multilineTextAlignment(.leading).padding(.top, 2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle(radius: 14, scale: 1.02))
            #if os(iOS)
            ZStack {
                if download?.status == DownloadStatus.running {
                    Circle().stroke(Theme.surfaceElevated, lineWidth: 2).frame(width: 40, height: 40)
                    Circle().trim(from: 0, to: CGFloat(download?.fraction ?? 0))
                        .stroke(Theme.primary, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                        .rotationEffect(.degrees(-90)).frame(width: 40, height: 40)
                }
                RoundIconButton(symbol: DownloadUI.icon(download), label: DownloadUI.label(download), tint: DownloadUI.tint(download), action: onDownload)
            }
            #endif
        }
        .padding(10)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 16).padding(.vertical, 5)
    }
}
