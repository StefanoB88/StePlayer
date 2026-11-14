import SwiftUI
import Shared

/// Android `SettingsScreen`.
struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var player = PlayerSettingsModel()
    @State private var epgBusy = false

    private var offerPhone: Bool { Theme.isTV || sizeClass == .regular }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Impostazioni", onBack: { app.back() }, large: true)
            ScrollView {
                VStack(spacing: 16) {
                    sourceLists
                    if let active = app.active { activeSection(active) }
                    playback
                    SettingsSection(title: "Avvisi di scadenza") {
                        SwitchRow(symbol: "bell.badge.fill", title: "Avvisi di scadenza",
                                  subtitle: Theme.isTV
                                    ? "Ti avvisiamo nella Home 3 giorni e 1 giorno prima della scadenza comunicata dal provider."
                                    : "Ti avvisiamo 3 giorni e 1 giorno prima della scadenza comunicata dal provider.",
                                  isOn: app.remindersEnabled) { v in Task { await app.setRemindersEnabled(v) } }
                    }
                    SettingsSection(title: "Note legali") {
                        SettingRow(symbol: "building.columns.fill", title: Legal.disclaimer)
                        SettingRow(symbol: "lock.fill", title: "Privacy", subtitle: Legal.privacySettings)
                        SettingRow(symbol: "info.circle.fill", title: Legal.affiliation)
                        SettingRow(symbol: "doc.text.fill", title: "Licenze open source", subtitle: Legal.openSource)
                    }
                    SettingsSection(title: "Informazioni") {
                        SettingRow(symbol: "info.circle.fill", title: "StePlayer",
                                   subtitle: "Versione \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")")
                    }
                }
                .padding(16)
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
        }
        .onAppear { player.bind(app.core) }
    }

    private var sourceLists: some View {
        SettingsSection(title: "Liste") {
            ForEach(app.sources, id: \.id) { s in
                SourceRow(source: s, isActive: s.id == app.activeId,
                          onUse: { Task { await app.activate(s.id) } },
                          onEdit: { app.open(.editSource(id: s.id)) },
                          onDelete: {
                              app.confirm = ConfirmDialog(
                                title: "Elimina",
                                message: "Eliminare la lista “\(s.name)”? Preferiti, progressi e download di questa lista verranno rimossi.",
                                confirmTitle: "Elimina") { Task { await app.deleteSource(s.id) } }
                          })
            }
            SettingRow(symbol: "plus", title: "Aggiungi lista") { app.open(.addSource) }
            if offerPhone {
                SettingRow(symbol: "qrcode", title: "Configura la TV col telefono", subtitle: "Usa il telefono") { app.open(.pairing) }
            }
        }
    }

    private func activeSection(_ active: Source) -> some View {
        SettingsSection(title: active.name) {
            SettingRow(symbol: "arrow.triangle.2.circlepath", title: "Sincronizza ora",
                       subtitle: app.isSyncing ? "Sincronizzazione…"
                        : "Ultima sincronizzazione: \(active.lastSyncAt.map { Fmt.dayTime($0.int64Value) } ?? "mai")",
                       busy: app.isSyncing) {
                guard !app.isSyncing else { return }
                Task {
                    await app.syncNow(active.id, blocking: false)
                    app.showToast(app.syncError ?? "Sincronizzazione completata")
                }
            }
            SettingRow(symbol: "calendar", title: "Aggiorna EPG",
                       subtitle: active.lastEpgSyncAt.map { "Ultima sincronizzazione: \(Fmt.dayTime($0.int64Value))" },
                       busy: epgBusy) {
                guard !epgBusy else { return }
                epgBusy = true
                Task {
                    do { try await app.refreshEpg(); app.showToast("Guida TV aggiornata") } catch { app.showToast(readableError(error)) }
                    epgBusy = false
                }
            }
            if active.type == SourceType.xtream {
                SettingRow(symbol: "info.circle.fill", title: "Account",
                           subtitle: ["Scadenza: \(active.expiresAt.map { Fmt.day($0.int64Value) } ?? "illimitata")",
                                      active.maxConnections.map { "Connessioni massime: \($0.int32Value)" }]
                            .compactMap { $0 }.joined(separator: "  ·  "))
            }
            SettingRow(symbol: "tv", title: "Categorie visibili · Live", subtitle: "Nascondi le categorie che non ti interessano") {
                app.open(.categories(sourceId: active.id, type: "live"))
            }
            SettingRow(symbol: "film", title: "Categorie visibili · Film") { app.open(.categories(sourceId: active.id, type: "movie")) }
            SettingRow(symbol: "play.rectangle.on.rectangle", title: "Categorie visibili · Serie") { app.open(.categories(sourceId: active.id, type: "series")) }
        }
    }

    private var playback: some View {
        SettingsSection(title: "Riproduzione") {
            if let s = player.settings {
                ChoiceRow(title: "Chiedi se stai ancora guardando",
                          options: [(Int32(0), "Mai")] + [2, 3, 4, 6].map { (Int32($0), "\($0) ore") },
                          selected: s.stillWatchingAfterHours) { v in player.update { $0.hours = v } }
                ChoiceRow(title: "Buffer",
                          options: [(BufferMode.large, "Grande (predefinito)"), (BufferMode.auto_, "Automatico"), (BufferMode.normal, "Normale")],
                          selected: s.bufferMode) { v in player.update { $0.buffer = v } }
                ChoiceRow(title: "Lingua audio preferita",
                          options: Languages.choices.map { ($0.code, $0.name) },
                          selected: s.preferredAudioLanguages.first) { v in player.update { $0.audioLanguages = v.map { [$0] } ?? [] } }
                SwitchRow(symbol: "info.circle.fill", title: "Mostra statistiche nel player", subtitle: "Statistiche",
                          isOn: s.showStatsOverlay) { v in player.update { $0.stats = v } }
            }
            SettingRow(symbol: "captions.bubble.fill", title: "Sottotitoli") { app.open(.subtitles) }
            if app.activeId >= 0 {
                SettingRow(symbol: "speedometer", title: "Test connessione", subtitle: "Misura la tua rete e la velocità del provider") {
                    app.open(.connectionTest(sourceId: app.activeId))
                }
            }
        }
    }
}

// MARK: - Rows (Android Settings building blocks)

struct SettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(AppFont.titleMedium).foregroundStyle(Theme.textSecondary).padding(.leading, 8)
            VStack(alignment: .leading, spacing: 0) { content() }
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }
}

struct SettingRow: View {
    let symbol: String
    let title: String
    var subtitle: String?
    var busy = false
    var action: (() -> Void)?

    var body: some View {
        if let action {
            Button(action: action) { rowContent }
                .buttonStyle(PressStyle(radius: 12, scale: 1.01))
                .padding(.horizontal, 6)
        } else {
            rowContent.padding(.horizontal, 6)
        }
    }

    private var rowContent: some View {
        HStack(spacing: 16) {
            Image(systemName: symbol).font(.system(size: 19, weight: .semibold)).foregroundStyle(Theme.primary).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary).multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                if let subtitle {
                    Text(subtitle).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if busy { ProgressView().tint(Theme.primary) }
        }
        .padding(.horizontal, 12).padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

struct SwitchRow: View {
    let symbol: String
    let title: String
    let subtitle: String
    let isOn: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        Button { onChange(!isOn) } label: {
            HStack(spacing: 16) {
                Image(systemName: symbol).font(.system(size: 19, weight: .semibold)).foregroundStyle(Theme.primary).frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary)
                    Text(subtitle).font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // Android-style switch.
                Capsule().fill(isOn ? Theme.primary : Theme.surfaceElevated)
                    .overlay(Capsule().strokeBorder(isOn ? .clear : Theme.textSecondary, lineWidth: 2))
                    .frame(width: 52, height: 32)
                    .overlay(alignment: isOn ? .trailing : .leading) {
                        Circle().fill(isOn ? .white : Theme.textSecondary).frame(width: isOn ? 24 : 16).padding(isOn ? 4 : 8)
                    }
                    .animation(Theme.quick, value: isOn)
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(radius: 12, scale: 1.01))
        .padding(.horizontal, 6)
        .accessibilityValue(isOn ? "Attivo" : "Disattivo")
    }
}

/// Chip-style single choice (Android `ChoiceRow`).
struct ChoiceRow<T: Equatable>: View {
    let title: String
    let options: [(T, String)]
    let selected: T
    let onSelect: (T) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(AppFont.labelLarge).foregroundStyle(Theme.textSecondary)
            FlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(options.indices, id: \.self) { i in
                    let (value, label) = options[i]
                    let sel = value == selected
                    Button { onSelect(value) } label: {
                        Text(label).font(AppFont.labelLarge).foregroundStyle(sel ? Color.white : Theme.textSecondary)
                            .lineLimit(1)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background { if sel { Capsule().fill(Theme.accentGradient) } else { Capsule().fill(Theme.surfaceElevated) } }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(PressStyle(radius: 40, scale: 1.04))
                    .accessibilityAddTraits(sel ? .isSelected : [])
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }
}

private struct SourceRow: View {
    let source: Source
    let isActive: Bool
    let onUse: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(source.name).font(AppFont.bodyLarge).foregroundStyle(Theme.textPrimary).lineLimit(1)
                    if isActive {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 14)).foregroundStyle(Theme.success)
                            .accessibilityLabel("In uso")
                    }
                }
                Text((source.type == SourceType.xtream ? "API Xtream · " : "M3U · ") + host(source.url))
                    .font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if !isActive { SecondaryButton(text: "Usa", action: onUse) }
            RoundIconButton(symbol: "pencil", label: "Modifica", tint: Theme.textSecondary, action: onEdit)
            RoundIconButton(symbol: "trash.fill", label: "Elimina", tint: Theme.textSecondary, action: onDelete)
        }
        .padding(.horizontal, 18).padding(.vertical, 10)
    }

    private func host(_ url: String) -> String {
        let afterScheme = url.components(separatedBy: "://").last ?? url
        return afterScheme.components(separatedBy: "/").first ?? afterScheme
    }
}

// MARK: - Category visibility (Android `CategoriesScreen`)

struct CategoryVisibilityView: View {
    let sourceId: Int64
    let typeKey: String
    @Environment(AppModel.self) private var app
    @State private var categories: [Shared.Category] = []

    private var type: ContentType { typeKey == "movie" ? .movie : (typeKey == "series" ? .series : .live) }
    private var typeTitle: String { typeKey == "movie" ? "Film" : (typeKey == "series" ? "Serie" : "Live") }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "Categorie visibili · \(typeTitle)", onBack: { app.back() })
            ScrollView {
                LazyVStack(spacing: 4) {
                    ForEach(categories, id: \.id) { c in
                        SwitchRow(symbol: "folder.fill", title: c.name, subtitle: c.hidden ? "Nascosta" : "Visibile", isOn: !c.hidden) { visible in
                            Task { _ = try? await app.core.catalog.setCategoryHidden(sourceId: sourceId, type: type, category: c, hidden: !visible) }
                        }
                        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
                .padding(16)
            }
        }
        .task {
            await holdUntilCancelled([observeList(app.core.catalog.allCategories(sourceId: sourceId, type: type), as: Shared.Category.self) { categories = $0 }])
        }
    }
}
