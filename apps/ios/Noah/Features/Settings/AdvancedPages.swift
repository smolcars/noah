import SwiftUI
import UIKit
import UserNotifications

/// App → Notifications: whether notifications are on, the mailbox switch, and the way to iOS Settings.
/// The launch push gate is unchanged.
struct NotificationsPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var on: Bool?
    @State private var busy = false
    @State private var error: String?

    private var language: String { locale.identifier }

    var body: some View {
        SettingsScroll {
            if let on {
                SettingsStatusHeader(mark: on ? .on("bell") : .failedIcon,
                                     title: L10n.text(on ? "On" : "Off", locale: language),
                                     detail: L10n.text(on ? "Noah can receive and renew while closed."
                                                       : "Noah can’t receive payments or renew your bitcoin.", locale: language),
                                     failure: !on)
            }
            SettingsGroup {
                SettingsToggleRow(icon: "bolt", title: L10n.text("Claim payments in the background", locale: language),
                                  sub: L10n.text("Lets Noah wake up for Lightning", locale: language),
                                  isOn: Binding(get: { app.server.state.isMailboxAuthorizationEnabled != false }, set: setMailbox),
                                  id: "settings-mailbox")
                SettingsRow(icon: "gearshape", title: L10n.text("System settings", locale: language), trailing: .external, hairline: false,
                            id: "settings-system-notifications", action: openSystemSettings)
            }
            .disabled(busy)
            if on == false {
                SettingsFine(text: L10n.text("Turn them on in Settings. Noah checks again when you come back.", locale: language))
            }
            if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
        }
        .settingsChrome("Notifications")
        .settingsFooter {
            if on == false {
                Button(L10n.text("Open Settings", locale: language), action: openSystemSettings)
                    .buttonStyle(SettingsActionStyle())
            }
        }
        .task(id: scenePhase == .active) {
            guard scenePhase == .active else { return }
            let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            on = [.authorized, .provisional, .ephemeral].contains(status)
        }
    }

    private func setMailbox(_ enabled: Bool) {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do { try await app.server.setMailboxEnabled(enabled) } catch { self.error = "Couldn't update mailbox notifications." }
        }
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
    }
}

private extension SettingsStatusMark.Kind {
    static let failedIcon = SettingsStatusMark.Kind.idle("bell.slash")
}

/// App → Advanced: the Ark server and Esplora, logs, reset registration, debug, and suspend.
struct AdvancedPage: View {
    private enum Sheet: String, Identifiable { case reset, suspend; var id: Self { self } }

    @Environment(AppModel.self) private var app
    @Environment(SettingsSummary.self) private var summary
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var sheet: Sheet?
    @State private var busy = false
    @State private var error: String?
    @State private var haptics = OnboardingHaptics()
    @State private var esplora = EsploraSettings().selection(for: .current)

    private var language: String { locale.identifier }
    private var configuration: WalletConfiguration { .effective(for: .current, endpoint: esplora.endpoint) }

    var body: some View {
        SettingsScroll {
            SettingsGroup(label: "Network", first: true) {
                row("Ark server", icon: "server.rack", value: URL(string: configuration.arkServer)?.host() ?? configuration.arkServer, page: .arkServer,
                    id: "settings-ark-server")
                row("Esplora", icon: "link", value: AppVariant.current == .regtest ? "Bitcoin Core"
                        : esplora.endpoint == nil ? L10n.text("Noah default", locale: language) : L10n.text("Custom", locale: language),
                    page: .esplora, last: true, id: "settings-esplora")
            }
            SettingsGroup(label: "Diagnostics") {
                row("Logs", icon: "doc.text", page: .logs, id: "settings-logs")
                SettingsRow(icon: "arrow.clockwise", title: L10n.text("Reset registration", locale: language), hairline: summary.debug,
                            id: "settings-reset-registration") {
                    haptics.play(.light)
                    sheet = .reset
                }
                .disabled(busy || app.isWalletSuspended)
                if summary.debug {
                    row("Debug", icon: "ladybug", page: .debug, id: "settings-debug")
                    // Off hides Debug again; five taps on the version bring it back.
                    SettingsToggleRow(icon: "hammer", title: L10n.text("Debug mode", locale: language),
                                      isOn: Binding(get: { summary.debug }, set: setDebug), hairline: false, id: "settings-debug-mode")
                }
            }
            SettingsGroup(label: "Wallet") {
                SettingsToggleRow(icon: "stop.circle", title: L10n.text("Suspend wallet", locale: language),
                                  sub: L10n.text("Pauses all wallet activity", locale: language),
                                  isOn: Binding(get: { app.isWalletSuspended }, set: suspendSwitched), hairline: false, id: "settings-suspend")
                    .disabled(busy)
            }
            if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
        }
        .settingsChrome("Advanced")
        .onboardingHaptics(haptics)
        .onAppear { esplora = EsploraSettings().selection(for: .current) }
        .settingsSheet(item: $sheet, locked: busy) { sheet in
            switch sheet {
            case .reset:
                SettingsSheetBody(title: L10n.text("Reset registration?", locale: language),
                                  line: L10n.text("Noah registers this wallet with the server again. Your wallet stays.", locale: language)) {
                    EmptyView()
                } actions: {
                    SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Reset", locale: language), busy: busy,
                                         actionID: "settings-reset-confirm", onCancel: { self.sheet = nil }, onAction: reset)
                }
            case .suspend:
                SettingsSheetBody(title: L10n.text("Suspend wallet?", locale: language),
                                  line: L10n.text("Noah closes the wallet and stops payments until you turn this off.", locale: language)) {
                    EmptyView()
                } actions: {
                    SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Suspend", locale: language),
                                         destructive: true, busy: busy, actionID: "settings-suspend-confirm", onCancel: { self.sheet = nil }) {
                        haptics.play(.warning)
                        run("Couldn't change wallet suspension. Please try again.") {
                            try await app.suspendWallet()
                            self.sheet = nil
                            settings.toast("Wallet suspended")
                        }
                    }
                }
            }
        }
    }

    private func row(_ title: String, icon: String, value: String? = nil, page: SettingsPage, last: Bool = false, id: String) -> some View {
        SettingsRow(icon: icon, title: L10n.text(title, locale: language), value: value, hairline: !last, id: id) {
            haptics.play(.light)
            settings.open(page)
        }
    }

    private func reset() {
        run("Couldn't register again. Please retry.") {
            try await app.reRegisterWithServer()
            sheet = nil
            haptics.play(.success)
            settings.toast("Registered again")
        }
    }

    /// Suspending asks first; resuming doesn't.
    private func suspendSwitched(_ suspend: Bool) {
        switch SuspendSwitch.action(suspended: app.isWalletSuspended, requested: suspend) {
        case .confirm:
            haptics.play(.light)
            sheet = .suspend
        case .resume: run("Couldn't change wallet suspension. Please try again.") { try await app.resumeWallet() }
        case .none: break
        }
    }

    private func setDebug(_ on: Bool) {
        do {
            try summary.setDebug(on)
            haptics.play(.selection)
        } catch { self.error = "Couldn't save debug setting." }
    }

    private func run(_ failure: String, _ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do { try await action() } catch {
                self.error = (error as? WalletLifecycle.Failure) == .restoreSettingsUnavailable
                    ? error.localizedDescription : failure
            }
        }
    }
}
