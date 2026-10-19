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
                comingSoon("Show VTXOs", "VTXOs are to Ark like UTXOs are to Bitcoin")
                comingSoon("Emergency Exit", "Recover funds if the Ark server is unavailable.")
                comingSoon("Recurring Payments", "Schedule automatic weekly or monthly payments, and pause or cancel them.")
                row("Backup & Restore", "Automatically or manually backup your wallet after encrypting it.") { BackupSettingsView() }
                comingSoon("Board to Ark", "Manually move onchain bitcoin into Ark.")
                Toggle(isOn: Binding(get: { transaction.isAutoBoardingEnabled }, set: setAutoBoard)) {
                    SettingsLabel(title: "Auto-Board to Ark", detail: threshold.map {
                        "Ask to board to Ark when onchain balance can cover \(max($0, 20_000)) sats, estimated fees, and a 5,000 sats reserve."
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
                comingSoon("Reset Server Registration", "Attempt to reset the connection with our server if you're experiencing issues.")
                row("Send Feedback", "Report bugs or share feedback with the Noah team") { FeedbackView() }
                if wallet.isDebugModeEnabled == true {
                    row("Debug Screen", "Advanced debug actions for developers") { DebugView() }
                }
            }
            Section("Danger Zone") {
                comingSoon("Suspend Wallet", "Disable all wallet operations. The wallet will be closed and won't load until re-enabled.")
                row("Export Database", "Create an encrypted backup file containing your wallet database.") { ExportDatabaseView() }
                comingSoon("Delete Wallet", "Coming soon")
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
        .task(id: taps) {
            guard taps > 0 else { return }
            do { try await Task.sleep(for: .seconds(2)); taps = 0 } catch {}
        }
        .task { threshold = try? await model.session.withOpenWallet { try await $0.wallet.arkInfo()?.minBoardAmountSats } }
    }

    private func row<Content: View>(_ title: String, _ detail: String, @ViewBuilder destination: () -> Content) -> some View {
        NavigationLink(destination: destination) { SettingsLabel(title: title, detail: detail) }
    }
    private func comingSoon(_ title: String, _ detail: String) -> some View {
        SettingsLabel(title: title, detail: detail == "Coming soon" ? detail : detail + "\nComing soon")
            .foregroundStyle(NoahColor.mutedForeground).accessibilityAddTraits(.isStaticText)
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

