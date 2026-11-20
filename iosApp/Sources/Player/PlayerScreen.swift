import AVKit
import SwiftUI
import Shared

struct PlayerScreen: View {
    let item: PlayItem
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var vm: PlayerViewModel?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let vm {
                #if os(tvOS)
                TVPlayerContent(vm: vm, close: close, diagnose: diagnose)
                #else
                IOSPlayerContent(vm: vm, close: close, diagnose: diagnose)
                #endif
            }
        }
        .task {
            guard vm == nil else { return } // re-appearing must never open a second player
            let model = PlayerViewModel(item: item, core: app.core, startAt: app.pendingStartAt,
                                        advisorFor: { [app] mode in app.bufferAdvisor(for: mode) })
            app.pendingStartAt = nil
            vm = model
            #if os(iOS)
            UIApplication.shared.isIdleTimerDisabled = true
            AppDelegate.lockPlayerOrientation() // normally already landscape: AppModel.play rotates first
            #endif
            await model.start()
        }
        .onChange(of: vm?.shouldDismiss ?? false) { _, done in
            guard done else { return }
            if let message = vm?.stopMessage { app.showToast(message) }
            close()
        }
        .onDisappear {
            vm?.close()
            #if os(iOS)
            UIApplication.shared.isIdleTimerDisabled = false
            AppDelegate.lockOrientation(.allButUpsideDown, rotateTo: .portrait)
            #endif
        }
        #if os(iOS)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        #endif
    }

    private func close() {
        vm?.close()
        dismiss()
    }

    /// "Perché si blocca?": stop the stream (frees the connection), then open the connection test.
    private func diagnose() {
        let sid = vm?.sourceId ?? app.activeId
        close()
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            app.open(.connectionTest(sourceId: sid))
        }
    }
}

// MARK: - iOS (Android PlayerScreen replica)

#if os(iOS)
private struct IOSPlayerContent: View {
    @Bindable var vm: PlayerViewModel
    @Environment(AppModel.self) private var app
    let close: () -> Void
    let diagnose: () -> Void

    /// Controls start HIDDEN: when a channel starts only the loading spinner is visible.
    @State private var controlsVisible = false
    @State private var drawerOpen = false
    @State private var sheet: PlayerSheet?
    @State private var interaction = 0
    @State private var aspect: AspectMode = .fit
    @State private var speed: Float = 1
    @State private var scrub: Double?
    @State private var demoPinned = false

    private var blocking: Bool { vm.failure != nil || vm.retryCountdown != nil || vm.busy != nil }
    private var avEngine: AVPlayerEngine? { vm.engine as? AVPlayerEngine }

    var body: some View {
        ZStack {
            if let engine = vm.engine {
                EngineView(engine: engine)
                    .id(vm.engineGeneration)
                    .ignoresSafeArea()
            }
            // Gesture surface: tap toggles controls (or closes the drawer), vertical swipe zaps on live.
            Color.clear
                .contentShape(Rectangle())
                .ignoresSafeArea()
                .onTapGesture {
                    if drawerOpen { withAnimation(.easeOut(duration: 0.2)) { drawerOpen = false } } else { toggleControls() }
                }
                .gesture(DragGesture(minimumDistance: 30).onEnded { value in
                    guard vm.isLive, abs(value.translation.height) > abs(value.translation.width), abs(value.translation.height) > 80 else { return }
                    vm.zap(value.translation.height < 0 ? 1 : -1)
                })

            // Loading: only the spinner. With controls visible the spinner takes the play button's place.
            if vm.spinnerVisible && !blocking && !controlsVisible {
                ProgressView().controlSize(.large).tint(Theme.primary).scaleEffect(1.2)
                    .allowsHitTesting(false)
            }

            if let seconds = vm.retryCountdown {
                RetryOverlay(seconds: seconds) { vm.cancelRetry() }
            } else if let error = vm.failure {
                ErrorOverlay(title: error.title, message: error.message, onRetry: { vm.retry() }, onBack: close, onDiagnose: diagnose)
            }

            if vm.showStats && !vm.statsLines.isEmpty {
                StatsOverlay(lines: vm.statsLines)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .padding(.top, 84).padding(.leading, 16)
            }

            VStack(spacing: 8) {
                if vm.bufferBanner { PlayerNotice(text: "Buffer aumentato per ridurre i blocchi") }
                if let notice = vm.delayNotice {
                    PlayerNotice(text: notice)
                        .task(id: notice) {
                            try? await Task.sleep(for: .seconds(4))
                            if vm.delayNotice == notice { vm.delayNotice = nil }
                        }
                }
            }
            .frame(maxHeight: .infinity, alignment: .top)
            .padding(.top, 84)

            if let seconds = vm.sleepWarning {
                SleepWarningPill(seconds: seconds, onExtend: { vm.extendSleep() }, onCancel: { vm.cancelSleep() })
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }

            if controlsVisible && !blocking {
                controls.transition(.opacity)
            }

            if vm.isLive && vm.miniGuideVisible && !controlsVisible && !drawerOpen {
                ZapBanner(vm: vm)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .transition(.opacity)
            }

            if drawerOpen {
                HStack(spacing: 0) {
                    ChannelDrawer(vm: vm, core: app.core) { withAnimation(.easeOut(duration: 0.2)) { drawerOpen = false } }
                        .frame(width: 380)
                        .background(
                            LinearGradient(colors: [Theme.background.opacity(0.97), Theme.background.opacity(0.88)], startPoint: .leading, endPoint: .trailing)
                                .ignoresSafeArea()
                        )
                    Spacer(minLength: 0)
                }
                .transition(.move(edge: .leading))
            }

            if let countdown = vm.nextEpisodeCountdown, let next = vm.nextEpisode {
                NextEpisodePrompt(episode: next, countdown: countdown, onPlay: { vm.playNextEpisode() }, onCancel: { vm.cancelNextEpisode() })
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }

            if let busy = vm.busy {
                BusyDialog(busy: busy, onPauseDownloads: { vm.pauseDownloadsAndPlay() }, onCancel: close)
            }
            if let seconds = vm.stillWatchingCountdown {
                StillWatchingDialog(seconds: seconds, onContinue: { vm.continueWatching() }, onExit: { vm.exitFromPrompt() })
            }
            if let sheet {
                DialogHost(onDismiss: { self.sheet = nil }) { dialog(sheet) }
            }
        }
        .animation(.easeOut(duration: 0.2), value: controlsVisible)
        .animation(.easeOut(duration: 0.2), value: vm.miniGuideVisible)
        .animation(.easeOut(duration: 0.2), value: sheet)
        .task(id: "\(controlsVisible)-\(vm.isPlaying)-\(interaction)-\(sheet != nil)") {
            // Auto-hide controls after 4 s of inactivity while playing.
            guard controlsVisible, vm.isPlaying, sheet == nil, scrub == nil, !demoPinned else { return }
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            controlsVisible = false
        }
        .onChange(of: vm.engineGeneration) { _, _ in applyVideoSettings() }
        .onChange(of: aspect) { _, _ in applyVideoSettings() }
        #if DEBUG
        .task {
            // Development only (simulator screenshots without touches):
            // STEPLAYER_DEMO_CONTROLS=1 shows the controls after 6 s, STEPLAYER_DEMO_ZAP=1 zaps after 6 s.
            let env = ProcessInfo.processInfo.environment
            guard env["STEPLAYER_DEMO_CONTROLS"] == "1" || env["STEPLAYER_DEMO_ZAP"] == "1" else { return }
            try? await Task.sleep(for: .seconds(6))
            if env["STEPLAYER_DEMO_ZAP"] == "1" { vm.zap(1) } else { demoPinned = true; poke() }
        }
        #endif
        .onChange(of: speed) { _, _ in applyVideoSettings() }
    }

    private func toggleControls() {
        vm.interaction()
        controlsVisible.toggle()
        interaction += 1
    }

    private func poke() {
        vm.interaction()
        controlsVisible = true
        interaction += 1
    }

    private func open(_ s: PlayerSheet) {
        vm.interaction()
        sheet = s
    }

    private func applyVideoSettings() {
        guard let av = avEngine, let layer = av.view.layer as? AVPlayerLayer else { return }
        switch aspect {
        case .fit: layer.videoGravity = .resizeAspect
        case .fill: layer.videoGravity = .resize
        case .zoom: layer.videoGravity = .resizeAspectFill
        }
        if !vm.isLive {
            av.player.defaultRate = speed
            if av.player.rate > 0 { av.player.rate = speed }
        }
    }

    // MARK: Controls (top bar, centre transport, bottom live info / seek bar)

    private var controls: some View {
        ZStack {
            VStack(spacing: 0) {
                LinearGradient(colors: [.black.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom).frame(height: 150)
                Spacer()
                LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom).frame(height: 170)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                topBar
                Spacer(minLength: 0)
                bottom
                    .padding(.horizontal, 24).padding(.vertical, 18)
            }
            centre
        }
    }

    private var topBar: some View {
        HStack(spacing: 0) {
            RoundIconButton(symbol: "arrow.left", label: "Indietro", tint: .white, action: close)
            Spacer().frame(width: 8)
            VStack(alignment: .leading, spacing: 0) {
                Text(vm.title).font(AppFont.titleLarge).foregroundStyle(.white).lineLimit(1)
                let sub = vm.isLive ? vm.nowProgram?.title : vm.subtitle
                if let sub, !sub.isEmpty {
                    Text(sub).font(AppFont.bodyMedium).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if vm.isLive && vm.lastChannelName != nil {
                RoundIconButton(symbol: "clock.arrow.circlepath", label: "Ultimo canale", tint: .white) { vm.back(); poke() }
            }
            if vm.isLive && vm.zapList.count > 1 {
                RoundIconButton(symbol: "list.bullet", label: "Canali", tint: .white) {
                    vm.interaction()
                    controlsVisible = false
                    withAnimation(.easeOut(duration: 0.22)) { drawerOpen = true }
                }
            }
            if vm.audioTracks.count > 1 {
                RoundIconButton(symbol: "music.note", label: "Audio", tint: .white) { open(.audio) }
            }
            // Always visible: style and track choice even when the stream has no subtitles (or they load late).
            RoundIconButton(symbol: "captions.bubble", label: "Sottotitoli", tint: .white) { open(.text) }
            if avEngine != nil {
                RoundIconButton(symbol: "aspectratio", label: "Formato", tint: .white) { open(.aspect) }
            }
            RoundIconButton(symbol: "slider.horizontal.3", label: "Opzioni", tint: .white) { open(.menu) }
            if !vm.isLive && avEngine != nil {
                RoundIconButton(symbol: "gauge.with.dots.needle.67percent", label: "Velocità", tint: .white) { open(.speed) }
            }
            if let pip = avEngine?.pipController {
                RoundIconButton(symbol: "pip.enter", label: "Picture in picture", tint: .white) { pip.startPictureInPicture() }
            }
            AirPlayButton().frame(width: 44, height: 44).accessibilityLabel("AirPlay")
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
    }

    private var centre: some View {
        HStack(spacing: 28) {
            if canSeek {
                RoundIconButton(symbol: "gobackward.10", label: "Indietro 10 secondi", size: 56, tint: .white) { vm.seek(by: -10); poke() }
            }
            if vm.spinnerVisible {
                ProgressView().controlSize(.large).tint(Theme.primary).frame(width: 76, height: 76)
            } else {
                RoundIconButton(symbol: vm.isPlaying ? "pause.fill" : "play.fill", label: vm.isPlaying ? "Pausa" : "Riproduci",
                                size: 76, background: .white.opacity(0.16), tint: .white) { vm.togglePlay(); poke() }
            }
            if canSeek {
                RoundIconButton(symbol: "goforward.10", label: "Avanti 10 secondi", size: 56, tint: .white) { vm.seek(by: 10); poke() }
            }
        }
    }

    private var canSeek: Bool { !vm.isLive && vm.duration > 0 }

    @ViewBuilder private var bottom: some View {
        if vm.isLive {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    LiveBadge()
                    Text(vm.nowProgram?.title ?? "Nessuna informazione sul programma").font(AppFont.titleMedium).foregroundStyle(.white).lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let p = vm.nowProgram {
                        Text("\(Fmt.time(p.startAt)) – \(Fmt.time(p.endAt))").font(AppFont.labelLarge).foregroundStyle(.white.opacity(0.7))
                    }
                }
                if let p = vm.nowProgram {
                    EpgProgressBar(startAt: p.startAt, endAt: p.endAt, height: 4).padding(.top, 10)
                }
                if let n = vm.nextProgram {
                    Text("Dopo: \(Fmt.time(n.startAt))  \(n.title)").font(AppFont.bodyMedium).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                        .padding(.top, 8)
                }
            }
        } else {
            let total = max(vm.duration, 1)
            let value = scrub ?? vm.currentTime
            VStack(spacing: 2) {
                SeekBar(value: min(value, total), total: total, enabled: canSeek,
                        // Preview: the video follows the finger, as on Android.
                        onScrub: { scrub = $0; vm.scrub(to: $0); poke() },
                        onCommit: { vm.endScrub(at: $0); scrub = nil; poke() })
                HStack {
                    Text(Fmt.clock(value)).font(AppFont.labelLarge).foregroundStyle(.white).monospacedDigit()
                    Spacer()
                    Text(vm.duration > 0 ? "-" + Fmt.clock(max(0, vm.duration - value)) : "")
                        .font(AppFont.labelLarge).foregroundStyle(.white.opacity(0.7)).monospacedDigit()
                }
            }
            // Controls closed mid-drag (a dialog, PiP...): never leave the player silenced in preview mode.
            .onDisappear { if scrub != nil || vm.scrubbing { vm.endScrub(at: nil); scrub = nil } }
        }
    }

    // MARK: Dialogs

    @ViewBuilder
    private func dialog(_ s: PlayerSheet) -> some View {
        let dismiss = { sheet = nil }
        switch s {
        case .audio:
            OptionsDialog(title: "Audio", options: vm.audioTracks.map { ($0.name, $0.isSelected) },
                          onSelect: { vm.selectAudio(vm.audioTracks[$0]) }, onDismiss: dismiss)
        case .text:
            let st = vm.subtitleStyle
            let current = vm.subtitleTracks.first { $0.isSelected }?.name ?? "Disattivati"
            let entries: [(String, PlayerSheet?)] =
                (vm.subtitleTracks.isEmpty ? [("Nessun sottotitolo in questo contenuto", nil)] : [("Lingua sottotitoli: \(current)", .textTrack)])
                + [("Dimensione: \(Int((st?.scale ?? 1) * 100))%", .subSize),
                   ("Colore: \(SubtitleChoices.colorName(st?.color ?? .white))", .subColor),
                   ("Sfondo del testo: \(SubtitleChoices.backgroundName(st?.background ?? .none))", .subBackground)]
                + (vm.subtitleTracks.isEmpty ? [] : [("Ritardo sottotitoli", .subSync)])
            MenuDialog(title: "Sottotitoli", items: entries.map(\.0), onSelect: { i in sheet = entries[i].1 ?? .text }, onDismiss: dismiss)
        case .subSize:
            OptionsDialog(title: "Dimensione", options: SubtitleChoices.scales.map { ("\(Int($0 * 100))%", $0 == vm.subtitleStyle?.scale) },
                          onSelect: { vm.updateSubtitleStyle(scale: SubtitleChoices.scales[$0]); sheet = .text }, onDismiss: { sheet = .text })
        case .subColor:
            OptionsDialog(title: "Colore", options: SubtitleChoices.colors.map { (SubtitleChoices.colorName($0), $0 == vm.subtitleStyle?.color) },
                          onSelect: { vm.updateSubtitleStyle(color: SubtitleChoices.colors[$0]); sheet = .text }, onDismiss: { sheet = .text })
        case .subBackground:
            OptionsDialog(title: "Sfondo del testo", options: SubtitleChoices.backgrounds.map { (SubtitleChoices.backgroundName($0), $0 == vm.subtitleStyle?.background) },
                          onSelect: { vm.updateSubtitleStyle(background: SubtitleChoices.backgrounds[$0]); sheet = .text }, onDismiss: { sheet = .text })
        case .textTrack:
            OptionsDialog(title: "Sottotitoli",
                          options: [("Disattivati", !vm.subtitleTracks.contains { $0.isSelected })] + vm.subtitleTracks.map { ($0.name, $0.isSelected) },
                          onSelect: { i in vm.selectSubtitle(i == 0 ? nil : vm.subtitleTracks[i - 1]) }, onDismiss: dismiss)
        case .aspect:
            OptionsDialog(title: "Formato", options: AspectMode.allCases.map { ($0.title, $0 == aspect) },
                          onSelect: { aspect = AspectMode(rawValue: $0) ?? .fit }, onDismiss: dismiss)
        case .speed:
            let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 2]
            OptionsDialog(title: "Velocità",
                          options: speeds.map { ($0 == 1 ? "Normale" : "\(String(format: "%g", $0).replacingOccurrences(of: ".", with: ","))x", $0 == speed) },
                          onSelect: { speed = speeds[$0] }, onDismiss: dismiss)
        case .menu:
            let entries: [(String, PlayerSheet?)] = [("Timer di spegnimento", .sleep), ("Sincronizzazione audio", .audioSync)]
                + (vm.subtitleTracks.isEmpty ? [] : [("Ritardo sottotitoli", .subSync)])
                + [("Statistiche: \(vm.showStats ? "ON" : "OFF")", nil), ("Perché si blocca?", nil)]
            MenuDialog(title: "Opzioni", items: entries.map(\.0), onSelect: { i in
                let (label, next) = entries[i]
                if let next { sheet = next }
                else if label.hasPrefix("Statistiche") { vm.showStats.toggle(); sheet = nil }
                else { sheet = nil; diagnose() }
            }, onDismiss: dismiss)
        case .sleep:
            let choices = SleepChoice.allCases.filter { $0 != .programEnd || vm.canSleepAtProgramEnd }
            OptionsDialog(title: vm.sleepRemainingText ?? "Timer di spegnimento",
                          options: choices.map { ($0.title, $0 == .off && vm.sleepRemainingText == nil) },
                          onSelect: { choices[$0].apply(to: vm) }, onDismiss: dismiss)
        case .audioSync:
            DelayDialog(title: "Sincronizzazione audio", valueMs: vm.audioDelayMs, step: 50, onChange: { vm.setAudioDelay($0) }, onDismiss: dismiss)
        case .subSync:
            DelayDialog(title: "Ritardo sottotitoli", valueMs: vm.subtitleDelayMs, step: 100, onChange: { vm.setSubtitleDelay($0) }, onDismiss: dismiss)
        }
    }
}

/// Android's Material 3 seek bar, 1:1: thick track (accent / white 25%), vertical white thumb with a gap on both
/// sides, stop dot at the end. The whole bar responds like on Android: touch anywhere and drag, or tap to jump.
/// (The system Slider only moved when the drag started exactly on its small thumb, and a tap on the bar fell
/// through to the screen and hid the controls.)
private struct SeekBar: View {
    let value: Double
    let total: Double
    let enabled: Bool
    let onScrub: (Double) -> Void
    let onCommit: (Double) -> Void

    @State private var dragging = false
    private let trackHeight: CGFloat = 16
    private let thumbWidth: CGFloat = 4
    private let thumbHeight: CGFloat = 44
    private let gap: CGFloat = 6

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let fraction = total > 0 ? min(max(value / total, 0), 1) : 0
            let thumbX = (width - thumbWidth) * fraction // thumb's leading edge
            let activeWidth = max(0, thumbX - gap)
            let inactiveStart = thumbX + thumbWidth + gap
            let inactiveWidth = max(0, width - inactiveStart)
            ZStack(alignment: .leading) {
                UnevenRoundedRectangle(cornerRadii: .init(topLeading: 8, bottomLeading: 8, bottomTrailing: 2, topTrailing: 2), style: .continuous)
                    .fill(Theme.primary)
                    .frame(width: activeWidth, height: trackHeight)
                    .opacity(activeWidth > 0 ? 1 : 0)
                UnevenRoundedRectangle(cornerRadii: .init(topLeading: 2, bottomLeading: 2, bottomTrailing: 8, topTrailing: 8), style: .continuous)
                    .fill(Color.white.opacity(0.25))
                    .frame(width: inactiveWidth, height: trackHeight)
                    .offset(x: inactiveStart)
                    .opacity(inactiveWidth > 0 ? 1 : 0)
                if inactiveWidth > 16 { // stop indicator, hidden once the thumb reaches it
                    Circle().fill(Theme.primary).frame(width: 4, height: 4).offset(x: width - 10)
                }
                Capsule().fill(Color.white)
                    .frame(width: dragging ? 2 : thumbWidth, height: thumbHeight)
                    .offset(x: thumbX + (dragging ? 1 : 0))
            }
            .frame(width: width, height: geo.size.height, alignment: .leading)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        guard enabled, total > 0 else { return }
                        dragging = true
                        onScrub(position(at: g.location.x, width: width))
                    }
                    .onEnded { g in
                        guard enabled, total > 0 else { dragging = false; return }
                        dragging = false
                        onCommit(position(at: g.location.x, width: width))
                    }
            )
        }
        .frame(height: thumbHeight)
        .opacity(enabled ? 1 : 0.38)
        .animation(.easeOut(duration: 0.12), value: dragging)
        .accessibilityElement()
        .accessibilityLabel("Posizione")
        .accessibilityValue(Fmt.clock(value))
        .accessibilityAdjustableAction { direction in
            guard enabled else { return }
            switch direction {
            case .increment: onCommit(min(total, value + 10))
            case .decrement: onCommit(max(0, value - 10))
            @unknown default: break
            }
        }
    }

    /// Seconds under the finger (the thumb's centre follows it).
    private func position(at x: CGFloat, width: CGFloat) -> Double {
        let usable = max(1, width - thumbWidth)
        let fraction = min(max((x - thumbWidth / 2) / usable, 0), 1)
        return Double(fraction) * total
    }
}
#endif

// MARK: - tvOS

#if os(tvOS)
private struct TVPlayerContent: View {
    @Bindable var vm: PlayerViewModel
    let close: () -> Void
    let diagnose: () -> Void
    @Environment(AppModel.self) private var app

    private var blocking: Bool { vm.failure != nil || vm.retryCountdown != nil || vm.busy != nil }

    var body: some View {
        ZStack {
            if let av = vm.engine as? AVPlayerEngine {
                TVPlayerController(player: av.player, vm: vm, diagnose: diagnose)
                    .id(vm.engineGeneration)
                    .ignoresSafeArea()
            } else if let engine = vm.engine {
                TVCustomPlayer(vm: vm, engine: engine, close: close)
            }
            if vm.spinnerVisible && !blocking {
                ProgressView().controlSize(.large).tint(Theme.primary).allowsHitTesting(false)
            }
            if vm.isLive && vm.miniGuideVisible {
                ZapBanner(vm: vm)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .padding(40)
                    .allowsHitTesting(false)
            }
            if vm.showStats && !vm.statsLines.isEmpty {
                StatsOverlay(lines: vm.statsLines)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .padding(60)
            }
            if let seconds = vm.retryCountdown {
                RetryOverlay(seconds: seconds) { vm.cancelRetry() }
            } else if let error = vm.failure {
                ErrorOverlay(title: error.title, message: error.message, onRetry: { vm.retry() }, onBack: close, onDiagnose: diagnose)
            }
            if let countdown = vm.nextEpisodeCountdown, let next = vm.nextEpisode {
                NextEpisodePrompt(episode: next, countdown: countdown, onPlay: { vm.playNextEpisode() }, onCancel: { vm.cancelNextEpisode() })
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(40)
            }
            if let seconds = vm.sleepWarning {
                SleepWarningPill(seconds: seconds, onExtend: { vm.extendSleep() }, onCancel: { vm.cancelSleep() })
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    .padding(40)
            }
            if let busy = vm.busy {
                BusyDialog(busy: busy, onPauseDownloads: { vm.pauseDownloadsAndPlay() }, onCancel: close)
            }
            if let seconds = vm.stillWatchingCountdown {
                StillWatchingDialog(seconds: seconds, onContinue: { vm.continueWatching() }, onExit: { vm.exitFromPrompt() })
            }
        }
        .animation(.easeOut(duration: 0.2), value: vm.miniGuideVisible)
        .sheet(isPresented: $vm.showChannelList) {
            ChannelDrawer(vm: vm, core: app.core) { vm.showChannelList = false }
                .frame(width: 900, height: 900)
                .background(Theme.background)
        }
        .sheet(isPresented: $vm.showSyncPanel) { TVSyncPanel(vm: vm) }
        .sheet(isPresented: $vm.showSubtitlePanel) { TVSubtitlePanel(vm: vm) }
        .onChange(of: vm.channel?.id) { _, _ in vm.showChannelList = false }
    }
}

/// Native tvOS player: Siri Remote transport, info panel, audio/subtitle menus.
private struct TVPlayerController: UIViewControllerRepresentable {
    let player: AVPlayer
    let vm: PlayerViewModel
    let diagnose: () -> Void

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = ZapPlayerViewController()
        vc.player = player
        vc.allowsPictureInPicturePlayback = false
        // Honours the user's "Adatta frequenza / gamma dinamica" system setting.
        vc.appliesPreferredDisplayCriteriaAutomatically = true
        vc.onAnyPress = { vm.interaction() }
        if vm.isLive {
            vc.onUp = { vm.zap(1) }
            vc.onDown = { vm.zap(-1) }
            vc.onLeft = { vm.showChannelList = true }
            vc.onPlayPauseDoubleTap = { vm.back() }
        }
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== player { vc.player = player }
        vc.transportBarCustomMenuItems = Self.menu(vm, diagnose: diagnose)
        if vm.nextEpisode != nil, !vm.isLive {
            vc.contextualActions = [UIAction(title: "Prossimo episodio", image: UIImage(systemName: "forward.end.fill")) { _ in
                vm.playNextEpisode()
            }]
        } else {
            vc.contextualActions = []
        }
    }

    /// Transport-bar menus (Android "Opzioni"): channel list, last channel, sleep timer, sync, stats, diagnosis.
    @MainActor static func menu(_ vm: PlayerViewModel, diagnose: @escaping () -> Void) -> [UIMenuElement] {
        var items: [UIMenuElement] = [subtitleMenu(vm)]
        if vm.isLive && vm.zapList.count > 1 {
            items.append(UIAction(title: "Canali", image: UIImage(systemName: "list.bullet")) { _ in vm.showChannelList = true })
        }
        if vm.isLive && vm.lastChannelName != nil {
            items.append(UIAction(title: "Ultimo canale", image: UIImage(systemName: "clock.arrow.circlepath")) { _ in vm.back() })
        }
        items.append(UIMenu(title: "Timer di spegnimento", subtitle: vm.sleepRemainingText, image: UIImage(systemName: "moon.zzz"),
                            children: SleepChoice.allCases.filter { $0 != .programEnd || vm.canSleepAtProgramEnd }.map { choice in
            UIAction(title: choice.title) { _ in choice.apply(to: vm) }
        }))
        items.append(UIAction(title: "Sincronizzazione audio", image: UIImage(systemName: "waveform")) { _ in vm.showSyncPanel = true })
        items.append(UIAction(title: "Statistiche", image: UIImage(systemName: "chart.bar.xaxis"), state: vm.showStats ? .on : .off) { _ in
            vm.showStats.toggle()
        })
        items.append(UIAction(title: "Perché si blocca?", image: UIImage(systemName: "stethoscope")) { _ in diagnose() })
        return items
    }

    /// Always in the transport bar: track (also offered by the system panel when the stream has tracks) and style.
    @MainActor static func subtitleMenu(_ vm: PlayerViewModel) -> UIMenu {
        let st = vm.subtitleStyle
        var children: [UIMenuElement] = []
        if vm.subtitleTracks.isEmpty {
            children.append(UIAction(title: "Nessun sottotitolo in questo contenuto", attributes: .disabled) { _ in })
        } else {
            children.append(UIMenu(title: "Lingua sottotitoli", options: .singleSelection, children:
                [UIAction(title: "Disattivati", state: vm.subtitleTracks.contains { $0.isSelected } ? .off : .on) { _ in vm.selectSubtitle(nil) }]
                + vm.subtitleTracks.map { t in UIAction(title: t.name, state: t.isSelected ? .on : .off) { _ in vm.selectSubtitle(t) } }))
        }
        children.append(UIMenu(title: "Dimensione", options: .singleSelection, children: SubtitleChoices.scales.map { v in
            UIAction(title: "\(Int(v * 100))%", state: st?.scale == v ? .on : .off) { _ in vm.updateSubtitleStyle(scale: v) }
        }))
        children.append(UIMenu(title: "Colore", options: .singleSelection, children: SubtitleChoices.colors.map { c in
            UIAction(title: SubtitleChoices.colorName(c), state: st?.color == c ? .on : .off) { _ in vm.updateSubtitleStyle(color: c) }
        }))
        children.append(UIMenu(title: "Sfondo del testo", options: .singleSelection, children: SubtitleChoices.backgrounds.map { b in
            UIAction(title: SubtitleChoices.backgroundName(b), state: st?.background == b ? .on : .off) { _ in vm.updateSubtitleStyle(background: b) }
        }))
        return UIMenu(title: "Sottotitoli", image: UIImage(systemName: "captions.bubble"), children: children)
    }
}

/// Live zapping with the Siri Remote: click up/down = next/previous channel, left = channel list,
/// double Play/Pause = last channel. Other presses keep the native behaviour.
final class ZapPlayerViewController: AVPlayerViewController {
    var onUp: (() -> Void)?
    var onDown: (() -> Void)?
    var onLeft: (() -> Void)?
    var onPlayPauseDoubleTap: (() -> Void)?
    var onAnyPress: (() -> Void)?
    private var lastPlayPause = Date.distantPast

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        onAnyPress?()
        var handled = false
        for press in presses {
            switch press.type {
            case .upArrow where onUp != nil: onUp?(); handled = true
            case .downArrow where onDown != nil: onDown?(); handled = true
            case .leftArrow where onLeft != nil: onLeft?(); handled = true
            case .playPause where onPlayPauseDoubleTap != nil:
                if Date().timeIntervalSince(lastPlayPause) < 0.4 { onPlayPauseDoubleTap?(); handled = true }
                lastPlayPause = Date()
            default: break
            }
        }
        if !handled { super.pressesBegan(presses, with: event) }
    }
}

/// Audio / subtitle delay on tvOS (Android DelayDialog values).
private struct TVSyncPanel: View {
    @Bindable var vm: PlayerViewModel
    var body: some View {
        VStack(spacing: 40) {
            DelayDialog(title: "Sincronizzazione audio", valueMs: vm.audioDelayMs, step: 50, onChange: { vm.setAudioDelay($0) }) { vm.showSyncPanel = false }
            if !vm.subtitleTracks.isEmpty {
                DelayDialog(title: "Ritardo sottotitoli", valueMs: vm.subtitleDelayMs, step: 100, onChange: { vm.setSubtitleDelay($0) }) { vm.showSyncPanel = false }
            }
        }
    }
}

/// Subtitle track and style on tvOS with the custom (VLC) player: same menu as the iOS player.
private struct TVSubtitlePanel: View {
    @Bindable var vm: PlayerViewModel
    @State private var page: PlayerSheet = .text

    var body: some View {
        let close = { vm.showSubtitlePanel = false }
        switch page {
        case .textTrack:
            OptionsDialog(title: "Sottotitoli",
                          options: [("Disattivati", !vm.subtitleTracks.contains { $0.isSelected })] + vm.subtitleTracks.map { ($0.name, $0.isSelected) },
                          onSelect: { i in vm.selectSubtitle(i == 0 ? nil : vm.subtitleTracks[i - 1]); page = .text }, onDismiss: { page = .text })
        case .subSize:
            OptionsDialog(title: "Dimensione", options: SubtitleChoices.scales.map { ("\(Int($0 * 100))%", $0 == vm.subtitleStyle?.scale) },
                          onSelect: { vm.updateSubtitleStyle(scale: SubtitleChoices.scales[$0]); page = .text }, onDismiss: { page = .text })
        case .subColor:
            OptionsDialog(title: "Colore", options: SubtitleChoices.colors.map { (SubtitleChoices.colorName($0), $0 == vm.subtitleStyle?.color) },
                          onSelect: { vm.updateSubtitleStyle(color: SubtitleChoices.colors[$0]); page = .text }, onDismiss: { page = .text })
        case .subBackground:
            OptionsDialog(title: "Sfondo del testo", options: SubtitleChoices.backgrounds.map { (SubtitleChoices.backgroundName($0), $0 == vm.subtitleStyle?.background) },
                          onSelect: { vm.updateSubtitleStyle(background: SubtitleChoices.backgrounds[$0]); page = .text }, onDismiss: { page = .text })
        default:
            let st = vm.subtitleStyle
            let current = vm.subtitleTracks.first { $0.isSelected }?.name ?? "Disattivati"
            let entries: [(String, PlayerSheet?)] =
                (vm.subtitleTracks.isEmpty ? [("Nessun sottotitolo in questo contenuto", nil)] : [("Lingua sottotitoli: \(current)", .textTrack)])
                + [("Dimensione: \(Int((st?.scale ?? 1) * 100))%", .subSize),
                   ("Colore: \(SubtitleChoices.colorName(st?.color ?? .white))", .subColor),
                   ("Sfondo del testo: \(SubtitleChoices.backgroundName(st?.background ?? .none))", .subBackground)]
            MenuDialog(title: "Sottotitoli", items: entries.map(\.0), onSelect: { i in if let next = entries[i].1 { page = next } }, onDismiss: close)
        }
    }
}

/// Remote-friendly controls for the VLC engine (Android TV overlay layout).
private struct TVCustomPlayer: View {
    @Bindable var vm: PlayerViewModel
    let engine: PlayerEngine
    let close: () -> Void

    var body: some View {
        ZStack(alignment: .bottom) {
            EngineView(engine: engine).id(vm.engineGeneration).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 18) {
                Text(vm.title).font(AppFont.titleLarge).foregroundStyle(.white)
                if !vm.isLive && vm.duration > 0 {
                    HStack {
                        Text(Fmt.clock(vm.currentTime)).monospacedDigit()
                        ProgressBar(progress: vm.currentTime / max(vm.duration, 1), height: 6)
                        Text(Fmt.clock(vm.duration)).monospacedDigit()
                    }
                    .font(AppFont.labelLarge).foregroundStyle(.white)
                }
                HStack(spacing: 30) {
                    if vm.isLive {
                        RoundIconButton(symbol: "chevron.up", label: "Canale successivo", size: 76, tint: .white) { vm.zap(1) }
                        playPause
                        RoundIconButton(symbol: "chevron.down", label: "Canale precedente", size: 76, tint: .white) { vm.zap(-1) }
                        RoundIconButton(symbol: "list.bullet", label: "Canali", size: 76, tint: .white) { vm.showChannelList = true }
                    } else {
                        RoundIconButton(symbol: "gobackward.10", label: "Indietro 10 secondi", size: 76, tint: .white) { vm.seek(by: -10) }
                        playPause
                        RoundIconButton(symbol: "goforward.10", label: "Avanti 10 secondi", size: 76, tint: .white) { vm.seek(by: 10) }
                    }
                    RoundIconButton(symbol: "captions.bubble", label: "Sottotitoli", size: 76, tint: .white) { vm.showSubtitlePanel = true }
                    RoundIconButton(symbol: "waveform", label: "Sincronizzazione audio", size: 76, tint: .white) { vm.showSyncPanel = true }
                    RoundIconButton(symbol: "chart.bar.xaxis", label: "Statistiche", size: 76, tint: .white) { vm.showStats.toggle() }
                    RoundIconButton(symbol: "xmark", label: "Chiudi", size: 76, tint: .white, action: close)
                }
            }
            .padding(60)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        }
        .onPlayPauseCommand { vm.togglePlay() }
        .onMoveCommand { direction in
            vm.interaction()
            guard vm.isLive else { return }
            switch direction {
            case .up: vm.zap(1)
            case .down: vm.zap(-1)
            case .left: vm.showChannelList = true
            default: break
            }
        }
        .onExitCommand(perform: close)
    }

    @ViewBuilder private var playPause: some View {
        if vm.spinnerVisible {
            ProgressView().controlSize(.large).tint(Theme.primary).frame(width: 76, height: 76)
        } else {
            RoundIconButton(symbol: vm.isPlaying ? "pause.fill" : "play.fill", label: "Riproduci", size: 76,
                            background: .white.opacity(0.16), tint: .white) { vm.togglePlay() }
        }
    }
}
#endif
