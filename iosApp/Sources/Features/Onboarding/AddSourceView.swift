import SwiftUI
import Shared

/// Android `AddSourceScreen`.
struct AddSourceView: View {
    enum Mode { case onboarding, add, edit(Int64) }
    enum Kind { case xtream, m3u }

    let mode: Mode
    var onBack: (() -> Void)?
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var kind: Kind = .xtream
    @State private var name = ""
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var showPassword = false
    @State private var listUrl = ""
    @State private var epgUrl = ""
    @State private var userAgent = ""
    @State private var advanced = false
    @State private var loading = false
    @State private var error: String?
    @State private var usePhone = Theme.isTV
    @State private var prefilled = false

    private var editingId: Int64? {
        if case let .edit(id) = mode { return id }
        return nil
    }
    private var editing: Bool { editingId != nil }
    /// TV (and tablets): adding a list with the phone is the primary option.
    private var offerPhone: Bool { Theme.isTV || sizeClass == .regular }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: editing ? "Modifica lista" : "Aggiungi lista", onBack: backAction, large: true)
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if offerPhone && !editing {
                        SegmentedTabs(options: ["Usa il telefono", "Inserisci con il telecomando"], selected: usePhone ? 0 : 1) { usePhone = $0 == 0 }
                    }
                    if offerPhone && usePhone && !editing {
                        PairingPanel().padding(.vertical, 16)
                    } else {
                        form
                    }
                }
                .padding(.horizontal, 20).padding(.vertical, 8)
                .frame(maxWidth: offerPhone && usePhone ? 900 : 620)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .task { await prefill() }
    }

    private var backAction: (() -> Void)? {
        switch mode {
        case .onboarding: return onBack
        default: return { model.back() }
        }
    }

    @ViewBuilder private var form: some View {
        if !editing {
            SegmentedTabs(options: ["Accesso al server", "Lista M3U"], selected: kind == .xtream ? 0 : 1) { i in
                kind = i == 0 ? .xtream : .m3u
                error = nil
            }
        }
        FormField(label: "Nome", text: $name)
        if kind == .xtream {
            Text("Accedi con indirizzo, username e password del tuo server").font(AppFont.labelLarge).foregroundStyle(Theme.textSecondary)
            FormField(label: "Server (es. http://server.esempio:8080)", text: $server, keyboard: .url)
            FormField(label: "Username", text: $username, contentType: .username)
            FormField(label: editing ? "Password (lascia vuoto per non cambiarla)" : "Password", text: $password,
                      secure: !showPassword, contentType: .password) {
                Button { showPassword.toggle() } label: {
                    Image(systemName: showPassword ? "eye.slash" : "eye").foregroundStyle(Theme.textSecondary).frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(showPassword ? "Nascondi password" : "Mostra password")
            }
        } else {
            FormField(label: editing ? "URL lista (obbligatorio per salvare)" : "URL lista", text: $listUrl, keyboard: .url)
            FormField(label: "URL EPG (opzionale)", text: $epgUrl, keyboard: .url)
        }

        Button { withAnimation(Theme.quick) { advanced.toggle() } } label: {
            HStack(spacing: 4) {
                Text("Avanzate").font(AppFont.labelLarge).foregroundStyle(Theme.textSecondary)
                Image(systemName: advanced ? "chevron.up" : "chevron.down").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.textSecondary)
            }
            .padding(.vertical, 6).padding(.horizontal, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(radius: 12, scale: 1.04))
        if advanced {
            FormField(label: "User-Agent (opzionale)", text: $userAgent)
        }

        if let error {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.circle").foregroundStyle(Theme.live)
                Text(error).font(AppFont.bodyMedium).foregroundStyle(Theme.textPrimary).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(14)
            .background(Theme.live.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }

        Spacer().frame(height: 4)
        if loading {
            HStack(spacing: 12) {
                ProgressView().tint(Theme.primary)
                Text("Connessione in corso…").foregroundStyle(Theme.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(12)
        } else {
            PrimaryButton(text: editing ? "Salva" : "Salva e connetti", fullWidth: true) { Task { await save() } }
        }
        Text(Legal.disclaimer)
            .font(AppFont.bodySmall).foregroundStyle(Theme.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
    }

    private func prefill() async {
        guard let id = editingId, !prefilled else { return }
        prefilled = true
        guard let s = try? await model.core.sources.byId(id: id) else { return }
        name = s.name
        userAgent = s.userAgent ?? ""
        epgUrl = s.epgUrl ?? ""
        if s.type == SourceType.xtream {
            kind = .xtream
            server = s.url
            username = s.username ?? ""
        } else {
            kind = .m3u
        }
    }

    private func save() async {
        guard !loading else { return }
        let missing: Bool
        switch kind {
        case .xtream: missing = server.trimmed.isEmpty || username.trimmed.isEmpty || (password.isEmpty && !editing)
        case .m3u: missing = listUrl.trimmed.isEmpty
        }
        if missing { error = "Compila tutti i campi obbligatori"; return }
        let ua = userAgent.trimmed.isEmpty ? nil : userAgent.trimmed
        let input: SourceInput = kind == .xtream
            ? SourceInputXtream(name: name.trimmed, serverUrl: server.trimmed, username: username.trimmed, password: password, userAgent: ua)
            : SourceInputM3u(name: name.trimmed, listUrl: listUrl.trimmed, epgUrl: epgUrl.trimmed.isEmpty ? nil : epgUrl.trimmed, userAgent: ua)
        error = nil
        loading = true
        defer { loading = false }
        do {
            if let id = editingId {
                try await model.updateSource(id, input)
                model.back()
            } else {
                try await model.addSource(input)
                model.path = []
            }
        } catch {
            self.error = readableError(error)
        }
    }
}

/// Android `SegmentedTabs`: rounded track, the selected segment uses the accent gradient.
struct SegmentedTabs: View {
    let options: [String]
    let selected: Int
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options.indices, id: \.self) { i in
                let sel = i == selected
                Button { onSelect(i) } label: {
                    Text(options[i]).font(AppFont.labelLarge).foregroundStyle(sel ? Theme.onAccent : Theme.textSecondary)
                        .lineLimit(1).minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background { if sel { Capsule().fill(Theme.accentGradient) } }
                        .contentShape(Capsule())
                }
                .buttonStyle(PressStyle(radius: 40, scale: 1.02))
                .accessibilityAddTraits(sel ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Theme.surface, in: Capsule())
    }
}

/// Android OutlinedTextField with a floating label.
struct FormField<Trailing: View>: View {
    enum Keyboard { case text, url }
    let label: String
    @Binding var text: String
    var keyboard: Keyboard = .text
    var secure = false
    var contentType: UITextContentType?
    @ViewBuilder var trailing: () -> Trailing
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                if !text.isEmpty || focused {
                    Text(label).font(AppFont.labelMedium).foregroundStyle(focused ? Theme.primary : Theme.textSecondary).lineLimit(1)
                }
                Group {
                    if secure {
                        SecureField("", text: $text, prompt: prompt)
                    } else {
                        TextField("", text: $text, prompt: prompt)
                    }
                }
                .font(AppFont.bodyLarge)
                .foregroundStyle(Theme.textPrimary)
                .focused($focused)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .keyboardType(keyboard == .url ? .URL : .default)
                #endif
                .textContentType(contentType ?? (keyboard == .url ? .URL : nil))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 58)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(focused ? Theme.primary : Theme.outline, lineWidth: focused ? 2 : 1))
        .contentShape(Rectangle())
        .onTapGesture { focused = true }
        .animation(Theme.quick, value: focused)
    }

    private var prompt: Text? {
        (text.isEmpty && !focused) ? Text(label).foregroundStyle(Theme.textSecondary) : nil
    }
}

extension FormField where Trailing == EmptyView {
    init(label: String, text: Binding<String>, keyboard: Keyboard = .text, secure: Bool = false, contentType: UITextContentType? = nil) {
        self.init(label: label, text: text, keyboard: keyboard, secure: secure, contentType: contentType) { EmptyView() }
    }
}
