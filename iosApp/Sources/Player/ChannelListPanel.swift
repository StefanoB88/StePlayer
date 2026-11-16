import SwiftUI
import Shared

/// Now-playing data for the player's channel list (rows built off the main thread, now/next kept current).
@MainActor
@Observable
final class ChannelListModel {
    var rows = ListSnapshot<LiveRow>([])
    @ObservationIgnored private var feed: NowNextFeed?
    @ObservationIgnored private var boundIds: [String] = []

    func bind(core: StePlayerCore, sourceId: Int64, channels: [Channel]) {
        let ids = channels.map(\.id)
        guard ids != boundIds else { return }
        boundIds = ids
        feed?.stop()
        Task { [weak self] in
            let batch = await offMain { UncheckedBox(ChannelBatch(channels)) }.value
            guard let self, self.boundIds == ids else { return }
            let feed = NowNextFeed(core: core, sourceId: sourceId, batch: batch, limit: 800) { [weak self] rows in
                self?.rows = ListSnapshot(rows)
            }
            self.feed = feed
            self.rows = ListSnapshot(feed.initialRows())
            feed.start()
        }
    }

    func unbind() {
        feed?.stop()
        feed = nil
        boundIds = []
    }
}

/// Android `ChannelDrawer`: left drawer with the zapping list.
struct ChannelDrawer: View {
    @Bindable var vm: PlayerViewModel
    let core: StePlayerCore
    let onPick: () -> Void
    @State private var model = ChannelListModel()

    private var currentId: String? { vm.channel?.id }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Canali").font(AppFont.titleLarge).foregroundStyle(Theme.textPrimary)
                .padding(.horizontal, 20).padding(.vertical, 8)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(model.rows.items) { item in
                            let selected = item.id == currentId
                            Button {
                                vm.switchTo(item.channel)
                                onPick()
                            } label: {
                                HStack(spacing: 10) {
                                    ArtImage(url: item.logo, title: item.name, contentMode: .fit, points: 56)
                                        .frame(width: 56, height: 38)
                                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text([item.num.map { String($0) }, item.name].compactMap { $0 }.joined(separator: "  "))
                                            .font(AppFont.titleSmall).foregroundStyle(Theme.textPrimary).lineLimit(1)
                                        if let p = item.now {
                                            Text(p.title).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
                                            EpgProgressBar(startAt: p.startAt, endAt: p.endAt, height: 2).padding(.top, 4)
                                        }
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                .padding(8)
                                .background(selected ? Theme.primary.opacity(0.22) : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressStyle(radius: 12, scale: 1.02))
                            .padding(.horizontal, 10).padding(.vertical, 3)
                            .id(item.id)
                        }
                    }
                }
                .onChange(of: model.rows.items.isEmpty) { _, empty in
                    // Rows arrive asynchronously: centre the playing channel once they are there.
                    if !empty, let id = currentId { proxy.scrollTo(id, anchor: .center) }
                }
                .onAppear {
                    if let id = currentId, !model.rows.items.isEmpty { DispatchQueue.main.async { proxy.scrollTo(id, anchor: .center) } }
                }
            }
        }
        .padding(.vertical, 16)
        .onAppear { model.bind(core: core, sourceId: vm.sourceId, channels: vm.zapList) }
        .onDisappear { model.unbind() }
    }
}

/// Android `ZapBanner`: channel artwork, number + name, now/next, LIVE badge and "Ultimo canale".
struct ZapBanner: View {
    @Bindable var vm: PlayerViewModel

    var body: some View {
        if let ch = vm.channel {
            HStack(spacing: 14) {
                ArtImage(url: ch.logo, title: ch.name, contentMode: .fit, points: 80)
                    .frame(width: 80, height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 0) {
                        if let num = ch.num {
                            Text("\(num.int32Value)  ").font(AppFont.titleMedium).foregroundStyle(Theme.accent2)
                        }
                        Text(ch.name).font(AppFont.titleMedium).foregroundStyle(Theme.textPrimary).lineLimit(1)
                    }
                    Text(vm.nowProgram?.title ?? "Nessuna informazione sul programma")
                        .font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary).lineLimit(1)
                    if let p = vm.nowProgram {
                        EpgProgressBar(startAt: p.startAt, endAt: p.endAt, height: 3).padding(.top, 6)
                    }
                    if let n = vm.nextProgram {
                        Text("Dopo: \(Fmt.time(n.startAt))  \(n.title)").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
                            .padding(.top, 6)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .trailing, spacing: 6) {
                    LiveBadge()
                    if vm.lastChannelName != nil && !Theme.isTV {
                        RoundIconButton(symbol: "clock.arrow.circlepath", label: "Ultimo canale", size: 36) { vm.back() }
                    }
                }
            }
            .padding(14)
            .frame(maxWidth: Theme.isTV ? 1000 : 720)
            .background(Theme.surface.opacity(0.92), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .padding(24)
        }
    }
}
