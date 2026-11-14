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
        .animation(.easeInOut(duration: 0.2), value: model.confirm?.id)
        .animation(.easeInOut(duration: 0.25), value: model.phase)
        .animation(.easeInOut(duration: 0.25), value: model.toast)
        .fullScreenCover(item: $model.playing) { item in
            PlayerScreen(item: item)
                .environment(model)
        }
    }
}
