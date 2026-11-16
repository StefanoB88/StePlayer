import Combine
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import Shared

/// Route wrapper: "Configura la TV col telefono" from Settings.
struct PairingScreen: View {
    @Environment(AppModel.self) private var app
    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Aggiungi lista", onBack: { app.back() }, large: true)
            ScrollView { PairingPanel().padding(20).frame(maxWidth: 900).frame(maxWidth: .infinity) }
        }
    }
}

/// Android `PairingPanel`: the device runs a tiny LAN-only form server (shared core); the phone scans the QR,
/// fills in the list and sends it. Nothing goes through the internet or third parties.
struct PairingPanel: View {
    @Environment(AppModel.self) private var app
    @State private var waiting: PairingStateWaiting?
    @State private var verifying = false
    @State private var failure: String?
    @State private var offline = false
    @State private var now = Date()
    @State private var sub: FlowSubscription?

    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 16) {
            Text("Configura la TV col telefono").font(AppFont.headlineSmall).foregroundStyle(Theme.textPrimary).multilineTextAlignment(.center)
            if verifying {
                ProgressView().tint(Theme.primary)
                Text("Verifica in corso…").foregroundStyle(Theme.textSecondary)
            } else if let failure {
                Image(systemName: "exclamationmark.circle").font(.system(size: 40)).foregroundStyle(Theme.live)
                Text(failure).foregroundStyle(Theme.textPrimary).multilineTextAlignment(.center)
                PrimaryButton(text: "Genera nuovo codice", action: begin)
            } else if let w = waiting {
                let left = max(0, w.expiresAtMs - Int64(now.timeIntervalSince1970 * 1000))
                if left <= 0 {
                    Text("Il codice è scaduto").foregroundStyle(Theme.textSecondary)
                    PrimaryButton(text: "Genera nuovo codice", action: begin)
                } else {
                    ViewThatFits(in: .horizontal) {
                        HStack(alignment: .center, spacing: 32) { qr(w.url); steps(w.url, left: left) }
                        VStack(spacing: 24) { qr(w.url); steps(w.url, left: left) }
                    }
                }
            } else if offline {
                Image(systemName: "wifi.slash").font(.system(size: 40)).foregroundStyle(Theme.textSecondary)
                Text("Nessuna rete locale disponibile. Collega il dispositivo al Wi-Fi o usa il telecomando.")
                    .foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                PrimaryButton(text: "Genera nuovo codice", action: begin)
            } else {
                ProgressView().tint(Theme.primary)
            }
        }
        .frame(maxWidth: .infinity)
        .onAppear(perform: begin)
        .onDisappear {
            sub = nil
            app.core.pairing.stop(keepState: false)
        }
        .onReceive(ticker) { now = $0 }
    }

    private func qr(_ url: String) -> some View {
        Group {
            if let image = QRCode.image(for: url) {
                Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
            }
        }
        .padding(8)
        .frame(width: Theme.isTV ? 400 : 260, height: Theme.isTV ? 400 : 260)
        .background(Theme.background, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func steps(_ url: String, left: Int64) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("1. Collega il telefono alla stessa rete Wi-Fi").font(AppFont.bodyLarge)
            Text("2. Inquadra il codice con la fotocamera").font(AppFont.bodyLarge)
            Text("3. Inserisci i dati e premi Invia").font(AppFont.bodyLarge)
            Spacer().frame(height: 12)
            Text(url).font(AppFont.titleMedium).foregroundStyle(Theme.accent2)
            Text("Il codice scade tra \(AFormat.duration(ms: left))").font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
        }
        .foregroundStyle(Theme.textPrimary)
        .frame(maxWidth: 360, alignment: .leading)
    }

    private func begin() {
        failure = nil
        verifying = false
        offline = false
        waiting = nil
        let core = app.core
        sub = observe(core.pairing.state) { state in
            if let w = state as? PairingStateWaiting {
                waiting = w
                verifying = false
            } else if state is PairingStateVerifying {
                verifying = true
            } else if let added = state as? PairingStateAdded {
                verifying = false
                app.path = []
                app.pairingCompleted(added.sourceId)
            } else if let failed = state as? PairingStateFailed {
                verifying = false
                failure = failed.message
            }
        }
        Task {
            let result = try? await core.pairing.start(preferredPort: 8765)
            if result == nil && failure == nil { offline = true }
        }
    }
}

enum QRCode {
    /// White modules on the app background, rendered at native module size (scale with `.interpolation(.none)`).
    /// The panel re-renders every second (expiry countdown): generate the QR once per URL.
    @MainActor private static var cache: (String, UIImage)?

    @MainActor
    static func image(for string: String) -> UIImage? {
        if let cache, cache.0 == string { return cache.1 }
        let image = render(string)
        if let image { cache = (string, image) }
        return image
    }

    private static func render(_ string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let qr = filter.outputImage else { return nil }
        let colored = qr.applyingFilter("CIFalseColor", parameters: [
            "inputColor0": CIColor(red: 1, green: 1, blue: 1),
            "inputColor1": CIColor(red: 0x0B / 255, green: 0x0D / 255, blue: 0x12 / 255),
        ])
        let scaled = colored.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
