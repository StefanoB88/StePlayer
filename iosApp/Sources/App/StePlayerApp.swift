import SwiftUI
import AVFoundation

@main
struct StePlayerApp: App {
    #if os(iOS)
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    #endif
    @State private var model = AppModel()

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(Theme.primary)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        ZStack {
            Theme.background.ignoresSafeArea()
            switch model.phase {
            case .launching:
                ProgressView().tint(Theme.primary)
            case .onboarding:
                OnboardingFlow()
                    .transition(.opacity)
            case .syncing:
                SyncProgressView()
                    .transition(.opacity)
            case .ready:
                MainShellRoot()
                    .transition(.opacity)
            }
        }
        // The user-initiated sync popup blocks everything behind it: not tappable, not focusable (tvOS), hidden from VoiceOver.
        .disabled(model.syncPopup != nil)
        .accessibilityHidden(model.syncPopup != nil)
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                // Android Toast / Snackbar.
                Text(toast)
                    .font(AppFont.bodyMedium)
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(Theme.surfaceElevated, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .shadow(color: .black.opacity(0.4), radius: 12, y: 4)
                    .padding(.horizontal, 24)
                    .padding(.bottom, Theme.isTV ? 60 : 84)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .allowsHitTesting(false)
            }
        }
        .overlay {
            if let c = model.confirm {
                DialogHost(onDismiss: { model.confirm = nil }) {
                    DialogCard(title: c.title) {
                        Text(c.message).font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    } buttons: {
                        TextAction(title: "Annulla", color: Theme.primary) { model.confirm = nil }
                        TextAction(title: c.confirmTitle, color: Theme.live) {
                            model.confirm = nil
                            c.action()
                        }
                    }
                }
            }
        }
        .overlay {
            if let state = model.syncPopup {
                SyncProgressOverlay(state: state)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.confirm?.id)
        .animation(.easeInOut(duration: 0.2), value: model.syncPopup)
        .animation(.easeInOut(duration: 0.25), value: model.phase)
        .animation(.easeInOut(duration: 0.25), value: model.toast)
        .fullScreenCover(item: $model.playing) { item in
            PlayerScreen(item: item)
                .environment(model)
        }
    }
}

/// Blocking "Aggiornamento della lista" popup for user-initiated syncs ("Aggiorna", "Sincronizza ora").
/// Covers the whole app (RootView disables everything behind it) and can't be dismissed while running:
/// no tap outside, nothing to swipe, the tvOS Menu button is swallowed. On failure it shows the
/// specific message with "Riprova" (same popup) and "Chiudi".
struct SyncProgressOverlay: View {
    @Environment(AppModel.self) private var model
    let state: SyncPopup

    private enum Focus: Hashable { case card, retry, close }
    @FocusState private var focus: Focus?

    private var failedMessage: String? {
        if case let .failed(message) = state { return message }
        return nil
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.6)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {} // swallows taps: nothing behind is reachable
            card
        }
        .transition(.opacity)
        .accessibilityAddTraits(.isModal)
        #if os(tvOS)
        // Menu: ignored while the sync runs; once it failed it works as "Chiudi".
        .onExitCommand {
            if failedMessage != nil { model.dismissSyncPopup() }
        }
        .defaultFocus($focus, failedMessage == nil ? .card : .retry)
        #endif
        .onAppear { moveFocus() }
        .onChange(of: state) { _, _ in moveFocus() }
    }

    private var card: some View {
        VStack(spacing: 0) {
            if let message = failedMessage {
                failed(message)
            } else {
                running
            }
        }
        .padding(Theme.isTV ? 40 : 28)
        .frame(maxWidth: Theme.isTV ? 680 : 400)
        .background(Theme.surfaceElevated, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: .black.opacity(0.45), radius: 24, y: 8)
        .padding(24)
        #if os(tvOS)
        // While running there is no button: the card itself holds focus so the Menu press lands here.
        .focusable(failedMessage == nil)
        .focused($focus, equals: .card)
        .focusEffectDisabled()
        #endif
    }

    private var running: some View {
        let progress = min(1, max(0, model.syncProgress.isFinite ? model.syncProgress : 0))
        let percent = Int((progress * 100).rounded())
        return VStack(spacing: 0) {
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: Theme.isTV ? 34 : 28, weight: .semibold))
                .foregroundStyle(Theme.onAccent)
                .frame(width: Theme.isTV ? 76 : 64, height: Theme.isTV ? 76 : 64)
                .background(Theme.accentGradient, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            Spacer().frame(height: 20)
            Text("Aggiornamento della lista")
                .font(AppFont.headlineSmall).foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 8)
            Text(model.syncStepText.isEmpty ? "Accesso al server…" : model.syncStepText)
                .font(AppFont.bodyMedium).foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .contentTransition(.opacity)
                .animation(.easeInOut(duration: 0.25), value: model.syncStepText)
            Spacer().frame(height: 24)
            HStack(spacing: 12) {
                ProgressBar(progress: max(0.02, progress), height: 6)
                    .animation(.easeInOut(duration: 0.45), value: progress)
                Text("\(percent)%")
                    .font(AppFont.labelLarge.monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                    .frame(minWidth: Theme.isTV ? 64 : 44, alignment: .trailing)
                    .contentTransition(.numericText(value: Double(percent)))
                    .animation(.easeInOut(duration: 0.45), value: percent)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Aggiornamento della lista, \(model.syncStepText), \(percent)%")
    }

    private func failed(_ message: String) -> some View {
        VStack(spacing: 0) {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: Theme.isTV ? 58 : 50))
                .foregroundStyle(Theme.live)
            Spacer().frame(height: 16)
            Text("Aggiornamento non riuscito")
                .font(AppFont.headlineSmall).foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 10)
            // The core's message is already a complete sentence (offline, wrong address, credentials…): shown in full.
            Text(message)
                .font(AppFont.bodyLarge).foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 24)
            HStack(spacing: 12) {
                SecondaryButton(text: "Chiudi", fullWidth: true) { model.dismissSyncPopup() }
                    .focused($focus, equals: .close)
                PrimaryButton(text: "Riprova", icon: "arrow.clockwise", fullWidth: true) { model.retryPopupSync() }
                    .focused($focus, equals: .retry)
            }
            #if os(tvOS)
            .focusSection()
            #endif
        }
        .frame(maxWidth: .infinity)
    }

    private func moveFocus() {
        #if os(tvOS)
        let target: Focus = failedMessage == nil ? .card : .retry
        // Next run loop: the target must be in the hierarchy (and the views behind already disabled).
        DispatchQueue.main.async { focus = target }
        #endif
    }
}
