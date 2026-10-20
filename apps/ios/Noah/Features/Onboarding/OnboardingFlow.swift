import SwiftUI

/// Welcome → beta → recovery phrase → emergency email → Lightning address → Home.
struct OnboardingFlow: View {
    enum Step: Hashable {
        case beta, mnemonic, email, lightning, restore
    }

    @Environment(AppModel.self) private var model
    @State private var path: [Step] = []

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView(onCreate: { path.append(.beta) }, onRestore: { path.append(.restore) })
                .navigationDestination(for: Step.self) { step in
                    switch step {
                    case .beta:
                        BetaWarningView(onCreated: { path.append(.mnemonic) }, onDecline: { path.removeLast() })
                    case .mnemonic:
                        MnemonicView(onContinue: { path.append(.email) })
                    case .email:
                        EmergencyEmailView(fromOnboarding: true, onContinue: { path.append(.lightning) })
                    case .lightning:
                        LightningAddressView(onContinue: { Task { await model.finishOnboarding() } })
                    case .restore:
                        RestoreWalletScreen()
                    }
                }
        }
        .tint(NoahColor.brand)
        .onAppear {
            if model.resumeOnboarding && path.isEmpty { path = [.mnemonic] }
        }
    }
}

private struct WelcomeView: View {
    let onCreate: () -> Void
    let onRestore: () -> Void

    var body: some View {
        VStack(spacing: 32) {
            Spacer()
            VStack(spacing: 12) {
                Text("Welcome to Noah")
                    .font(NoahFont.largeTitle)
                    .foregroundStyle(NoahColor.foreground)
                Text("Create a new wallet or restore an existing one.")
                    .font(NoahFont.body)
                    .foregroundStyle(NoahColor.mutedForeground)
                    .multilineTextAlignment(.center)
            }
            VStack(spacing: 12) {
                NoahButton(title: "Create Wallet", action: onCreate)
                NoahButton(title: "Restore Wallet", kind: .secondary, action: onRestore)
            }
            Spacer()
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(NoahColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}

private struct BetaWarningView: View {
    @Environment(\.locale) private var locale

    let onCreated: () -> Void
    let onDecline: () -> Void

    @Environment(AppModel.self) private var model
    @State private var isCreating = false
    @State private var errorMessage: String?

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(.orange)
                .frame(width: 80, height: 80)
                .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            VStack(spacing: 12) {
                Text("Noah is in beta")
                    .font(NoahFont.largeTitle)
                    .foregroundStyle(NoahColor.foreground)
                Text("Noah is still in beta. There is a possibility you could lose money. Only deposit funds you are willing to lose.")
                    .font(NoahFont.body)
                    .foregroundStyle(NoahColor.mutedForeground)
                    .multilineTextAlignment(.center)
            }
            if let errorMessage {
                Text(LocalizedStringKey(errorMessage))
                    .font(NoahFont.footnote)
                    .foregroundStyle(NoahColor.destructive)
                    .multilineTextAlignment(.center)
            }
            VStack(spacing: 12) {
                NoahButton(title: "I accept", isLoading: isCreating, action: accept)
                NoahButton(title: "I decline", kind: .secondary, action: onDecline)
                    .disabled(isCreating)
            }
            Spacer()
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(NoahColor.background.ignoresSafeArea())
        .navigationBarBackButtonHidden(isCreating)
    }

    private func accept() {
        guard !isCreating else { return }
        isCreating = true
        errorMessage = nil
        Task {
            defer { isCreating = false }
            do {
                try await model.createWallet()
                onCreated()
            } catch WalletCreator.Failure.incompleteExistingData {
                errorMessage = L10n.text("Existing wallet data is incomplete. Restore your wallet to continue.", locale: locale.identifier)
            } catch {
                errorMessage = L10n.text("Creation Failed. Please try again.", locale: locale.identifier)
            }
        }
    }
}

struct MnemonicView: View {
    @Environment(\.locale) private var locale

    var onContinue: (() -> Void)? = nil
    @Environment(WalletLock.self) private var lock
    @Environment(\.scenePhase) private var scenePhase
    @State private var authenticated = false
    @State private var warningGeneration: Int?

    @Environment(AppModel.self) private var model
    @State private var words: [String] = []
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("Write down these \(words.count) words in order and store them in a safe place. This is the only way to recover your wallet.")
                    .font(NoahFont.body)
                    .foregroundStyle(NoahColor.mutedForeground)

                NoahCard {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 14) {
                        ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                            HStack(spacing: 10) {
                                Text("\(index + 1)")
                                    .font(NoahFont.inter(13, .medium))
                                    .foregroundStyle(NoahColor.mutedForeground)
                                    .frame(minWidth: 22, alignment: .trailing)
                                Text(word)
                                    .font(NoahFont.inter(17, .medium))
                                    .foregroundStyle(NoahColor.foreground)
                            }
                        }
                    }
                }
                .secretShield()

                VStack(spacing: 12) {
                    if onContinue == nil && !authenticated {
                        NoahButton(title: "Authenticate to view your seed phrase") { Task { await reveal() } }
                        if let error = lock.error { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) }
                    }
                    NoahButton(title: copied ? "Copied" : "Copy Seed Phrase", kind: .secondary, action: copy)
                        .disabled(words.isEmpty)
                    if let onContinue {
                        NoahButton(title: "I Have Saved It, Continue", action: onContinue).disabled(words.isEmpty)
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background(NoahColor.background.ignoresSafeArea())
        .navigationTitle("Your Recovery Phrase")
        .navigationBarTitleDisplayMode(.large)
        .navigationBarBackButtonHidden(onContinue != nil)
        .alert("No screen lock", isPresented: Binding(get: { warningGeneration != nil }, set: { if !$0 { warningGeneration = nil } })) {
            Button("Cancel", role: .cancel) { warningGeneration = nil }
            Button("Show recovery phrase") {
                let generation = warningGeneration
                warningGeneration = nil
                guard generation == lock.policy.generation, scenePhase == .active else { return }
                if WalletLock.hasNoScreenLock { showWords() }
                else { Task { await reveal() } }
            }
        } message: {
            Text("This device has no screen lock. Anyone with access to it can view your recovery phrase. Set a screen lock in device settings to protect your wallet.")
        }
        .task { await reveal() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { words = []; authenticated = false; copied = false; warningGeneration = nil }
            if phase == .active && onContinue != nil && words.isEmpty { Task { await reveal() } }
        }
        .onDisappear { words = []; authenticated = false; warningGeneration = nil }
    }

    private func reveal() async {
        let generation = lock.policy.generation
        let result = onContinue != nil ? RecoveryPhraseAuthentication.authenticated
            : await lock.authenticateRecoveryPhrase(L10n.text("Authenticate to view your seed phrase", locale: locale.identifier))
        guard generation == lock.policy.generation, scenePhase != .background else { return }
        switch result {
        case .authenticated: showWords()
        case .noScreenLock: warningGeneration = generation
        case .denied: break
        }
    }

    private func showWords() {
        authenticated = true
        words = model.currentMnemonicWords()
    }

    /// Local-only pasteboard item that expires, so the phrase does not sync
    /// through Universal Clipboard or linger.
    private func copy() {
        UIPasteboard.general.setItems(
            [[UIPasteboard.typeAutomatic: words.joined(separator: " ")]],
            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
        copied = true
    }
}

struct PendingFeatureView: View {
    let title: LocalizedStringKey

    var body: some View {
        ContentUnavailableView(title, systemImage: "hammer", description: Text("Coming soon in the native app."))
            .background(NoahColor.background.ignoresSafeArea())
    }
}
