import SwiftUI
import Shared

/// Android `SearchScreen`: channels, movies, series and TV programmes (live now, upcoming, replay).
struct SearchView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var query = ""
    @State private var results: SmartSearchResults?
    @State private var loading = false
    @State private var recent: [String] = []
    @FocusState private var fieldFocused: Bool

    private var posterWidth: CGFloat { Theme.isTV ? 180 : (sizeClass == .regular ? 140 : 112) }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                RoundIconButton(symbol: "arrow.left", label: "Indietro") { app.back() }
                searchField.padding(.trailing, 8)
            }
            .padding(8)

            let q = query.trimmed
            if q.count < 2 {
                RecentSearches(recent: recent, onPick: { query = $0 }, onClear: {
                    Task { _ = try? await app.core.search.clearRecentQueries() }
                })
            } else if loading && results == nil {
                LoadingBox()
            } else if let r = results, !r.isEmpty {
                resultsList(r)
            } else if !loading {
                EmptyState(symbol: "magnifyingglass", text: "Nessun risultato per «\(q)». Prova con un'altra parola o controlla l'ortografia.")
            } else {
                LoadingBox()
            }
        }
        .task(id: query) { await runSearch() }
        .task {
            await holdUntilCancelled([observeList(app.core.search.recentQueries, as: String.self) { recent = $0 }])
        }
        .onAppear {
            #if os(iOS)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { fieldFocused = true }
            #endif
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.textSecondary)
            TextField("", text: $query, prompt: Text("Cerca canali, film, serie…").foregroundStyle(Theme.textSecondary))
                .font(AppFont.bodyLarge)
                .foregroundStyle(Theme.textPrimary)
                .focused($fieldFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .onSubmit { remember() }
                #if os(iOS)
                .textInputAutocapitalization(.never)
                #endif
            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark").foregroundStyle(Theme.textSecondary).frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Cancella")
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 52)
        .background(Theme.surface, in: Capsule())
        .overlay(Capsule().strokeBorder(fieldFocused ? Theme.primary : Theme.outline, lineWidth: fieldFocused ? 2 : 1))
    }

    // MARK: Search

    private func runSearch() async {
        let q = query.trimmed
        guard q.count >= 2, app.activeId >= 0 else { results = nil; loading = false; return }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        loading = true
        results = nil
        let r = try? await app.core.search.search(sourceId: app.activeId, query: q)
        guard !Task.isCancelled else { return }
        results = r
        loading = false
    }

    private func remember() {
        let t = query.trimmed
        guard t.count >= 2 else { return }
        Task { try? await app.core.search.rememberQuery(query: t) }
    }

    // MARK: Opening results (every tap remembers the query first)

    private func openChannel(_ ch: Channel) { remember(); app.playChannel(ch, categoryId: ch.categoryId) }
    private func openMovie(_ m: Movie) { remember(); app.open(.movie(sourceId: m.sourceId, id: m.id)) }
    private func openSeries(_ s: Series) { remember(); app.open(.series(sourceId: s.sourceId, id: s.id)) }
    private func openProgram(_ hit: SearchHitProgramHit) {
        remember()
        if hit.status == ProgramStatus.replay {
            app.play(.catchup(hit.channel, hit.program))
        } else {
            app.playChannel(hit.channel, categoryId: hit.channel.categoryId)
        }
    }

    // MARK: Results

    private func resultsList(_ r: SmartSearchResults) -> some View {
        let terms = r.terms
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let corrected = r.correctedQuery {
                    HStack(spacing: 10) {
                        Image(systemName: "wand.and.stars").font(.system(size: 16)).foregroundStyle(Theme.primary)
                        VStack(alignment: .leading, spacing: 0) {
                            Text("Risultati per «\(corrected)»").font(AppFont.labelLarge).foregroundStyle(Theme.textPrimary)
                            Text("Forse cercavi: \(corrected)").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(12)
                    .background(Theme.primary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .padding(.horizontal, Theme.gutter).padding(.vertical, 4)
                }
                if !r.topHits.isEmpty {
                    SectionHeader(title: "Migliori risultati")
                    hRow {
                        ForEach(Array(r.topHits.enumerated()), id: \.offset) { _, hit in topHit(hit, terms: terms) }
                    }
                }
                programSection("In onda ora", r.liveNow, terms)
                programSection("Prossimamente", r.upcoming, terms)
                programSection("Replay", r.replay, terms)
                if !r.channels.isEmpty {
                    SectionHeader(title: "Canali")
                    ForEach(r.channels, id: \.channel.id) { hit in
                        ResultRow(logo: hit.channel.logo, name: hit.channel.name, action: { openChannel(hit.channel) }) {
                            SearchHighlight.text(hit.channel.name, terms: terms).font(AppFont.titleSmall).lineLimit(1)
                        }
                    }
                }
                if !r.movies.isEmpty {
                    SectionHeader(title: "Film")
                    hRow {
                        ForEach(r.movies, id: \.movie.id) { hit in
                            let m = hit.movie
                            PosterCard(title: m.name, imageURL: m.poster, subtitle: m.year.map { String($0.int32Value) },
                                       rating: AFormat.rating(m.rating?.doubleValue), width: posterWidth) { openMovie(m) }
                        }
                    }
                }
                if !r.series.isEmpty {
                    SectionHeader(title: "Serie")
                    hRow {
                        ForEach(r.series, id: \.series.id) { hit in
                            let s = hit.series
                            PosterCard(title: s.name, imageURL: s.cover,
                                       subtitle: hit.matchedInPlot ? "nella trama" : s.year.map { String($0.int32Value) },
                                       rating: AFormat.rating(s.rating?.doubleValue), width: posterWidth) { openSeries(s) }
                        }
                    }
                }
            }
            .padding(.bottom, 32)
        }
        .scrollDismissesKeyboard(.immediately)
    }

    @ViewBuilder
    private func programSection(_ title: String, _ hits: [SearchHitProgramHit], _ terms: [String]) -> some View {
        if !hits.isEmpty {
            SectionHeader(title: title)
            ForEach(Array(hits.enumerated()), id: \.offset) { _, hit in
                ResultRow(logo: hit.channel.logo, name: hit.channel.name, action: { openProgram(hit) }) {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 8) {
                            if hit.status == ProgramStatus.live { LiveBadge() }
                            SearchHighlight.text(hit.program.title, terms: terms).font(AppFont.titleSmall).lineLimit(1)
                        }
                        let when = hit.status == ProgramStatus.live
                            ? "\(Fmt.time(hit.program.startAt)) – \(Fmt.time(hit.program.endAt))"
                            : Fmt.relativeDayTime(hit.program.startAt)
                        let extra = hit.otherAirings > 0 ? "  ·  +\(hit.otherAirings) repliche" : ""
                        Text("\(hit.channel.name)  ·  \(when)\(extra)").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
                            .padding(.top, 2)
                        if hit.status == ProgramStatus.live {
                            FractionWidth(fraction: 0.9) {
                                EpgProgressBar(startAt: hit.program.startAt, endAt: hit.program.endAt, height: 3)
                            }
                            .frame(height: 3).padding(.top, 6)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func topHit(_ hit: SearchHit, terms: [String]) -> some View {
        if let m = hit as? SearchHitMovieHit {
            PosterCard(title: m.movie.name, imageURL: m.movie.poster, subtitle: "Film", width: posterWidth) { openMovie(m.movie) }
        } else if let s = hit as? SearchHitSeriesHit {
            PosterCard(title: s.series.name, imageURL: s.series.cover, subtitle: "Serie", width: posterWidth) { openSeries(s.series) }
        } else if let c = hit as? SearchHitChannelHit {
            TileCard(logo: c.channel.logo, rawTitle: c.channel.name, title: SearchHighlight.text(c.channel.name, terms: terms),
                     subtitle: "Canali", live: false, posterWidth: posterWidth) { openChannel(c.channel) }
        } else if let p = hit as? SearchHitProgramHit {
            let sub: String = {
                switch p.status {
                case ProgramStatus.live: return p.channel.name
                case ProgramStatus.upcoming: return "\(p.channel.name) · \(Fmt.relativeDayTime(p.program.startAt))"
                default: return "Replay · \(p.channel.name)"
                }
            }()
            TileCard(logo: p.channel.logo, rawTitle: p.program.title, title: SearchHighlight.text(p.program.title, terms: terms),
                     subtitle: sub, live: p.status == ProgramStatus.live, posterWidth: posterWidth) { openProgram(p) }
        }
    }

    private func hRow<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 12) { content() }
                .padding(.horizontal, Theme.gutter)
        }
        .scrollClipDisabled()
    }
}

private struct RecentSearches: View {
    let recent: [String]
    let onPick: (String) -> Void
    let onClear: () -> Void

    var body: some View {
        if recent.isEmpty {
            EmptyState(symbol: "magnifyingglass", text: "Digita almeno 2 caratteri")
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Ricerche recenti").font(AppFont.titleLarge).foregroundStyle(Theme.textPrimary)
                        Spacer()
                        TextAction(title: "Cancella", action: onClear)
                    }
                    FlowLayout(spacing: 8, lineSpacing: 8) {
                        ForEach(recent, id: \.self) { q in
                            Button { onPick(q) } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "clock.arrow.circlepath").font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                                    Text(q).font(AppFont.labelLarge).foregroundStyle(Theme.textPrimary).lineLimit(1)
                                }
                                .padding(.horizontal, 14).padding(.vertical, 9)
                                .background(Theme.surfaceElevated, in: Capsule())
                                .contentShape(Capsule())
                            }
                            .buttonStyle(PressStyle(radius: 40, scale: 1.04))
                        }
                    }
                }
                .padding(.horizontal, Theme.gutter).padding(.vertical, 8)
            }
        }
    }
}

private struct ResultRow<Content: View>: View {
    let logo: String?
    let name: String
    let action: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ArtImage(url: logo, title: name, contentMode: .fit, points: 56)
                    .padding(4)
                    .frame(width: 64, height: 44)
                    .background(Theme.surfaceElevated)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                content().frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(10)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(radius: 14, scale: 1.02))
        .padding(.horizontal, 12).padding(.vertical, 3)
    }
}

/// Mixed card for the "best results" row: 16:9-ish tile for channels/programmes.
private struct TileCard: View {
    let logo: String?
    let rawTitle: String
    let title: Text
    let subtitle: String
    let live: Bool
    let posterWidth: CGFloat
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: action) {
                ZStack(alignment: .topLeading) {
                    Theme.surface
                    ArtImage(url: logo, title: rawTitle, contentMode: .fit, points: posterWidth * 1.6).padding(28)
                    if live { LiveBadge().padding(8) }
                }
                .frame(width: posterWidth * 1.6, height: posterWidth * 1.5)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .pressStyle(radius: 12)
            Spacer().frame(height: 8)
            title.font(AppFont.titleSmall).foregroundStyle(Theme.textPrimary).lineLimit(1)
            Text(subtitle).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
        }
        .frame(width: posterWidth * 1.6, alignment: .leading)
    }
}

/// Bolds (extra-bold, Accent2) word prefixes that match the search terms, like Android.
enum SearchHighlight {
    static func text(_ string: String, terms: [String]) -> Text {
        guard !terms.isEmpty else { return Text(string) }
        var attributed = AttributedString(string)
        let folded = string.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "it_IT"))
        guard folded.count == string.count else { return Text(string) }
        let chars = Array(folded)
        var i = 0
        while i < chars.count {
            guard chars[i].isLetter || chars[i].isNumber else { i += 1; continue }
            var j = i
            while j < chars.count, chars[j].isLetter || chars[j].isNumber { j += 1 }
            let word = String(chars[i..<j])
            if let term = terms.first(where: { !$0.isEmpty && word.hasPrefix($0) }) {
                let len = min(term.count, j - i)
                let start = attributed.index(attributed.startIndex, offsetByCharacters: i)
                let end = attributed.index(start, offsetByCharacters: len)
                attributed[start..<end].font = AppFont.titleSmall.weight(.heavy)
                attributed[start..<end].foregroundColor = Theme.accent2
            }
            i = j
        }
        return Text(attributed)
    }
}
