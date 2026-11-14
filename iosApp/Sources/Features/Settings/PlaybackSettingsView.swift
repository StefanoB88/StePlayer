import SwiftUI
import Shared

/// Player preferences (backed by core.playerPreferences).
@MainActor
@Observable
final class PlayerSettingsModel {
    var settings: PlayerSettings?
    @ObservationIgnored private var sub: FlowSubscription?
    @ObservationIgnored private var core: StePlayerCore?

    func bind(_ core: StePlayerCore) {
        guard self.core == nil else { return }
        self.core = core
        sub = observe(core.playerPreferences.settings) { [weak self] (s: PlayerSettings) in self?.settings = s }
    }

    func update(_ change: (inout Draft) -> Void) {
        guard let s = settings, let core else { return }
        var d = Draft(s)
        change(&d)
        let updated = s.doCopy(stillWatchingAfterHours: d.hours, bufferMode: d.buffer, preferredAudioLanguages: d.audioLanguages,
                               preferredSubtitleLanguage: d.subtitleLanguage, subtitleMode: d.subtitleMode,
                               subtitleStyle: s.subtitleStyle.doCopy(scale: d.scale, color: d.color, background: d.background,
                                                                     edge: d.edge, bottomMarginPercent: s.subtitleStyle.bottomMarginPercent),
                               showStatsOverlay: d.stats)
        settings = updated
        Task { try? await core.playerPreferences.save(settings: updated) }
    }

    struct Draft {
        var hours: Int32, buffer: BufferMode, audioLanguages: [String], subtitleLanguage: String?
        var subtitleMode: SubtitleMode, scale: Float, color: SubtitleColor, background: SubtitleBackground, edge: SubtitleEdge, stats: Bool
        init(_ s: PlayerSettings) {
            hours = s.stillWatchingAfterHours; buffer = s.bufferMode; audioLanguages = s.preferredAudioLanguages
            subtitleLanguage = s.preferredSubtitleLanguage; subtitleMode = s.subtitleMode; scale = s.subtitleStyle.scale
            color = s.subtitleStyle.color; background = s.subtitleStyle.background; edge = s.subtitleStyle.edge; stats = s.showStatsOverlay
        }
    }
}

/// Languages offered for audio/subtitle preferences (same list as Android). nil = follow the device.
enum Languages {
    static let choices: [(code: String?, name: String)] = [
        (nil, "Lingua del dispositivo"), ("it", "Italiano"), ("en", "English"), ("es", "Español"), ("fr", "Français"),
        ("de", "Deutsch"), ("pt", "Português"), ("ro", "Română"), ("ar", "العربية"),
    ]
}

enum BufferModeLabel {
    static func title(_ m: BufferMode) -> String {
        switch m {
        case .normal: return "Normale"
        case .large: return "Grande (predefinito)"
        default: return "Automatico"
        }
    }
}

/// Android `SubtitleSettingsScreen`.
struct SubtitleSettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var model = PlayerSettingsModel()

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Sottotitoli", onBack: { app.back() })
            ScrollView {
                if let s = model.settings {
                    VStack(spacing: 14) {
                        SubtitlePreview(style: s.subtitleStyle)
                        card {
                            ChoiceRow(title: "Modalità",
                                      options: [(SubtitleMode.off, "Disattivati"), (SubtitleMode.auto_, "Automatici (se l'audio è in un'altra lingua)"), (SubtitleMode.always, "Sempre")],
                                      selected: s.subtitleMode) { v in model.update { $0.subtitleMode = v } }
                            ChoiceRow(title: "Lingua", options: Languages.choices.map { ($0.code, $0.name) },
                                      selected: s.preferredSubtitleLanguage) { v in model.update { $0.subtitleLanguage = v } }
                        }
                        card {
                            ChoiceRow(title: "Dimensione",
                                      options: [(Float(0.75), "75%"), (Float(1.0), "100%"), (Float(1.25), "125%"), (Float(1.5), "150%"), (Float(2.0), "200%")],
                                      selected: s.subtitleStyle.scale) { v in model.update { $0.scale = v } }
                            ChoiceRow(title: "Colore", options: [(SubtitleColor.white, "Bianco"), (SubtitleColor.yellow, "Giallo")],
                                      selected: s.subtitleStyle.color) { v in model.update { $0.color = v } }
                            ChoiceRow(title: "Sfondo",
                                      options: [(SubtitleBackground.none, "Nessuno"), (SubtitleBackground.semiTransparent, "Semitrasparente"), (SubtitleBackground.opaque, "Pieno")],
                                      selected: s.subtitleStyle.background) { v in model.update { $0.background = v } }
                            ChoiceRow(title: "Bordo",
                                      options: [(SubtitleEdge.none, "Nessuno"), (SubtitleEdge.outline, "Contorno"), (SubtitleEdge.shadow, "Ombra")],
                                      selected: s.subtitleStyle.edge) { v in model.update { $0.edge = v } }
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: 760)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .onAppear { model.bind(app.core) }
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// Live preview over a neutral gradient "frame" with fictional text.
struct SubtitlePreview: View {
    let style: SubtitleStyle

    var body: some View {
        AspectBox(ratio: 16.0 / 9.0) {
            ZStack(alignment: .bottom) {
                LinearGradient(colors: [Color(hex: 0x2B3A55), Color(hex: 0x5B4B8A), Color(hex: 0x1F6F78)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Text("Questo è un esempio di sottotitolo")
                    .font(.system(size: 18 * CGFloat(style.scale) * (Theme.isTV ? 1.6 : 1)))
                    .foregroundStyle(style.color == SubtitleColor.yellow ? Color(hex: 0xFFEB3B) : .white)
                    .multilineTextAlignment(.center)
                    .shadow(color: .black, radius: style.edge == SubtitleEdge.shadow ? 3 : (style.edge == SubtitleEdge.outline ? 1.5 : 0),
                            x: style.edge == SubtitleEdge.shadow ? 1.5 : 0, y: style.edge == SubtitleEdge.shadow ? 1.5 : 0)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(backgroundColor)
                    .padding(.bottom, CGFloat(style.bottomMarginPercent) * 2)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var backgroundColor: Color {
        switch style.background {
        case SubtitleBackground.opaque: return .black
        case SubtitleBackground.semiTransparent: return .black.opacity(0.6)
        default: return .clear
        }
    }
}
