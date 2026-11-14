import Foundation
import Shared

/// A programme reduced to what the lists show (plain Swift values: cheap to compare, safe off-main).
struct EpgSlot: Equatable, Sendable {
    let title: String
    let startAt: Int64
    let endAt: Int64

    init?(_ p: EpgProgram?) {
        guard let p else { return nil }
        title = p.title
        startAt = p.startAt
        endAt = p.endAt
    }
}

/// One channel line of the Live list / player drawer, built off the main thread.
/// Equality ignores the Kotlin `channel` (kept only to start playback) and compares what is drawn,
/// so SwiftUI skips rows whose content did not change.
struct LiveRow: Identifiable, Equatable, @unchecked Sendable {
    let channel: Channel
    let id: String
    let num: Int32?
    let name: String
    let logo: String?
    let hasArchive: Bool
    var now: EpgSlot?
    var next: EpgSlot?

    init(_ c: Channel) {
        channel = c
        id = c.id
        num = c.num?.int32Value
        name = c.name
        logo = c.logo
        hasArchive = c.archiveDays > 0
    }

    static func == (a: LiveRow, b: LiveRow) -> Bool {
        a.id == b.id && a.num == b.num && a.name == b.name && a.logo == b.logo && a.hasArchive == b.hasArchive
            && a.now == b.now && a.next == b.next
    }
}

/// A channel list emission converted in the background.
struct ChannelBatch: Equatable, @unchecked Sendable {
    let channels: [Channel]
    let rows: [LiveRow]

    init(_ channels: [Channel]) {
        self.channels = channels
        rows = channels.map(LiveRow.init)
    }

    static func == (a: ChannelBatch, b: ChannelBatch) -> Bool { a.rows == b.rows }
}

/// Now/next of one chunk of channels, keyed by channel id.
struct EpgChunk: Equatable, Sendable {
    struct Entry: Equatable, Sendable { let now: EpgSlot?; let next: EpgSlot? }
    let entries: [String: Entry]

    init(_ list: [ChannelWithEpg]) {
        var map: [String: Entry] = [:]
        map.reserveCapacity(list.count)
        for item in list { map[item.channel.id] = Entry(now: EpgSlot(item.now), next: EpgSlot(item.next)) }
        entries = map
    }
}

/// Keeps a channel list's now/next current, like Android (`ticker()` + `nowNextChunked`): the query is
/// re-run every minute in chunks of 800 channels (SQLite variable limit); all conversion and merging runs
/// off the main thread and `onRows` fires only when something visible changed.
@MainActor
final class NowNextFeed {
    private let core: StePlayerCore
    private let sourceId: Int64
    private let base: [LiveRow]
    private let chunks: [[Channel]]
    private var epg: [String: EpgChunk.Entry]
    private var subs: [FlowSubscription] = []
    private var ticker: Task<Void, Never>?
    private var merging = false
    private var pendingParts: [EpgChunk] = []
    private var lastRows: [LiveRow]
    private let onRows: ([LiveRow]) -> Void

    /// `carryOver`: now/next already known (e.g. the previous emission of the same list) so rows don't blink empty.
    init(core: StePlayerCore, sourceId: Int64, batch: ChannelBatch, limit: Int = .max,
         carryOver: [String: EpgChunk.Entry] = [:], onRows: @escaping ([LiveRow]) -> Void) {
        self.core = core
        self.sourceId = sourceId
        self.base = batch.rows
        let head = Array(batch.channels.prefix(limit))
        self.chunks = stride(from: 0, to: head.count, by: 800).map { Array(head[$0..<min($0 + 800, head.count)]) }
        self.epg = carryOver
        self.lastRows = []
        self.onRows = onRows
    }

    /// Now/next known so far (to carry over into the next feed).
    var known: [String: EpgChunk.Entry] { epg }

    /// Rows with whatever now/next is already known, synchronously (first frame of a new list).
    func initialRows() -> [LiveRow] {
        lastRows = Self.merge(base, epg)
        return lastRows
    }

    func start() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                if let feed = self { feed.subscribe(now: .nowMs) } else { return }
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }

    func stop() {
        ticker?.cancel()
        ticker = nil
        subs.forEach { $0.cancel() }
        subs = []
    }

    private func subscribe(now: Int64) {
        let fresh = chunks.map { chunk in
            observeListOffMain(core.epg.nowNext(sourceId: sourceId, channels: chunk, now: now), as: ChannelWithEpg.self,
                               transform: EpgChunk.init) { [weak self] (part: EpgChunk) in
                self?.apply(part)
            }
        }
        subs.forEach { $0.cancel() }
        subs = fresh
    }

    private func apply(_ part: EpgChunk) {
        pendingParts.append(part)
        scheduleMerge()
    }

    /// Coalesces chunk arrivals: one background merge at a time (dictionary merge, row rebuild and comparison
    /// all off the main thread), re-run if more chunks came in meanwhile.
    private func scheduleMerge() {
        guard !merging, !pendingParts.isEmpty else { return }
        merging = true
        let base = base, epg = epg, previous = lastRows, parts = pendingParts
        pendingParts = []
        Task { [weak self] in
            let (merged, rows) = await offMain { () -> ([String: EpgChunk.Entry], [LiveRow]?) in
                var all = epg
                for part in parts { all.merge(part.entries) { _, new in new } }
                let rows = Self.merge(base, all)
                return (all, rows == previous ? nil : rows)
            }
            guard let self else { return }
            self.epg = merged
            if let rows {
                self.lastRows = rows
                self.onRows(rows)
            }
            self.merging = false
            self.scheduleMerge()
        }
    }

    nonisolated static func merge(_ base: [LiveRow], _ epg: [String: EpgChunk.Entry]) -> [LiveRow] {
        guard !epg.isEmpty else { return base }
        return base.map { row in
            guard let e = epg[row.id] else { return row }
            var r = row
            r.now = e.now
            r.next = e.next
            return r
        }
    }

    deinit {
        ticker?.cancel()
    }
}
