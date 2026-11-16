import SwiftUI
import Shared

/// Grid EPG (Android `GuideScreen`): channels × 30 h timeline with a "now" line.
///
/// Performance notes (300 channels × ~60 programmes):
/// - the programme grid is ONE two-axis scroll view, so its LazyVStack is really lazy (a LazyVStack inside a
///   horizontal scroll view nested in a vertical one builds every row and froze the main thread for seconds);
/// - the ruler and the channel column follow the grid through `GuideScroll`, and each of the three parts is its
///   own view: a scroll frame re-renders only the small part that moves, never the whole grid;
/// - programmes are converted to plain Swift values (and their times formatted) off the main thread.
struct GuideView: View {
    let sourceId: Int64
    let categoryId: String
    @Environment(AppModel.self) private var app

    @State private var loading = true
    /// Channels with their programmes, as plain Swift values built off the main thread.
    @State private var rows: [GuideRowData] = []
    @State private var scroll = GuideScroll()
    /// Fixed when the screen opens (@State: a parent re-render must not shift the window under loaded data).
    @State private var metrics = GuideMetrics(now: .nowMs)

    private let maxChannels = 300

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Guida TV", onBack: { app.back() })
            if loading {
                LoadingBox()
            } else if rows.isEmpty {
                EmptyState(symbol: "calendar", text: "Nessun canale in questa categoria")
            } else {
                GuideRuler(metrics: metrics, scroll: scroll)
                HStack(alignment: .top, spacing: 0) {
                    GuideChannelColumn(rows: rows, metrics: metrics, scroll: scroll) { ch in
                        app.playChannel(ch, categoryId: categoryId, list: rows.map(\.channel))
                    }
                    GuideProgramGrid(rows: rows, metrics: metrics, scroll: scroll) { slot, ch in
                        tap(slot.program, on: ch)
                    }
                }
            }
        }
        .task { await load() }
    }

    private func tap(_ p: EpgProgram, on ch: Channel) {
        let now = metrics.now
        if p.isLive(now: now) {
            app.playChannel(ch, categoryId: categoryId, list: rows.map(\.channel))
        } else if p.endAt <= now, ch.archiveDays > 0, p.startAt >= now - Int64(ch.archiveDays) * 86_400_000 {
            app.play(.catchup(ch, p))
        } else if p.endAt <= now {
            app.showToast("Replay non disponibile per questo canale")
        } else {
            app.showToast("\(Fmt.dayTime(p.startAt)) · \(p.title)")
        }
    }

    private func load() async {
        guard loading else { return }
        let core = app.core
        let list = await firstList(core.catalog.channels(sourceId: sourceId, categoryId: categoryId), as: Channel.self)
        guard !Task.isCancelled else { return }
        let shown = UncheckedBox(Array(list.prefix(maxChannels)))
        let ids = Array(Set(shown.value.compactMap(\.epgId)))
        let m = metrics
        let raw = UncheckedBox((try? await core.epg.guide(sourceId: sourceId, epgIds: ids, from: m.from, to: m.to)) ?? [:])
        guard !Task.isCancelled else { return }
        // Reading thousands of Kotlin programmes (every String is converted on access) and formatting the
        // times is done once, in the background; the grid then only touches plain Swift values.
        rows = await offMain { () -> UncheckedBox<[GuideRowData]> in
            let byEpg = raw.value
            return UncheckedBox(shown.value.map { ch in
                GuideRowData(ch, slots: (ch.epgId.flatMap { byEpg[$0] } ?? []).compactMap { GuideSlot($0, from: m.from, to: m.to) })
            })
        }.value
        // Start half an hour before now (Android).
        scroll.initialX = max(0, m.x(m.now - 30 * 60_000))
        loading = false
    }
}

/// One guide line: the channel and its programmes.
struct GuideRowData: Identifiable, @unchecked Sendable {
    let channel: Channel
    let id: String
    let name: String
    let logo: String?
    let archive: Bool
    let slots: [GuideSlot]

    init(_ c: Channel, slots: [GuideSlot]) {
        channel = c
        id = c.id
        name = c.name
        logo = c.logo
        archive = c.archiveDays > 0
        self.slots = slots
    }
}

/// Geometry of the guide, fixed for the lifetime of the screen.
struct GuideMetrics {
    let now: Int64
    let from: Int64
    var to: Int64 { from + 30 * 3_600_000 }
    let rowHeight: CGFloat = Theme.isTV ? 96 : 68
    let channelColumn: CGFloat = Theme.isTV ? 260 : 150
    let perMinute: CGFloat = Theme.isTV ? 6 : 5
    var totalWidth: CGFloat { perMinute * CGFloat(to - from) / 60_000 }

    init(now: Int64) {
        self.now = now
        let halfHour: Int64 = 30 * 60_000
        from = (now - 6 * 3_600_000) / halfHour * halfHour
    }

    func x(_ ms: Int64) -> CGFloat { perMinute * CGFloat(ms - from) / 60_000 }
}

/// Scroll offsets shared by the grid (leader), the ruler and the channel column (followers).
@MainActor
@Observable
final class GuideScroll {
    /// Content offset of the programme grid, one property per axis (each follower observes only its axis).
    var gridX: CGFloat = 0
    var gridY: CGFloat = 0
    /// Vertical offset requested by dragging the channel column (the grid follows).
    var columnY: CGFloat = 0
    @ObservationIgnored var initialX: CGFloat = 0
    /// Last offsets actually applied, to break the follow-the-follower loop.
    @ObservationIgnored var lastGrid: CGPoint = .zero
    @ObservationIgnored var lastColumnY: CGFloat = 0
}

private struct GuideRuler: View {
    let metrics: GuideMetrics
    let scroll: GuideScroll
    @State private var position = ScrollPosition(idType: Int.self)

    var body: some View {
        HStack(spacing: 0) {
            Spacer().frame(width: metrics.channelColumn)
            ScrollView(.horizontal, showsIndicators: false) {
                ZStack(alignment: .leading) {
                    ForEach(Array(stride(from: metrics.from, to: metrics.to, by: 30 * 60_000)), id: \.self) { t in
                        Text(Fmt.time(t)).font(AppFont.labelMedium).foregroundStyle(Theme.textSecondary)
                            .offset(x: metrics.x(t) + 6)
                    }
                    Rectangle().fill(Theme.live).frame(width: 2).offset(x: metrics.x(metrics.now))
                }
                .frame(width: metrics.totalWidth, height: 36, alignment: .leading)
            }
            .scrollPosition($position)
            .scrollDisabled(true)
        }
        .frame(height: 36)
        .onChange(of: scroll.gridX, initial: true) { _, x in position.scrollTo(x: x) }
    }
}

/// Follows the grid vertically; the list itself is a separate Equatable view, so following doesn't rebuild it.
private struct GuideChannelColumn: View {
    let rows: [GuideRowData]
    let metrics: GuideMetrics
    let scroll: GuideScroll
    let onPlay: (Channel) -> Void
    @State private var position = ScrollPosition(idType: Int.self)

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            GuideChannelList(rows: rows, metrics: metrics, onPlay: onPlay).equatable()
        }
        .frame(width: metrics.channelColumn)
        .scrollPosition($position)
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y } action: { _, y in
            scroll.lastColumnY = y
            // Dragged by the user (not following the grid): the grid follows the column.
            if abs(scroll.lastGrid.y - y) > 0.5 { scroll.columnY = y }
        }
        .onChange(of: scroll.gridY) { _, y in
            if abs(scroll.lastColumnY - y) > 0.5 { position.scrollTo(y: y) }
        }
    }
}

private struct GuideChannelList: View, Equatable {
    let rows: [GuideRowData]
    let metrics: GuideMetrics
    let onPlay: (Channel) -> Void

    static func == (a: Self, b: Self) -> Bool { a.rows.count == b.rows.count && a.rows.first?.id == b.rows.first?.id }

    var body: some View {
        LazyVStack(spacing: 0) {
            ForEach(rows) { row in
                Button { onPlay(row.channel) } label: {
                    HStack(spacing: 8) {
                        ArtImage(url: row.logo, title: row.name, contentMode: .fit, points: 40)
                            .frame(width: 40, height: 40)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                        Text(row.name).font(AppFont.labelMedium).foregroundStyle(Theme.textPrimary).lineLimit(2)
                        Spacer(minLength: 0)
                    }
                    .padding(6)
                    .frame(maxHeight: .infinity)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(radius: 10, scale: 1.03))
                .padding(.horizontal, 6).padding(.vertical, 3)
                .frame(width: metrics.channelColumn, height: metrics.rowHeight)
            }
        }
        .frame(width: metrics.channelColumn)
    }
}

/// The leader: publishes its offset per axis; follows the channel column only when that one is dragged.
private struct GuideProgramGrid: View {
    let rows: [GuideRowData]
    let metrics: GuideMetrics
    let scroll: GuideScroll
    let onTap: (GuideSlot, Channel) -> Void
    @State private var position = ScrollPosition(idType: Int.self)
    @State private var viewportHeight: CGFloat = 0

    var body: some View {
        ScrollView([.horizontal, .vertical]) {
            GuideGridContent(rows: rows, metrics: metrics, onTap: onTap).equatable()
                // A two-axis scroll view centres content shorter than itself: with few channels the rows slid
                // below the channel column. Never shorter than the viewport, rows pinned to the top.
                .frame(minHeight: viewportHeight, alignment: .topLeading)
        }
        .scrollIndicators(.hidden, axes: .horizontal)
        .onScrollGeometryChange(for: CGFloat.self) { $0.containerSize.height } action: { _, h in viewportHeight = h }
        .scrollPosition($position)
        .onScrollGeometryChange(for: CGPoint.self) { $0.contentOffset } action: { _, offset in
            scroll.lastGrid = offset
            if scroll.gridX != offset.x { scroll.gridX = offset.x }
            if scroll.gridY != offset.y { scroll.gridY = offset.y }
        }
        .onChange(of: scroll.columnY) { _, y in
            if abs(scroll.lastGrid.y - y) > 0.5 { position.scrollTo(point: CGPoint(x: scroll.lastGrid.x, y: y)) }
        }
        .task {
            try? await Task.sleep(for: .milliseconds(50))
            position.scrollTo(x: scroll.initialX)
        }
    }
}

private struct GuideGridContent: View, Equatable {
    let rows: [GuideRowData]
    let metrics: GuideMetrics
    let onTap: (GuideSlot, Channel) -> Void

    static func == (a: Self, b: Self) -> Bool { a.rows.count == b.rows.count && a.rows.first?.id == b.rows.first?.id }

    var body: some View {
        ZStack(alignment: .topLeading) {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(rows) { row in
                    GuideProgramRow(slots: row.slots, archive: row.archive, metrics: metrics) { slot in
                        onTap(slot, row.channel)
                    }
                    .equatable()
                }
            }
            Rectangle().fill(Theme.live).frame(width: 2, height: CGFloat(rows.count) * metrics.rowHeight)
                .offset(x: metrics.x(metrics.now))
                .allowsHitTesting(false)
        }
        .frame(width: metrics.totalWidth, alignment: .leading)
    }
}

/// One channel's programmes. Equatable on its data, so scrolling never rebuilds rows that didn't change.
private struct GuideProgramRow: View, Equatable {
    let slots: [GuideSlot]
    let archive: Bool
    let metrics: GuideMetrics
    let onTap: (GuideSlot) -> Void

    static func == (a: Self, b: Self) -> Bool {
        a.archive == b.archive && a.metrics.from == b.metrics.from && a.slots.map(\.id) == b.slots.map(\.id)
    }

    var body: some View {
        let now = metrics.now
        ZStack(alignment: .topLeading) {
            Color.clear.frame(width: metrics.totalWidth, height: metrics.rowHeight)
            ForEach(slots) { slot in
                let live = slot.startAt <= now && now < slot.endAt
                let past = slot.endAt <= now
                let width = max(8, metrics.perMinute * CGFloat(slot.endAt - slot.clampedStart) / 60_000 - 3)
                let inner = max(0, width - 16)
                // The label may slide right up to here, keeping at least 40 pt (or the whole block) for the text.
                let maxShift = max(0, inner - min(inner, 40))
                Button { onTap(slot) } label: {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 4) {
                            if past && archive {
                                Image(systemName: "clock.arrow.circlepath").font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.accent2)
                            }
                            Text(slot.title).font(AppFont.labelLarge).foregroundStyle(past ? Theme.textSecondary : Theme.textPrimary).lineLimit(1)
                        }
                        Text(slot.times).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
                    }
                    .frame(width: inner, alignment: .leading)
                    // A programme that started before the visible part of the timeline keeps its title and times on
                    // screen: the label is laid out from max(start, left edge of the viewport). Done in a visual
                    // effect, so scrolling never re-renders the rows.
                    .visualEffect { content, proxy in
                        content.offset(x: min(max(0, 8 - proxy.frame(in: .scrollView).minX), maxShift))
                    }
                    .padding(.horizontal, 8).padding(.vertical, 6)
                    .frame(width: width, height: metrics.rowHeight - 6, alignment: .topLeading)
                    .clipped()
                    .background(live ? Theme.primary.opacity(0.28) : (past ? Theme.surface.opacity(0.6) : Theme.surface),
                                in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(radius: 10, scale: 1.02))
                .offset(x: metrics.x(slot.clampedStart), y: 3)
            }
        }
        .frame(width: metrics.totalWidth, height: metrics.rowHeight, alignment: .topLeading)
    }
}

/// A programme cell of the grid: clipped to the guide window, times pre-formatted.
struct GuideSlot: Identifiable, @unchecked Sendable {
    let program: EpgProgram
    let id: Int64
    let title: String
    let startAt: Int64
    let endAt: Int64
    let clampedStart: Int64
    let times: String

    init?(_ p: EpgProgram, from: Int64, to: Int64) {
        let start = max(p.startAt, from)
        guard p.endAt > start, start < to else { return nil }
        program = p
        id = p.startAt
        title = p.title
        startAt = p.startAt
        endAt = p.endAt
        clampedStart = start
        times = "\(Fmt.time(p.startAt)) – \(Fmt.time(p.endAt))"
    }
}
