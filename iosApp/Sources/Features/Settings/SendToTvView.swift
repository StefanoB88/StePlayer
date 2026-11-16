#if os(iOS)
import Combine
import SwiftUI
import Shared

/// Android `SendToTvScreen`: for TVs that can't open a server (Samsung), this phone serves its lists on the local
/// network for 10 minutes; the TV finds it and asks for them with the 4-digit code shown here.
struct SendToTvView: View {
    @Environment(AppModel.self) private var app
    @State private var state: SendToTvState = SendToTvStateIdle.shared
    @State private var selected: Set<Int64> = []
    @State private var now = Date()
    @State private var sub: FlowSubscription?
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Invia una lista alla TV", onBack: { app.back() })
            if app.sources.isEmpty {
                EmptyState(symbol: "exclamationmark.circle", text: "Aggiungi prima una lista su questo telefono.")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("1. Sul televisore apri StePlayer e scegli Aggiungi lista → Usa il telefono").font(AppFont.bodyLarge)
                        Spacer().frame(height: 10)
                        Text("2. Tieni il telefono sulla stessa rete Wi-Fi del televisore e lascia aperta questa schermata").font(AppFont.bodyLarge)
                        Spacer().frame(height: 10)
                        Text("3. Inserisci sul televisore questo codice:").font(AppFont.bodyLarge)
                        Spacer().frame(height: 20)
                        status
                        Spacer().frame(height: 28)
                        Text("Liste da inviare").font(AppFont.titleMedium).foregroundStyle(Theme.textSecondary)
                        Spacer().frame(height: 8)
                        VStack(spacing: 0) {
                            ForEach(app.sources, id: \.id) { s in listRow(s) }
                        }
                        .padding(.vertical, 6)
                        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        Spacer().frame(height: 16)
                        Text("I dati passano solo nella tua rete di casa, direttamente dal telefono al televisore. Nessun server esterno.")
                            .font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
                    }
                    .foregroundStyle(Theme.textPrimary)
                    .padding(.horizontal, 20).padding(.vertical, 8)
                    .frame(maxWidth: 760).frame(maxWidth: .infinity)
                }
            }
        }
        .onAppear(perform: begin)
        .onDisappear { sub = nil; app.core.sendToTv.stop(keepState: false) }
        .onReceive(ticker) { now = $0 }
    }

    @ViewBuilder private var status: some View {
        if let w = state as? SendToTvStateWaiting {
            let left = max(0, w.expiresAtMs - Int64(now.timeIntervalSince1970 * 1000))
            codeBoxes(w.code)
            Spacer().frame(height: 16)
            if left <= 0 {
                Text("Il codice è scaduto").foregroundStyle(Theme.textSecondary)
                retryButton
            } else {
                HStack(spacing: 10) {
                    ProgressView().tint(Theme.primary).controlSize(.small)
                    Text("In attesa del televisore…").foregroundStyle(Theme.textSecondary)
                }
                Spacer().frame(height: 6)
                Text("Il codice scade tra \(AFormat.duration(ms: left))").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
                Spacer().frame(height: 6)
                Text("Se il televisore non trova il telefono, inserisci questo indirizzo: \(w.address)")
                    .font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
            }
        } else if state is SendToTvStateSent {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 26)).foregroundStyle(Theme.success)
                Text("Fatto! Continua sul televisore.").font(AppFont.titleMedium)
            }
        } else if let f = state as? SendToTvStateFailed {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.circle").font(.system(size: 22)).foregroundStyle(Theme.live)
                Text(f.message).font(AppFont.bodyMedium)
            }
            retryButton
        } else {
            ProgressView().tint(Theme.primary)
        }
    }

    private var retryButton: some View {
        PrimaryButton(text: "Nuovo codice", action: begin).padding(.top, 16)
    }

    private func codeBoxes(_ code: String) -> some View {
        HStack(spacing: 12) {
            ForEach(Array(code.enumerated()), id: \.offset) { _, d in
                Text(String(d)).font(AppFont.displaySmall).foregroundStyle(Theme.accent2)
                    .frame(width: 64, height: 80)
                    .background(Theme.surfaceElevated, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func listRow(_ s: Source) -> some View {
        let checked = selected.contains(s.id)
        return Button {
            if checked { selected.remove(s.id) } else { selected.insert(s.id) }
            app.core.sendToTv.select(sourceIds: selected.map { KotlinLong(value: $0) })
        } label: {
            HStack(spacing: 14) {
                Image(systemName: checked ? "checkmark.square.fill" : "square").font(.system(size: 22))
                    .foregroundStyle(checked ? Theme.primary : Theme.textSecondary)
                Text(s.name).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func begin() {
        let core = app.core
        if selected.isEmpty, let first = app.activeId >= 0 ? app.activeId : app.sources.first?.id { selected = [first] }
        sub = observe(core.sendToTv.state) { s in state = s }
        let ids = selected.map { KotlinLong(value: $0) }
        Task { _ = try? await core.sendToTv.start(sourceIds: ids, device: UIDevice.current.model) }
    }
}
#endif
