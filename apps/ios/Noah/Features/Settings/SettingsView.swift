import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(WalletLock.self) private var lock
    @State private var wallet = SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
    @State private var transaction = PersistedTransactionState.load(from: .shared)
    @State private var taps = 0
    @State private var lastTap = Date.distantPast
    @State private var busy = false
    @State private var error: String?
    @State private var threshold: UInt64?
    @State private var confirmingReset = false
    @State private var resetDone = false
    @State private var showingDelete = false

    var body: some View {
        List {
            NavigationLink { NoahStoryView() } label: {
                Image("SplashScreenLogo").resizable().scaledToFit().frame(height: 100).frame(maxWidth: .infinity)
                    .accessibilityLabel("Noah's Ark Story")
            }
            Section("Account") {
                row("Profile", "Manage your name, Lightning address, emergency email, and public key.") { ProfileView() }
                row("Language", "Choose the language used in Noah.") { PreferenceView(kind: .language) }
                row("Currency", "Choose the fiat currency used for balances and payment amounts.") { PreferenceView(kind: .currency) }
                row("Bitcoin Unit", "Choose how bitcoin amounts are displayed.") { PreferenceView(kind: .unit) }
            }
            Section("Info") {
                row("Ark Info", "View Ark server, wallet, and explorer configuration.") { ArkInfoView() }
            }
            Section("Wallet") {
                row("Show Seed Phrase", "Never share your seed phrase with anyone. It is important to keep it safe and secure.") { MnemonicView() }
                row("Show VTXOs", "VTXOs are to Ark like UTXOs are to Bitcoin") { VtxosView() }
                row("Emergency Exit", "Recover funds if the Ark server is unavailable.") { UnilateralExitView() }
                row("Recurring Payments", "Schedule automatic weekly or monthly payments, and pause or cancel them.") { RecurringPaymentsView() }
                row("Backup & Restore", "Automatically or manually backup your wallet after encrypting it.") { BackupSettingsView() }
                row("Board to Ark", "Manually move onchain bitcoin into Ark.") { BoardArkView() }
                Toggle(isOn: Binding(get: { transaction.isAutoBoardingEnabled }, set: setAutoBoard)) {
                    SettingsLabel(title: "Auto-Board to Ark", detail: threshold.map {
                        "Ask to board to Ark when onchain balance can cover \(max($0, 20_000)) sats plus fees while leaving at least 5,000 sats onchain. Confirm the exact fees and reserve before boarding."
                    } ?? "Auto-board threshold unavailable")
                }
                if WalletLock.available || lock.policy.enabled {
                    Toggle(isOn: Binding(get: { lock.policy.enabled }, set: { value in
                        run { try await lock.setEnabled(value) }
                    })) {
                        SettingsLabel(title: "Biometric Authentication", detail: "Require biometric authentication to unlock your wallet")
                    }.disabled(busy)
                }
                Toggle(isOn: Binding(get: { model.server.state.isMailboxAuthorizationEnabled != false }, set: { value in
                    run { try await model.server.setMailboxEnabled(value) }
                })) {
                    SettingsLabel(title: "Mailbox Notifications", detail: "Allow Noah to monitor your Ark mailbox so it can wake this app to claim Lightning payments in the background.")
                }.disabled(busy)
            }
            Section("Debug") {
                row("Show Logs", "View application logs for debugging purposes") { LogView() }
                Button { confirmingReset = true } label: {
                    SettingsLabel(title: "Reset Server Registration", detail: "Clear your registration with the server. You will need to register again.")
                }
                .disabled(busy || model.isWalletSuspended)
                row("Send Feedback", "Report bugs or share feedback with the Noah team") { FeedbackView() }
                if wallet.isDebugModeEnabled == true {
                    row("Debug Screen", "Advanced debug actions for developers") { DebugView() }
                }
            }
            Section("Danger Zone") {
                Toggle(isOn: Binding(get: { model.isWalletSuspended }, set: { suspend in
                    run { suspend ? try await model.suspendWallet() : try await model.resumeWallet() }
                })) {
                    SettingsLabel(title: "Suspend Wallet", detail: "Disable all wallet operations. The wallet will be closed and won't load until re-enabled.")
                }.disabled(busy)
                row("Export Database", "Create an encrypted backup file containing your wallet database.") { ExportDatabaseView() }
                Button(role: .destructive) { showingDelete = true } label: {
                    SettingsLabel(title: "Delete Wallet", detail: "Permanently remove this wallet and its recovery phrase from this device.")
                        .foregroundStyle(NoahColor.destructive)
                }
                .disabled(busy)
            }
            Section {
                Button {
                    guard wallet.isDebugModeEnabled != true else { return }
                    taps = Date().timeIntervalSince(lastTap) < 2 ? taps + 1 : 1
                    lastTap = Date()
                    if taps >= 5 {
                        wallet = SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
                        wallet.isDebugModeEnabled = true
                        do { try SettingsStorage.shared.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version) }
                        catch { self.error = "Couldn't save debug setting." }
                        taps = 0
                    }
                } label: {
                    Text("v\(AppServices.shared.appVersion)" + (taps > 0 ? " (\(5 - taps) taps to unlock debug)" : ""))
                }
                Text("Made with ❤️ from Noah team").font(NoahFont.footnote)
                Link("Open Telegram support chat", destination: URL(string: "https://t.me/blixtwallet/605026")!)
                Link("Open Noah GitHub repository", destination: URL(string: "https://github.com/smolcars/noah")!)
            }
            if let error { Text(error).foregroundStyle(NoahColor.destructive) }
        }
        .settingsStyle("Settings")
        .alert("Reset Server Registration", isPresented: $confirmingReset) {
            Button("Cancel", role: .cancel) {}
            Button("Reset", role: .destructive) {
                run {
                    try await model.reRegisterWithServer()
                    resetDone = true
                }
            }
        } message: {
            Text("Are you sure you want to reset your server registration? This will not delete your wallet, but you will need to register with the server again.")
        }
        .alert("Registered with the server again", isPresented: $resetDone) { Button("OK") {} }
        .sheet(isPresented: $showingDelete) { DeleteWalletSheet() }
        .task(id: taps) {
            guard taps > 0 else { return }
            do { try await Task.sleep(for: .seconds(2)); taps = 0 } catch {}
        }
        .task(id: model.isWalletSuspended) {
            guard !model.isWalletSuspended else { return }
            threshold = try? await model.session.boardingMinimum()
        }
    }

    private func row<Content: View>(_ title: String, _ detail: String, @ViewBuilder destination: () -> Content) -> some View {
        NavigationLink(destination: destination) { SettingsLabel(title: title, detail: detail) }
    }
    private func setAutoBoard(_ enabled: Bool) {
        transaction.setAutoBoarding(enabled)
        do { try SettingsStorage.shared.setPersistedState(transaction, key: PersistedTransactionState.key, version: PersistedTransactionState.version) }
        catch { self.error = "Couldn't save auto-board setting." }
    }
    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }; busy = true; error = nil
        Task {
            defer { busy = false }
            do { try await action() } catch { self.error = "Couldn't update this setting. Please try again." }
        }
    }
}

/// RN's delete confirmation: type "delete" to enable the button.
struct DeleteWalletSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmation = ""
    @State private var deleting = false
    @State private var failure: String?

    private var confirmed: Bool { confirmation.trimmingCharacters(in: .whitespaces).lowercased() == "delete" }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("This action is irreversible. Your wallet and its recovery phrase will be removed from this device. Make sure you have your recovery phrase or a backup before you continue.")
                    .font(NoahFont.body)
                    .foregroundStyle(NoahColor.mutedForeground)
                Text("To confirm, please type \"delete\" in the box below.")
                    .font(NoahFont.body)
                TextField("delete", text: $confirmation)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
                if let failure {
                    Text(failure).font(NoahFont.footnote).foregroundStyle(NoahColor.destructive)
                }
                Button(role: .destructive) {
                    deleting = true
                    failure = nil
                    Task {
                        do {
                            try await model.deleteWallet()
                            dismiss()
                        } catch {
                            failure = (error as? WalletLifecycle.Failure)?.errorDescription
                                ?? "The wallet couldn't be deleted. Restart Noah and try again."
                            deleting = false
                        }
                    }
                } label: {
                    Group {
                        if deleting { ProgressView() } else { Text("Delete Wallet").font(NoahFont.headline) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(NoahColor.destructive)
                .disabled(!confirmed || deleting)
                Spacer()
            }
            .padding(24)
            .navigationTitle("Delete Wallet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(deleting) } }
        }
        .interactiveDismissDisabled(deleting)
    }
}
