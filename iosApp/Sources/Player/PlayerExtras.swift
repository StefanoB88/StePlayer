import SwiftUI
import Shared

/// Android player dialogs (`Sheet` enum in PlayerUi.kt).
enum PlayerSheet: Equatable {
    case audio, text, aspect, speed, menu, sleep, audioSync, subSync
}

/// Sleep timer choices shared by the iOS dialog and the tvOS transport-bar menu.
enum SleepChoice: CaseIterable, Identifiable {
    case off, m15, m30, m60, m90, programEnd
    var id: Self { self }
    var title: String {
        switch self {
        case .off: return "Disattivato"
        case .m15: return "15 min"
        case .m30: return "30 min"
        case .m60: return "60 min"
        case .m90: return "90 min"
        case .programEnd: return "Fine programma"
        }
    }
    @MainActor func apply(to vm: PlayerViewModel) {
        switch self {
        case .off: vm.setSleepTimer(minutes: nil)
        case .m15: vm.setSleepTimer(minutes: 15)
        case .m30: vm.setSleepTimer(minutes: 30)
        case .m60: vm.setSleepTimer(minutes: 60)
        case .m90: vm.setSleepTimer(minutes: 90)
        case .programEnd: vm.sleepAtProgramEnd()
        }
    }
}

/// Aspect modes: Adatta / Riempi / Zoom (Android RESIZE_MODE_FIT / FILL / ZOOM).
enum AspectMode: Int, CaseIterable {
    case fit, fill, zoom
    var title: String { ["Adatta", "Riempi", "Zoom"][rawValue] }
}

/// Options dialog with radio rows (Android `OptionsDialog`).
struct OptionsDialog: View {
    let title: String
    let options: [(String, Bool)]
    let onSelect: (Int) -> Void
    let onDismiss: () -> Void

    var body: some View {
        DialogCard(title: title) {
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(options.indices, id: \.self) { i in
                        RadioRow(label: options[i].0, selected: options[i].1) {
                            onSelect(i)
                            onDismiss()
                        }
                    }
                }
            }
            .frame(maxHeight: 320)
            .fixedSize(horizontal: false, vertical: true)
        } buttons: {
            TextAction(title: "Annulla", action: onDismiss)
        }
    }
}

/// Plain list dialog (Android `MenuDialog`).
struct MenuDialog: View {
    let title: String
    let items: [String]
    let onSelect: (Int) -> Void
    let onDismiss: () -> Void

    var body: some View {
        DialogCard(title: title) {
            VStack(spacing: 0) {
                ForEach(items.indices, id: \.self) { i in
                    Button { onSelect(i) } label: {
                        Text(items[i]).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 12).padding(.horizontal, 8)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle(radius: 10, scale: 1.01))
                }
            }
        } buttons: {
            TextAction(title: "Annulla", action: onDismiss)
        }
    }
}

/// −/+ stepper (Android `DelayDialog`).
struct DelayDialog: View {
    let title: String
    let valueMs: Int64
    let step: Int64
    let onChange: (Int64) -> Void
    let onDismiss: () -> Void

    var body: some View {
        DialogCard(title: title) {
            HStack(spacing: 0) {
                RoundIconButton(symbol: "minus", label: "Meno", size: 52, background: Theme.surface) { onChange(valueMs - step) }
                Text(String(format: step < 100 ? "%+.2f s" : "%+.1f s", Double(valueMs) / 1000).replacingOccurrences(of: ".", with: ","))
                    .font(AppFont.headlineMedium).monospacedDigit().foregroundStyle(Theme.textPrimary)
                    .frame(minWidth: 120)
                    .padding(.horizontal, 24)
                RoundIconButton(symbol: "plus", label: "Più", size: 52, background: Theme.surface) { onChange(valueMs + step) }
            }
            .frame(maxWidth: .infinity)
        } buttons: {
            TextAction(title: "Azzera") { onChange(0) }
            TextAction(title: "OK", action: onDismiss)
        }
    }
}

struct StatsOverlay: View {
    let lines: [String]
    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            ForEach(lines, id: \.self) { Text($0) }
        }
        .font(.system(size: Theme.isTV ? 22 : 12, design: .monospaced))
        .foregroundStyle(.white)
        .padding(10)
        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
        .allowsHitTesting(false)
    }
}

struct ErrorOverlay: View {
    let title: String
    let message: String
    let onRetry: () -> Void
    let onBack: () -> Void
    let onDiagnose: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.8).ignoresSafeArea()
            VStack(spacing: 0) {
                Image(systemName: "wifi.slash").font(.system(size: 46)).foregroundStyle(Theme.live)
                Spacer().frame(height: 14)
                Text(title).font(AppFont.titleLarge).foregroundStyle(.white).multilineTextAlignment(.center)
                Spacer().frame(height: 6)
                Text(message).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer().frame(height: 22)
                HStack(spacing: 10) {
                    PrimaryButton(text: "Riprova", action: onRetry)
                    SecondaryButton(text: "Indietro", action: onBack)
                }
                Spacer().frame(height: 10)
                SecondaryButton(text: "Perché si blocca?", action: onDiagnose)
            }
            .padding(32)
            .frame(maxWidth: Theme.isTV ? 900 : 460)
        }
    }
}

struct RetryOverlay: View {
    let seconds: Int
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(spacing: 0) {
                ProgressView().controlSize(.large).tint(Theme.primary)
                Spacer().frame(height: 16)
                Text("Riconnessione…").font(AppFont.titleMedium).foregroundStyle(.white)
                Spacer().frame(height: 10)
                Text("Riprovo tra \(seconds) s…").font(AppFont.labelLarge).foregroundStyle(Theme.cyan).monospacedDigit()
                Spacer().frame(height: 18)
                SecondaryButton(text: "Annulla", action: onCancel)
            }
            .padding(32)
        }
    }
}

struct NextEpisodePrompt: View {
    let episode: Episode
    let countdown: Int
    let onPlay: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Prossimo episodio tra \(countdown) s").font(AppFont.labelLarge).foregroundStyle(Theme.cyan).monospacedDigit()
            Spacer().frame(height: 4)
            Text("S\(episode.season) E\(episode.number) · \(episode.title)").font(AppFont.titleMedium).foregroundStyle(Theme.textPrimary).lineLimit(2)
            Spacer().frame(height: 12)
            HStack(spacing: 8) {
                PrimaryButton(text: "Riproduci ora", icon: "forward.end.fill", action: onPlay)
                SecondaryButton(text: "Annulla", action: onCancel)
            }
        }
        .padding(16)
        .frame(width: Theme.isTV ? 560 : 340, alignment: .leading)
        .background(Theme.surface.opacity(0.95), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(28)
    }
}

struct SleepWarningPill: View {
    let seconds: Int
    let onExtend: () -> Void
    let onCancel: () -> Void
    var body: some View {
        HStack(spacing: 10) {
            Text("Spegnimento tra \(seconds) s").font(AppFont.titleSmall).foregroundStyle(Theme.textPrimary).monospacedDigit()
            SecondaryButton(text: "+15 min", action: onExtend)
            SecondaryButton(text: "Annulla", action: onCancel)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Theme.surface.opacity(0.95), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(24)
    }
}

/// Short notice over the video ("Buffer aumentato per ridurre i blocchi", delay notices).
struct PlayerNotice: View {
    let text: String
    var body: some View {
        Text(text)
            .font(AppFont.bodyMedium).foregroundStyle(Theme.textPrimary)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(Theme.surfaceElevated.opacity(0.95), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .allowsHitTesting(false)
    }
}

/// "Stai ancora guardando?" (Android `StillWatchingDialog`).
struct StillWatchingDialog: View {
    let seconds: Int
    let onContinue: () -> Void
    let onExit: () -> Void
    var body: some View {
        DialogHost(onDismiss: onContinue) {
            DialogCard(title: "Stai ancora guardando?") {
                Text("La riproduzione si fermerà tra \(seconds) s per non tenere occupata la connessione.")
                    .font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary).monospacedDigit()
                    .fixedSize(horizontal: false, vertical: true)
            } buttons: {
                SecondaryButton(text: "Esci", action: onExit)
                PrimaryButton(text: "Continua a guardare", action: onContinue)
            }
        }
    }
}

/// Android `BusyDialog`.
struct BusyDialog: View {
    let busy: AcquireResultDenied
    let onPauseDownloads: () -> Void
    let onCancel: () -> Void
    var body: some View {
        DialogHost(onDismiss: onCancel) {
            DialogCard(title: "Connessione occupata") {
                Text(busy.message).font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            } buttons: {
                TextAction(title: "Annulla", action: onCancel)
                if busy.canPauseDownloads {
                    TextAction(title: "Sospendi i download e guarda", action: onPauseDownloads)
                }
            }
        }
    }
}
