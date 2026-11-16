import SwiftUI
import Shared

@MainActor
@Observable
final class LiveModel {
    var categories: [ChipItem] = []
    var selected: String?
    var rows: ListSnapshot<LiveRow>?
    var favorites: Set<String> = []
    /// Current zapping list (read at tap time only: not observed).
    @ObservationIgnored private(set) var channels: [Channel] = []

    @ObservationIgnored private var core: StePlayerCore?
    @ObservationIgnored private(set) var sourceId: Int64 = -2
    @ObservationIgnored private var baseSubs: [FlowSubscription] = []
    @ObservationIgnored private var channelSub: FlowSubscription?
    @ObservationIgnored private var feed: NowNextFeed?

    func bind(_ core: StePlayerCore, sourceId: Int64) {
        guard self.sourceId != sourceId else { return }
        self.core = core
        self.sourceId = sourceId
        guard sourceId >= 0 else {
            baseSubs = []; channelSub = nil; feed?.stop(); feed = nil
            categories = []; rows = ListSnapshot([])
            return
        }
        baseSubs = [
            observeList(core.catalog.categories(sourceId: sourceId, type: .live), as: Shared.Category.self) { [weak self] cats in
                guard let self else { return }
                let chips = [ChipItem(id: "__fav__", title: "Preferiti"), ChipItem(id: "__all__", title: "Tutti")]
                    + cats.map { ChipItem(id: $0.id, title: $0.name) }
                if chips != self.categories { self.categories = chips }
                // Android default: the first real category, else "Tutti".
                let current = self.selected.flatMap { s in chips.contains(where: { $0.id == s }) ? s : nil }
                let target = current ?? DemoHooks.category ?? (chips.count > 2 ? chips[2].id : "__all__")
                if target != self.selected || self.channelSub == nil { self.load(target) }
            },
            observe(core.library.favoriteIds(sourceId: sourceId, type: .live)) { [weak self] (set: NSSet) in
                let ids = set as? Set<String> ?? []
                if self?.favorites != ids { self?.favorites = ids }
            },
        ]
    }

    func select(_ id: String) {
        load(id)
    }

    private func load(_ id: String) {
        selected = id
        rows = nil
        feed?.stop()
        feed = nil
        guard let core else { return }
        let sid = sourceId
        // Conversion to Swift rows happens off the main thread; identical re-emissions (e.g. during a sync) are dropped.
        channelSub = observeListOffMain(core.catalog.channels(sourceId: sid, categoryId: id), as: Channel.self,
                                        transform: ChannelBatch.init) { [weak self] (batch: ChannelBatch) in
            guard let self, self.selected == id else { return }
            self.channels = batch.channels
            // Keep the now/next we already have for these channels: no "Nessuna informazione" blink on re-emission.
            let carry = self.feed?.known ?? [:]
            self.feed?.stop()
            let feed = NowNextFeed(core: core, sourceId: sid, batch: batch, carryOver: carry) { [weak self] rows in
                guard let self, self.selected == id else { return }
                self.rows = ListSnapshot(rows)
            }
            self.feed = feed
            self.rows = ListSnapshot(feed.initialRows())
            feed.start()
        }
    }
}

struct LiveView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var model = LiveModel()

    private var railWidth: CGFloat { Theme.isTV ? 300 : (sizeClass == .regular ? 210 : 128) }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Live", large: true) {
                RoundIconButton(symbol: "calendar", label: "Guida TV") {
                    if model.sourceId >= 0, let cat = model.selected { app.open(.guide(sourceId: model.sourceId, categoryId: cat)) }
                }
                SearchSettingsActions()
            }
            // Live only: categories in a vertical column on the left, channels on the right.
            HStack(spacing: 0) {
                CategoryRail(items: model.categories, selected: model.selected) { model.select($0) }
                    .frame(width: railWidth)
                channelList
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .screenBackground()
        .onAppear { model.bind(app.core, sourceId: app.activeId) }
        .onChange(of: app.activeId) { _, id in model.bind(app.core, sourceId: id) }
    }

    @ViewBuilder private var channelList: some View {
        if let rows = model.rows?.items {
            if rows.isEmpty {
                EmptyState(symbol: "tv", text: "Nessun canale in questa categoria")
            } else {
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(rows) { row in
                            ChannelRow(item: row, favorite: model.favorites.contains(row.id),
                                       onClick: { [model] in app.playChannel(row.channel, categoryId: model.selected, list: model.channels) },
                                       onLongClick: { toggleFavorite(row.channel) })
                                .equatable()
                        }
                    }
                    .padding(.leading, 4).padding(.trailing, 12).padding(.top, 4).padding(.bottom, 16)
                }
                .id(model.selected) // new category → back to the top of its channel list
                #if os(tvOS)
                .focusSection()
                #endif
            }
        } else {
            LoadingBox()
        }
    }

    private func toggleFavorite(_ channel: Channel) {
        Task {
            let fav = (try? await app.core.library.toggleFavorite(sourceId: channel.sourceId, type: .live, itemId: channel.id))?.boolValue ?? false
            app.showToast(fav ? "Aggiunto ai preferiti" : "Rimosso dai preferiti")
        }
    }
}

/// Vertical category list: accent bar + tinted background on the selected one.
private struct CategoryRail: View {
    let items: [ChipItem]
    let selected: String?
    let onSelect: (String) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(items) { item in
                        let isSel = item.id == selected
                        Button { onSelect(item.id) } label: {
                            HStack(spacing: 8) {
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(isSel ? Theme.primary : .clear)
                                    .frame(width: 3, height: 20)
                                // One line at full size if it fits, else one line slightly smaller
                                // ("Documentari"), else two lines broken at spaces — never "Documen-tari".
                                ViewThatFits(in: .horizontal) {
                                    // Same size as Android's labelLarge (14): every category looks the same.
                                    Text(item.title).font(.system(size: 14, weight: .semibold)).lineLimit(1).fixedSize()
                                    Text(item.title).font(.system(size: 14, weight: .semibold)).lineLimit(2).multilineTextAlignment(.leading)
                                }
                                .foregroundStyle(isSel ? Theme.textPrimary : Theme.textSecondary)
                                .padding(.trailing, 8)
                                Spacer(minLength: 0)
                            }
                            .padding(.vertical, 12)
                            .background(isSel ? Theme.primary.opacity(0.18) : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressStyle(radius: 10, scale: 1.03))
                        .id(item.id)
                    }
                }
                .padding(6)
            }
            .background(Theme.surface.opacity(0.5))
            .onChange(of: items.isEmpty) { _, empty in
                if !empty, let selected { proxy.scrollTo(selected, anchor: .center) }
            }
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }
}

/// Equatable on what it draws (closures excluded): when the list publishes, unchanged rows are skipped.
struct ChannelRow: View, Equatable {
    let item: LiveRow
    let favorite: Bool
    let onClick: () -> Void
    let onLongClick: () -> Void

    static func == (a: ChannelRow, b: ChannelRow) -> Bool { a.item == b.item && a.favorite == b.favorite }

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 12) {
                ArtImage(url: item.logo, title: item.name, contentMode: .fit, points: 64)
                    .padding(6)
                    .frame(width: 76, height: 52)
                    .background(Theme.surfaceElevated)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 0) {
                        if let num = item.num {
                            Text("\(num)").font(AppFont.labelMedium).foregroundStyle(Theme.textSecondary)
                                .padding(.trailing, 8)
                        }
                        Text(item.name).font(AppFont.titleSmall).foregroundStyle(Theme.textPrimary).lineLimit(1)
                        if item.hasArchive {
                            Image(systemName: "clock.arrow.circlepath").font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(Theme.accent2).padding(.leading, 6)
                                .accessibilityLabel("Replay")
                        }
                    }
                    if let cur = item.now {
                        Text(cur.title).font(AppFont.bodySmall).foregroundStyle(Theme.textPrimary.opacity(0.85)).lineLimit(1)
                            .padding(.top, 2)
                        FractionWidth(fraction: 0.9) {
                            EpgProgressBar(startAt: cur.startAt, endAt: cur.endAt, height: 3)
                        }
                        .frame(height: 3)
                        .padding(.top, 5)
                        if let n = item.next {
                            Text("\(Fmt.time(n.startAt))  \(n.title)").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
                                .padding(.top, 4)
                        }
                    } else {
                        Text("Nessuna informazione sul programma").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if favorite {
                    Image(systemName: "star.fill").font(.system(size: 16)).foregroundStyle(Theme.primary)
                        .accessibilityLabel("Preferito")
                }
            }
            .padding(10)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(PressStyle(radius: 14, scale: 1.02))
        .contextMenu {
            Button(favorite ? "Rimuovi dai preferiti" : "Aggiungi ai preferiti", systemImage: favorite ? "star.slash" : "star", action: onLongClick)
        }
    }
}
