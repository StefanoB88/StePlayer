#if os(iOS)
import SwiftUI
import Shared

/// Android `DownloadsScreen` (the Android-only battery tip is not applicable on iOS).
struct DownloadsView: View {
    @Environment(AppModel.self) private var app
    @State private var downloads: [Download]?

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Download", large: true)
            if let items = downloads {
                if items.isEmpty {
                    EmptyState(symbol: "arrow.down.to.line", text: "Nessun download. Scarica film ed episodi per guardarli offline.")
                } else {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(items, id: \.id) { d in
                                DownloadRow(download: d,
                                            onPlay: { Task { await play(d) } },
                                            onPause: { Task { try? await app.core.downloads.pause(id: d.id) } },
                                            onResume: { Task { try? await app.core.downloads.resume(id: d.id) } },
                                            onDelete: { confirmDelete(d) })
                            }
                        }
                        .padding(.horizontal, 16).padding(.vertical, 8)
                    }
                }
            } else {
                Spacer()
            }
        }
        .screenBackground()
        .task {
            await holdUntilCancelled([observeList(app.core.downloads.downloads, as: Download.self) { list in
                if list != downloads { downloads = list }
            }])
        }
    }

    private func confirmDelete(_ d: Download) {
        app.confirm = ConfirmDialog(title: "Eliminare il download?",
                                    message: [d.title, d.subtitle].compactMap { $0 }.joined(separator: " · "),
                                    confirmTitle: "Elimina") {
            Task { try? await app.core.downloads.delete(id: d.id) }
        }
    }

    private func play(_ d: Download) async {
        guard d.status == DownloadStatus.completed else { return }
        let core = app.core
        if d.type == ContentType.episode {
            let series = (try? await core.catalog.seriesById(sourceId: d.sourceId, id: d.parentId ?? ""))
                ?? Series(sourceId: d.sourceId, id: d.parentId ?? "", name: d.title, cover: d.image, categoryId: nil, plot: nil, rating: nil, year: nil, genre: nil)
            let episode = (try? await core.catalog.episode(sourceId: d.sourceId, id: d.itemId))
                ?? Episode(sourceId: d.sourceId, seriesId: d.parentId ?? "", id: d.itemId, season: 0, number: 0,
                           title: d.subtitle ?? d.title, plot: nil, image: d.image, durationSec: nil, extension: d.extension)
            app.play(.episode(series, episode, all: [episode]))
        } else {
            let movie = (try? await core.catalog.movie(sourceId: d.sourceId, id: d.itemId))
                ?? Movie(sourceId: d.sourceId, id: d.itemId, name: d.title, poster: d.image, categoryId: nil,
                         rating: nil, year: nil, extension: d.extension, addedAt: nil)
            app.play(.movie(movie))
        }
    }
}

private struct DownloadRow: View {
    let download: Download
    /// Read here, not in the list: a speed tick re-renders only this row.
    private var speed: DownloadSpeedStore.Speed? { DownloadSpeedStore.shared.speeds[download.id] }
    let onPlay: () -> Void
    let onPause: () -> Void
    let onResume: () -> Void
    let onDelete: () -> Void

    var body: some View {
        let d = download
        HStack(spacing: 0) {
            Button(action: onPlay) {
                HStack(spacing: 12) {
                    AspectBox(ratio: 2.0 / 3.0) { ArtImage(url: d.image, title: d.title, points: 96) }
                        .frame(width: 64)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    // Constant layout whatever the state (Android): title / subtitle (reserved even when empty:
                    // episodes show "S01E02 · title" there) / ONE status line / progress bar slot (always reserved,
                    // hidden when it doesn't apply).
                    VStack(alignment: .leading, spacing: 0) {
                        Text(d.title).font(AppFont.titleSmall).foregroundStyle(Theme.textPrimary).lineLimit(1)
                        Text(d.subtitle ?? " ").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
                            .accessibilityHidden(d.subtitle == nil)
                        Spacer().frame(height: 6)
                        Text(statusLine).font(AppFont.labelMedium).foregroundStyle(statusColor)
                            // A failure may take two lines so the whole reason is readable; the rest is one line.
                            .lineLimit(d.status == DownloadStatus.failed ? 2 : 1).truncationMode(.tail)
                        ProgressBar(progress: Double(d.fraction)).padding(.top, 6)
                            .opacity(showsProgress ? 1 : 0)
                            .accessibilityHidden(!showsProgress)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 96)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle(radius: 14, scale: 1.02))
            Spacer().frame(width: 6)
            switch d.status {
            case DownloadStatus.completed:
                RoundIconButton(symbol: "play.fill", label: "Riproduci", background: Theme.primary.opacity(0.2), action: onPlay)
            case DownloadStatus.running, DownloadStatus.queued:
                RoundIconButton(symbol: "pause.fill", label: "Pausa", action: onPause)
            case DownloadStatus.paused:
                RoundIconButton(symbol: "play.fill", label: "Riprendi", action: onResume)
            default:
                RoundIconButton(symbol: "arrow.clockwise", label: "Riprova", action: onResume)
            }
            RoundIconButton(symbol: "trash.fill", label: "Elimina", tint: Theme.textSecondary, action: onDelete)
        }
        .padding(10)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .contextMenu {
            switch d.status {
            case DownloadStatus.running, DownloadStatus.queued: Button("Pausa", systemImage: "pause", action: onPause)
            case DownloadStatus.paused, DownloadStatus.failed: Button("Riprendi", systemImage: "play", action: onResume)
            default: Button("Riproduci", systemImage: "play.fill", action: onPlay)
            }
            Button("Elimina", systemImage: "trash", role: .destructive, action: onDelete)
        }
    }

    private var showsProgress: Bool {
        // Same rule as Android: the bar shows while the download is active, waiting or paused.
        [DownloadStatus.running, DownloadStatus.paused, DownloadStatus.queued].contains(download.status)
    }

    /// "24% di 1,2 GB · 2,4 MB/s · 3 min", "In pausa · 300 MB di 1,2 GB", "In attesa · 1,2 GB",
    /// "Completato · 1,2 GB", "Non riuscito · <motivo>" (same wording as Android).
    private var statusLine: String {
        let d = download
        let total = d.totalBytes > 0 ? Self.size(d.totalBytes) : nil
        let progress = total.map { "\(Self.size(d.bytesDownloaded)) di \($0)" }
        let parts: [String?]
        switch d.status {
        case DownloadStatus.queued:
            parts = ["In attesa", total]
        case DownloadStatus.running:
            let s = speed.flatMap { $0.bytesPerSecond > 0 ? $0 : nil }
            let rate = s.map { AFormat.speed($0.bytesPerSecond) }
            if let total { // short enough for a phone row: "24% di 1,2 GB · 2,4 MB/s · 3 min"
                parts = ["\(Int(d.fraction * 100))% di \(total)", rate, s?.etaSeconds.map(Self.eta)]
            } else { // unknown size: "300 MB · 2,4 MB/s"
                parts = [Self.size(d.bytesDownloaded), rate]
            }
        case DownloadStatus.paused:
            parts = ["In pausa", progress]
        case DownloadStatus.completed:
            parts = ["Completato", total ?? (d.bytesDownloaded > 0 ? Self.size(d.bytesDownloaded) : nil)]
        default:
            parts = ["Non riuscito", d.error.flatMap { $0.isEmpty ? nil : $0 }]
        }
        return parts.compactMap { $0 }.joined(separator: " · ")
    }

    /// "1,2 GB", "300 MB", "850 KB".
    private static func size(_ b: Int64) -> String {
        let v = Double(b), gb = 1_073_741_824.0, mb = 1_048_576.0
        if v >= gb { return String(format: "%.1f GB", v / gb).replacingOccurrences(of: ".", with: ",") }
        if v >= mb { return String(format: "%.0f MB", v / mb) }
        return AFormat.bytes(b)
    }

    /// Short remaining time: "40 s", "3 min", "1 h 05 min".
    private static func eta(_ seconds: Int64) -> String {
        if seconds < 60 { return "\(max(seconds, 1)) s" }
        if seconds < 3600 { return "\((seconds + 59) / 60) min" }
        return String(format: "%d h %02d min", seconds / 3600, (seconds % 3600) / 60)
    }

    private var statusColor: Color {
        switch download.status {
        case DownloadStatus.completed: return Theme.success
        case DownloadStatus.failed: return Theme.live
        default: return Theme.textSecondary
        }
    }
}
#endif
