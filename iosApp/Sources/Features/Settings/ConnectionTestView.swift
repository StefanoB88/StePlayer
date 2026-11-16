import SwiftUI
import Shared

/// Android `ConnectionTestScreen`: measures the home network and the provider, recommends a buffer profile.
struct ConnectionTestView: View {
    var sourceId: Int64
    var channel: Channel?
    /// Set when shown from the player; otherwise "back" pops the navigation stack.
    var onClose: (() -> Void)?

    @Environment(AppModel.self) private var app
    @State private var running = false
    @State private var step = ""
    @State private var progress: Double = 0
    @State private var result: ConnectionTestResult?
    @State private var failure: String?
    @State private var settings: PlayerSettings?

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Test connessione", onBack: { onClose?() ?? app.back() })
            ScrollView {
                VStack(spacing: 0) {
                    if running {
                        ProgressView().controlSize(.large).tint(Theme.primary)
                        Spacer().frame(height: 16)
                        Text(step.isEmpty ? "Avvio del test…" : step).font(AppFont.titleMedium).foregroundStyle(Theme.textPrimary)
                            .multilineTextAlignment(.center)
                        Spacer().frame(height: 12)
                        ProgressBar(progress: progress, height: 6)
                    } else if let r = result {
                        resultView(r)
                    } else if let failure {
                        Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 48)).foregroundStyle(Theme.live)
                        Spacer().frame(height: 12)
                        Text("Test non riuscito").font(AppFont.titleMedium).foregroundStyle(Theme.textPrimary)
                        Spacer().frame(height: 6)
                        Text(failure).font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                        Spacer().frame(height: 20)
                        PrimaryButton(text: "Ripeti test") { start() }
                    } else {
                        Image(systemName: "speedometer").font(.system(size: 54)).foregroundStyle(Theme.primary)
                        Spacer().frame(height: 12)
                        Text("Misura la tua rete e la velocità del provider").foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                        Spacer().frame(height: 20)
                        PrimaryButton(text: "Avvia test") { start() }
                    }
                    Spacer().frame(height: 28)
                    Text("Il test scarica un file anonimo da un server di prova pubblico per misurare la tua rete.")
                        .font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                }
                .padding(20)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
        }
        .screenBackground()
        .onAppear {
            #if DEBUG
            if ProcessInfo.processInfo.environment["STEPLAYER_DEMO_CONNTEST_AUTOSTART"] == "1", result == nil, !running { start() }
            #endif
        }
        .task {
            await holdUntilCancelled([
                observe(app.core.connectionTest.state) { state in
                    if let r = state as? ConnectionTestStateRunning {
                        step = r.step
                        progress = Double(r.progress)
                    }
                },
                observe(app.core.playerPreferences.settings) { (s: PlayerSettings) in settings = s },
            ])
        }
    }

    @ViewBuilder
    private func resultView(_ r: ConnectionTestResult) -> some View {
        let color: Color = r.verdict == .good ? Theme.success : ((r.verdict == .offline || r.verdict == .providerError) ? Theme.live : Theme.warning)
        VStack(alignment: .leading, spacing: 8) {
            Text(r.title).font(AppFont.headlineSmall).foregroundStyle(color)
            Text(r.message).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary).fixedSize(horizontal: false, vertical: true)
            if let name = r.testedChannelName {
                Text("Canale di prova: \(name)").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        Spacer().frame(height: 16)
        HStack(spacing: 12) {
            Gauge(label: "La tua rete", mbps: r.internetMbps?.doubleValue)
            Gauge(label: "Provider", mbps: r.providerMbps?.doubleValue)
        }
        Spacer().frame(height: 10)
        let extra = [r.providerFirstByteMs.map { "Primo byte: \($0.int64Value) ms" },
                     r.providerStability.map { "Stabilità: \(Int($0.doubleValue * 100))%" }].compactMap { $0 }.joined(separator: "   ·   ")
        if !extra.isEmpty { Text(extra).font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary) }
        Spacer().frame(height: 18)
        FlowLayout(spacing: 10, lineSpacing: 10) {
            if let settings, r.recommendedBuffer != settings.bufferMode {
                PrimaryButton(text: "Applica buffer consigliato (\(BufferModeLabel.title(r.recommendedBuffer)))") {
                    Task {
                        let updated = settings.doCopy(stillWatchingAfterHours: settings.stillWatchingAfterHours, bufferMode: r.recommendedBuffer,
                                                      preferredAudioLanguages: settings.preferredAudioLanguages,
                                                      preferredSubtitleLanguage: settings.preferredSubtitleLanguage,
                                                      subtitleMode: settings.subtitleMode, subtitleStyle: settings.subtitleStyle,
                                                      showStatsOverlay: settings.showStatsOverlay)
                        try? await app.core.playerPreferences.save(settings: updated)
                        app.showToast("Buffer impostato")
                    }
                }
            }
            SecondaryButton(text: "Ripeti test") { start() }
        }
    }

    private func start() {
        guard !running else { return }
        running = true
        result = nil
        failure = nil
        step = ""
        progress = 0
        Task {
            // The player is already stopped (error screen) or not running (settings).
            // Kotlin default arguments aren't visible from Swift: pass the reference server explicitly,
            // otherwise the home-network measurement is skipped.
            do {
                result = try await app.core.connectionTest.run(
                    sourceId: sourceId, channel: channel,
                    referenceUrl: ConnectionTest.companion.DEFAULT_REFERENCE)
            } catch {
                failure = readableError(error)
            }
            running = false
        }
    }
}

private struct Gauge: View {
    let label: String
    let mbps: Double?
    var body: some View {
        VStack(spacing: 10) {
            Text(label).font(AppFont.labelLarge).foregroundStyle(Theme.textSecondary)
            ZStack {
                Circle().stroke(Theme.surfaceElevated, lineWidth: 8)
                Circle().trim(from: 0, to: CGFloat(min(1, max(0, (mbps ?? 0) / 50))))
                    .stroke(Theme.accent2, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text(mbps.map { String(format: "%.1f", $0).replacingOccurrences(of: ".", with: ",") } ?? "—")
                    .font(AppFont.titleLarge).foregroundStyle(Theme.textPrimary)
            }
            .frame(width: Theme.isTV ? 140 : 96, height: Theme.isTV ? 140 : 96)
            Text("Mbps").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
