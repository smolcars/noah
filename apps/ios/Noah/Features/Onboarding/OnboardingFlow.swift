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
                Text(errorMessage)
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
                errorMessage = String(localized: "Existing wallet data is incomplete. Restore your wallet to continue.")
            } catch {
                errorMessage = String(localized: "Creation Failed. Please try again.")
            }
        }
    }
}

private struct MnemonicView: View {
    let onContinue: () -> Void

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
                    NoahButton(title: copied ? "Copied" : "Copy Seed Phrase", kind: .secondary, action: copy)
                    NoahButton(title: "I Have Saved It, Continue", action: onContinue)
                        .disabled(words.isEmpty)
                }
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background(NoahColor.background.ignoresSafeArea())
        .navigationTitle("Your Recovery Phrase")
        .navigationBarTitleDisplayMode(.large)
        .navigationBarBackButtonHidden()
        .task { words = model.currentMnemonicWords() }
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
