import SwiftUI
import Shared

enum CatalogKind {
    case movies, series
    var title: String { self == .movies ? "Film" : "Serie" }
    var type: ContentType { self == .movies ? .movie : .series }
    var emptySymbol: String { self == .movies ? "film" : "play.rectangle.on.rectangle" }
    var emptyText: String { self == .movies ? "Nessun film in questa categoria" : "Nessuna serie in questa categoria" }
}

/// Grid-ready item shared by movies and series (Android `GridItem`).
struct GridEntry: Identifiable, Equatable, Sendable {
    let sourceId: Int64
    let id: String
    let title: String
    let image: String?
    let subtitle: String?
    let rating: String?
}

@MainActor
@Observable
final class CatalogModel {
    var chips: [ChipItem] = []
    var selected: String?
    var items: ListSnapshot<GridEntry>?

    @ObservationIgnored private var core: StePlayerCore?
    @ObservationIgnored private var kind: CatalogKind = .movies
    @ObservationIgnored private var sourceId: Int64 = -2
    @ObservationIgnored private var catSub: FlowSubscription?
    @ObservationIgnored private var itemsSub: FlowSubscription?

    func bind(_ core: StePlayerCore, kind: CatalogKind, sourceId: Int64) {
        guard self.sourceId != sourceId else { return }
        self.core = core
        self.kind = kind
        self.sourceId = sourceId
        guard sourceId >= 0 else { chips = []; items = ListSnapshot([]); return }
        catSub = observeList(core.catalog.categories(sourceId: sourceId, type: kind.type), as: Shared.Category.self) { [weak self] cats in
            guard let self else { return }
            let chips = [
                ChipItem(id: "__recent__", title: "Recenti"),
                ChipItem(id: "__fav__", title: "Preferiti"),
                ChipItem(id: "__all__", title: "Tutti"),
            ] + cats.map { ChipItem(id: $0.id, title: $0.name) }
            if chips != self.chips { self.chips = chips }
            let current = self.selected.flatMap { s in chips.contains(where: { $0.id == s }) ? s : nil }
            let target = current ?? DemoHooks.category ?? chips.first?.id ?? "__recent__"
            if target != self.selected || self.itemsSub == nil { self.select(target) }
        }
    }

    func select(_ id: String) {
        selected = id
        items = nil
        guard let core else { return }
        let limit: Int64 = id == "__recent__" ? 120 : 5000
        // Up to 5,000 items: Kotlin → Swift conversion and formatting run off the main thread,
        // and identical re-emissions (sync, favourites elsewhere) don't touch the grid.
        let deliver: @MainActor ([GridEntry]) -> Void = { [weak self] entries in
            guard let self, self.selected == id else { return }
            self.items = ListSnapshot(entries)
        }
        switch kind {
        case .movies:
            itemsSub = observeListOffMain(core.catalog.movies(sourceId: sourceId, categoryId: id, limit: limit, offset: 0), as: Movie.self,
                                          transform: { list in
                list.map {
                    GridEntry(sourceId: $0.sourceId, id: $0.id, title: $0.name, image: $0.poster,
                              subtitle: $0.year.map { String($0.int32Value) }, rating: AFormat.rating($0.rating?.doubleValue))
                }
            }, deliver)
        case .series:
            itemsSub = observeListOffMain(core.catalog.series(sourceId: sourceId, categoryId: id, limit: limit, offset: 0), as: Series.self,
                                          transform: { list in
                list.map {
                    GridEntry(sourceId: $0.sourceId, id: $0.id, title: $0.name, image: $0.cover,
                              subtitle: $0.year.map { String($0.int32Value) }, rating: AFormat.rating($0.rating?.doubleValue))
                }
            }, deliver)
        }
    }
}

/// Android `VodGridScreen`: top bar, category chips, adaptive poster grid.
struct CatalogView: View {
    let kind: CatalogKind
    @Environment(AppModel.self) private var app
    @State private var model = CatalogModel()

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: Theme.isTV ? 220 : 112), spacing: Theme.isTV ? 40 : 14, alignment: .top)]
    }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: kind.title, large: true) { SearchSettingsActions() }
            CategoryChips(items: model.chips, selected: model.selected) { model.select($0) }
                .frame(height: Theme.isTV ? 80 : 40)
                .padding(.bottom, 8)
            if let items = model.items?.items {
                if items.isEmpty {
                    EmptyState(symbol: kind.emptySymbol, text: kind.emptyText)
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: Theme.isTV ? 48 : 18) {
                            ForEach(items) { item in
                                PosterCard(title: item.title, imageURL: item.image, subtitle: item.subtitle, rating: item.rating) {
                                    app.open(kind == .movies ? .movie(sourceId: item.sourceId, id: item.id) : .series(sourceId: item.sourceId, id: item.id))
                                }
                            }
                        }
                        .padding(16)
                    }
                    .id(model.selected)
                }
            } else {
                LoadingBox()
            }
        }
        .screenBackground()
        .onAppear { model.bind(app.core, kind: kind, sourceId: app.activeId) }
        .onChange(of: app.activeId) { _, id in model.bind(app.core, kind: kind, sourceId: id) }
    }
}
