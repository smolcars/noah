import SwiftUI

/// Welcome → beta → Creating → Name → Email → Notifications → Done, or
/// Welcome → Restore → Restoring. One tree scene sits behind every screen,
/// so it carries across Welcome → Creating without a jump. All state lives
/// here, so folding, unfolding or rotating keeps it.
struct OnboardingFlow: View {
    enum Step: Hashable {
        case welcome, creating, name, email, notifications, done, restore, restoring
        /// Reinstall recovery with the phrase still in the Keychain.
        case storedSeed

        /// An interrupted onboarding (wallet exists, not initialized) resumes at Name.
        static func initial(resuming: Bool, storedSeed: Bool) -> Self {
            storedSeed ? .storedSeed : resuming ? .name : .welcome
        }

        var scene: TreeScene.Mode? {
            switch self {
            case .welcome: .welcome
            case .creating: .create
            case .restoring: .restore
            case .notifications: .conveyor
            default: nil
            }
        }
    }

    private enum Sheet { case beta, language }
    private enum Travel { case forward, back, fade }

    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @Environment(\.locale) private var locale

    @State private var step: Step
    @State private var travel = Travel.forward
    @State private var scene: TreeScene.State
    @State private var layout = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var haptics = OnboardingHaptics()
    @State private var sheet: Sheet?
    @State private var sheetHeight: CGFloat = 0
    @State private var creating = false
    @State private var creationError: String?
    @State private var phase = ""
    @State private var name: NameAvailability
    @State private var savingName = false
    @State private var nameError: String?
    @State private var savedAddress: String?
    @State private var email: EmailVerification
    @State private var notificationsRequired = false
    @State private var requestingNotifications = false
    @State private var finishing = false
    @State private var draft = RestoreDraft()
    @State private var clipboard = RestorePasteboard()
    @State private var restoreStart = Date()
    @State private var restoreCount: OnboardingProgressScreen.Count?

    init(model: AppModel, storedSeed: Bool = false) {
        let first = Step.initial(resuming: model.resumeOnboarding, storedSeed: storedSeed)
        _step = State(initialValue: first)
        _scene = State(initialValue: TreeScene.State(mode: first.scene ?? .welcome, start: .now))
        _name = State(initialValue: NameAvailability(lookup: NameAvailability.liveLookup(variant: model.variant)))
        let server = model.server
        _email = State(initialValue: EmailVerification(send: { try await server.sendEmail($0) },
                                                       verify: { try await server.verifyEmail(code: $0, email: $1) }))
    }

    var body: some View {
        ZStack {
            NoahTokens.Colors.background.ignoresSafeArea()
            TreeSceneView(state: scene, visible: step.scene != nil, placement: layout.scene(scene.mode))
                .ignoresSafeArea()
            ZStack {
                screen
                    .id(step)
                    .transition(transition)
            }
            if layout.wide, let sheet {
                OnboardingPaneSheet(pane: CGRect(x: 0, y: 0, width: layout.size.width / 2 - layout.insets.trailing, height: layout.size.height),
                                    onDismiss: creating ? nil : { self.sheet = nil }) {
                    sheetContent(sheet)
                }
                .transition(.opacity)
                .zIndex(2)
            }
        }
        .onboardingWindow($layout)
        .sheet(isPresented: Binding(get: { sheet != nil && !layout.wide },
                                    set: { if !$0 && !layout.wide && !creating { sheet = nil } })) {
            sheetContent(sheet ?? .beta)
                .padding(.horizontal, NoahTokens.Space.screen)
                .padding(.top, 30)
                .padding(.bottom, NoahTokens.Space.l)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { sheetHeight = $0 }
                .presentationDetents([.height(max(sheetHeight, 240))])
                // Opaque: glass never sits behind paragraphs of text.
                .presentationBackground(NoahTokens.Colors.sheet)
                .presentationDragIndicator(.visible)
                .interactiveDismissDisabled(creating)
        }
        .onboardingHaptics(haptics)
        .task(id: step) { await run(step) }
        .onChange(of: phase) { _, phase in
            guard !phase.isEmpty else { return }
            AccessibilityNotification.Announcement(L10n.text(phase, locale: locale.identifier)).post()
        }
        .onChange(of: email.stage) { old, new in emailStageChanged(from: old, to: new) }
        .onChange(of: step) { old, _ in if old == .restore { clearDraft() } }
        .onChange(of: scenePhase) { _, phase in sceneChanged(phase) }
        .onChange(of: model.phase) { _, phase in
            // Deleting the wallet from reinstall recovery starts over.
            if phase == .onboarding && step == .storedSeed { go(.welcome, travel: .fade) }
        }
        .onAppear {
            // Nothing left to finish if this appears without a restore running.
            if model.holdsOnboardingAfterRestore && step != .restoring { model.endOnboardingAfterRestore() }
        }
    }

    // MARK: Screens

    @ViewBuilder
    private var screen: some View {
        switch step {
        case .welcome:
            WelcomeScreen(layout: layout, sheetUp: sheet != nil && !layout.wide, languageCode: model.localeIdentifier,
                          onLanguage: { sheet = .language },
                          onCreate: { haptics.play(.light); creationError = nil; sheet = .beta },
                          onRestore: { go(.restore) })
        case .creating:
            OnboardingProgressScreen(layout: layout, mode: .create, phase: phase)
        case .restoring:
            OnboardingProgressScreen(layout: layout, mode: .restore, phase: phase, count: restoreCount)
        case .name:
            NameScreen(layout: layout, name: name, domain: LightningIdentity.domain(for: model.variant),
                       saving: savingName, error: nameError, haptics: haptics, onContinue: saveName,
                       onSkip: { haptics.play(.light); savedAddress = nil; go(.email) })
        case .email:
            EmailScreen(layout: layout, email: email, haptics: haptics,
                        onSkip: { haptics.play(.light); go(.notifications) })
        case .notifications:
            NotificationsScreen(layout: layout, required: notificationsRequired, busy: requestingNotifications,
                                action: notificationsAction)
        case .done:
            DoneScreen(layout: layout, address: savedAddress, finishing: finishing, onOpen: openWallet)
        case .restore:
            RestoreWalletScreen(layout: layout, draft: $draft, issue: model.restoreIssue, haptics: haptics,
                                onPaste: { clipboard.accepted($0, change: $1) }, onRestore: restore,
                                onBack: { draft.focusedWord = nil; go(.welcome, travel: .back) })
        case .storedSeed:
            StoredSeedRestoreScreen(layout: layout, issue: model.restoreIssue) {
                haptics.play(.medium)
                startRestore(phrase: nil)
            }
        }
    }

    @ViewBuilder
    private func sheetContent(_ sheet: Sheet) -> some View {
        switch sheet {
        case .beta:
            BetaSheet(busy: creating, error: creationError, onConfirm: createWallet)
        case .language:
            LanguageSheet(selected: model.localeIdentifier) { code in
                haptics.play(.selection)
                self.sheet = nil
                model.setLanguage(code)
            }
        }
    }

    private var transition: AnyTransition {
        if reduceMotion || travel == .fade { return .opacity }
        let back = travel == .back
        return .asymmetric(insertion: .offset(x: back ? -34 : 44).combined(with: .opacity),
                           removal: .offset(x: back ? 44 : -34).combined(with: .scale(scale: back ? 1 : 0.98)).combined(with: .opacity))
    }

    private func go(_ next: Step, travel: Travel = .forward) {
        self.travel = travel
        if let mode = next.scene, mode != scene.mode || step.scene == nil || next == .welcome {
            scene = TreeScene.State(mode: mode, start: .now)
        }
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) { step = next }
    }

    // MARK: Steps

    private func run(_ step: Step) async {
        switch step {
        case .creating:
            let start = scene.start
            phase = "Creating your keys"
            guard await wait(until: start.addingTimeInterval(TreeScene.createJoining)) else { return }
            phase = "Joining the Ark"
            guard await wait(until: start.addingTimeInterval(TreeScene.createReady)) else { return }
            phase = "Ready"
            haptics.play(.success)
            guard await wait(until: start.addingTimeInterval(TreeScene.createDone)) else { return }
            go(.name)
        case .restoring:
            await followRestore()
        case .name:
            // Registration assigns a name; "Skip" keeps it.
            try? await model.server.ensureRegistered()
            name.assigned = model.server.state.lightningAddress?.components(separatedBy: "@").first
        case .done:
            try? await Task.sleep(for: .milliseconds(reduceMotion ? 200 : 1000))
            if !Task.isCancelled { haptics.play(.success) }
        default:
            break
        }
    }

    private func wait(until date: Date) async -> Bool {
        let delay = date.timeIntervalSinceNow
        if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
        return !Task.isCancelled
    }

    private func createWallet() {
        guard !creating else { return }
        haptics.play(.medium)
        creating = true
        creationError = nil
        Task {
            defer { creating = false }
            do {
                try await model.createWallet()
                sheet = nil
                go(.creating, travel: .fade)
            } catch WalletCreator.Failure.incompleteExistingData {
                creationError = "Existing wallet data is incomplete. Restore your wallet to continue."
            } catch {
                creationError = "Creation failed. Please try again."
            }
        }
    }

    private func saveName() {
        guard name.canContinue, !savingName else { return }
        let username = name.name
        nameError = nil
        if username == name.assigned {
            savedAddress = model.server.state.lightningAddress
            haptics.play(.medium)
            go(.email)
            return
        }
        savingName = true
        Task {
            defer { savingName = false }
            do {
                // Keep any Nostr key already linked; it is edited in Settings → Profile.
                try await model.server.saveIdentity(username: username, npub: Nostr.npub(from: model.server.state.nostrPubkey))
                savedAddress = model.server.state.lightningAddress
                haptics.play(.medium)
                go(.email)
            } catch {
                let message = ServerFailure.message(for: error, fallback: "Failed to update Lightning address. Please try again.")
                if message == #"Lightning address already taken"# { // The server's message, not UI copy.
                    name.markTaken()
                } else {
                    nameError = message
                    haptics.play(.error)
                }
            }
        }
    }

    private func emailStageChanged(from old: EmailVerification.Stage, to new: EmailVerification.Stage) {
        switch new {
        case .code where old == .sending: haptics.play(.success)
        case .wrongCode: haptics.play(.error)
        case .verified:
            haptics.play(.success)
            Task {
                try? await Task.sleep(for: .seconds(1.5))
                if step == .email { go(.notifications) }
            }
        default: break
        }
    }

    private func notificationsAction() {
        if notificationsRequired {
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            return
        }
        guard !requestingNotifications else { return }
        requestingNotifications = true
        Task {
            await model.pushPermission.retry(request: true)
            requestingNotifications = false
            switch model.pushPermission.onboardingStep {
            case .proceed: notificationsGranted()
            case .required:
                haptics.play(.warning)
                scene.frozenAt = Date().timeIntervalSince(scene.start)
                notificationsRequired = true
            case .ask: break // The request didn't settle; the button asks again.
            }
        }
    }

    private func notificationsGranted() {
        haptics.play(.success)
        Task {
            try? await Task.sleep(for: .milliseconds(280))
            if step == .notifications { go(.done) }
        }
    }

    private func sceneChanged(_ phase: ScenePhase) {
        // The words stay while the app is in the background (a phrase read from Notes a few words at a time);
        // the shield hides them meanwhile. Leaving Restore or restoring clears them.
        if phase != .active && step == .restore { clipboard.clearIfUnchanged() }
        guard phase == .active, step == .notifications, notificationsRequired else { return }
        Task {
            await model.pushPermission.refresh()
            guard model.pushPermission.onboardingStep == .proceed, notificationsRequired else { return }
            notificationsRequired = false
            scene = TreeScene.State(mode: .conveyor, start: .now)
            notificationsGranted()
        }
    }

    private func openWallet() {
        guard !finishing else { return }
        finishing = true
        haptics.play(.light)
        Task { await model.finishOnboarding() }
    }

    // MARK: Restore

    private func clearDraft() {
        clipboard.clearIfUnchanged()
        draft = RestoreDraft(generation: draft.generation + 1)
    }

    private func restore() {
        draft.focusedWord = nil
        guard draft.canRestore else { return }
        guard RecoveryPhraseInput.isValid(draft.input.phrase) else {
            draft.invalidPhrase = true
            draft.shakes += 1
            haptics.play(.error)
            return
        }
        haptics.play(.medium)
        let phrase = draft.input.phrase
        clearDraft()
        startRestore(phrase: phrase)
    }

    /// One closed-session restore; Restoring follows its steps.
    private func startRestore(phrase: String?) {
        guard !model.isRestoring else { return }
        restoreStart = .now
        restoreCount = model.isBalanceHidden ? nil : .init(total: 0, lit: [], reveal: nil, unit: model.amountUnit)
        phase = "Restoring your wallet"
        go(.restoring)
        Task {
            await model.restoreWallet(phrase: phrase)
            if model.phase == .wallet {
                let total = Int64(clamping: model.summary?.balance.totalSats ?? 0)
                let lit = total > 0 ? TreeScene.found.map(\.leaf) : [TreeScene.you]
                let now = Date()
                scene.reveal = now
                scene.lit = lit
                if restoreCount != nil { restoreCount = .init(total: total, lit: lit, reveal: now, unit: model.amountUnit) }
                phase = "Finding your bitcoin"
            } else {
                go(phrase == nil ? .storedSeed : .restore, travel: .back)
            }
        }
    }

    /// Phase text and haptics from the real restore steps, then the closing sweep.
    private func followRestore() async {
        var lit: Set<Int> = [], ready = false
        while !Task.isCancelled {
            let now = Date()
            if let reveal = scene.reveal {
                let elapsed = now.timeIntervalSince(reveal)
                for leaf in scene.lit where !lit.contains(leaf) && elapsed >= TreeScene.foundAt(leaf) {
                    lit.insert(leaf)
                    haptics.play(.light)
                }
                if !ready && elapsed >= TreeScene.restoreReady {
                    ready = true
                    phase = "Ready"
                    haptics.play(.success)
                }
                if elapsed >= TreeScene.restoreDone {
                    model.endOnboardingAfterRestore()
                    return
                }
            } else if phase == "Restoring your wallet", now.timeIntervalSince(restoreStart) >= TreeScene.restoreFinding,
                      let restoreStep = model.restoreStep, restoreStep != .fetching {
                phase = "Finding your bitcoin"
            }
            try? await Task.sleep(for: .milliseconds(50))
        }
    }
}

struct PendingFeatureView: View {
    let title: LocalizedStringKey

    var body: some View {
        ContentUnavailableView(title, systemImage: "hammer", description: Text("Coming soon in the native app."))
            .background(NoahColor.background.ignoresSafeArea())
    }
}
