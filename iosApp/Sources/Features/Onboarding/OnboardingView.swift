import SwiftUI

enum Legal {
    static let disclaimer = "StePlayer è solo un lettore: non fornisce né vende contenuti. Usa solo liste che hai il diritto di guardare."
    /// First-launch legal gate (3 points, same text on Android, iOS and Samsung TV).
    static let gate = "1. StePlayer è esclusivamente un lettore multimediale neutrale: non fornisce, non vende e non include alcun contenuto audiovisivo o abbonamento.\n\n2. Devi disporre dei diritti per riprodurre qualsiasi flusso o lista che inserisci nell'app.\n\n3. Lo sviluppatore declina ogni responsabilità per l'uso dell'app con contenuti protetti dal diritto d'autore senza autorizzazione."
    static let acceptance = "Confermo che userò StePlayer solo con contenuti che ho il diritto di guardare"
    /// Android `privacy_note`, adapted: iOS keeps passwords in the Keychain (Portachiavi).
    static let privacy = "Tutti i dati restano sul tuo dispositivo. Le password sono cifrate nel Portachiavi di iOS. Nessun account, nessun tracciamento."
    static let privacySettings = "StePlayer non raccoglie alcun dato. Liste, preferiti, cronologia e download restano solo su questo dispositivo."
    static let affiliation = "StePlayer non è affiliato con alcun provider e non fornisce contenuti, liste o abbonamenti."
    static let openSource = "StePlayer usa VLCKit e libVLC di VideoLAN (MobileVLCKit 3.7.5, TVVLCKit 3.0, con i decoder FFmpeg inclusi), distribuiti con licenza GNU LGPL 2.1. Codice sorgente: https://code.videolan.org/videolan/VLCKit"
}

/// Welcome → add list (Android `OnboardingScreen` + `AddSourceScreen`).
struct OnboardingFlow: View {
    @State private var path: [Int] = DemoHooks.onboardingAdd ? [1] : []

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView { path = [1] }
                .hideSystemNavigationBar()
                .navigationDestination(for: Int.self) { _ in
                    AddSourceView(mode: .onboarding, onBack: { path = [] })
                        .hideSystemNavigationBar()
                        .screenBackground()
                }
        }
    }
}

struct WelcomeView: View {
    let onContinue: () -> Void
    @AppStorage("legalAccepted") private var legalAccepted = false
    @State private var confirmed = false
    /// Landscape (iPad, iPhone): a wider column wraps the legal text in fewer lines, so "Ho capito e accetto"
    /// stays on the first screen instead of under the home indicator (iPad mini landscape is 744 pt tall).
    @State private var landscape = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                BrandMark(size: 88)
                Spacer().frame(height: 28)
                Text("Benvenuto in StePlayer").font(AppFont.displaySmall).foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                Spacer().frame(height: 12)
                Text("Lettore multimediale per le tue liste. Veloce, pulito e privato.")
                    .font(AppFont.bodyLarge).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                Spacer().frame(height: 32)
                InfoCard(symbol: "building.columns.fill", text: Legal.gate)
                Spacer().frame(height: 12)
                InfoCard(symbol: "lock.fill", text: Legal.privacy)
                Spacer().frame(height: 20)
                Button { confirmed.toggle() } label: {
                    HStack(spacing: 10) {
                        Image(systemName: confirmed ? "checkmark.square.fill" : "square")
                            .font(.system(size: 22))
                            .foregroundStyle(confirmed ? Theme.primary : Theme.textSecondary)
                        Text(Legal.acceptance).font(AppFont.bodyMedium).foregroundStyle(Theme.textPrimary)
                            .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 8).padding(.horizontal, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(radius: 14, scale: 1.02))
                .accessibilityAddTraits(confirmed ? .isSelected : [])
                Spacer().frame(height: 20)
                PrimaryButton(text: "Ho capito e accetto", enabled: confirmed, fullWidth: true) {
                    legalAccepted = true
                    onContinue()
                }
            }
            .padding(28)
            .frame(maxWidth: landscape && !Theme.isTV ? 760 : 560)
            .frame(maxWidth: .infinity)
        }
        .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height } action: { landscape = $0 }
        // Soft accent glow (decorative). A background, so its 420 pt frame never widens the page: as a ZStack sibling
        // it made the scroll view 420 pt wide and cut the text and cards on 375–390 pt phones.
        .background(alignment: .top) {
            RadialGradient(colors: [Theme.primary.opacity(0.35), .clear], center: .center, startRadius: 0, endRadius: 210)
                .frame(width: 420, height: 420)
                .allowsHitTesting(false)
        }
        .background(Theme.background.ignoresSafeArea())
        .onAppear { confirmed = legalAccepted }
    }
}

struct InfoCard: View {
    let symbol: String
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).font(.system(size: 19)).foregroundStyle(Theme.accent2).frame(width: 22)
            Text(text).font(AppFont.bodyMedium).foregroundStyle(Theme.textPrimary).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.outline, lineWidth: 1))
    }
}

/// Android `SyncScreen`.
struct SyncProgressView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            if let error = model.syncError {
                Image(systemName: "exclamationmark.circle").font(.system(size: 50)).foregroundStyle(Theme.live)
                Spacer().frame(height: 16)
                Text("Sincronizzazione non riuscita").font(AppFont.headlineSmall).foregroundStyle(Theme.textPrimary)
                Spacer().frame(height: 8)
                Text(error).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                Spacer().frame(height: 24)
                PrimaryButton(text: "Riprova", fullWidth: true) {
                    Task { await model.syncNow(model.activeId, blocking: true) }
                }
                Spacer().frame(height: 10)
                SecondaryButton(text: "Continua comunque", fullWidth: true) {
                    model.syncError = nil
                    model.phase = model.sources.isEmpty ? .onboarding : .ready
                }
            } else {
                Image(systemName: "arrow.triangle.2.circlepath.icloud")
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(Theme.onAccent)
                    .frame(width: 80, height: 80)
                    .background(Theme.accentGradient, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                Spacer().frame(height: 24)
                Text("Preparazione della libreria").font(AppFont.headlineSmall).foregroundStyle(Theme.textPrimary).multilineTextAlignment(.center)
                Spacer().frame(height: 8)
                Text(model.syncStepText.isEmpty ? "Accesso al server…" : model.syncStepText)
                    .foregroundStyle(Theme.textSecondary)
                    .contentTransition(.opacity)
                Spacer().frame(height: 24)
                ProgressBar(progress: max(0.02, model.syncProgress), height: 6)
                    .animation(.easeInOut, value: model.syncProgress)
            }
        }
        .padding(32)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .screenBackground()
    }
}
