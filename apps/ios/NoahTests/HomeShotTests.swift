import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// Opt-in screenshot fixtures for Home: the real tab shell and Home with fake
/// history, balance and status (no wallet, server writes or recovery phrase).
/// Write a state name to Documents/home-shot ("hidden-" prefix hides the
/// balance); "stop" ends the session. States: fresh, fresh-noaddress, everyday,
/// busy, suspended, rounds, error, celebrate (a first payment lands after 2.5 s), syncing (a background task runs),
/// backedup (a backup completes as the state opens).
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_HOME_SHOTS"] == "1"))
struct HomeShotTests {
    @Test(.timeLimit(.minutes(60))) func presentStates() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.keyWindow)
        let original = window.rootViewController
        let folder = URL.documentsDirectory
        let request = folder.appending(path: "home-shot"), ready = folder.appending(path: "home-shot-ready")
        let host = UIHostingController(rootView: AnyView(Color.clear))
        window.rootViewController = host
        var roots: [URL] = []
        defer {
            window.rootViewController = original
            try? FileManager.default.removeItem(at: ready)
            try? FileManager.default.removeItem(at: request)
            for root in roots { try? FileManager.default.removeItem(at: root) }
        }
        var current = ""
        let deadline = ContinuousClock.now.advanced(by: .seconds(3500))
        while ContinuousClock.now < deadline {
            let state = (try? String(contentsOf: request, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if state == "stop" { return }
            if !state.isEmpty && state != current {
                host.rootView = AnyView(Color.clear)
                try await Task.sleep(for: .milliseconds(100))
                let (view, root) = try await fixture(state)
                roots.append(root)
                host.rootView = view
                try await Task.sleep(for: .milliseconds(600))
                try state.write(to: ready, atomically: true, encoding: .utf8)
                current = state
            }
            try await Task.sleep(for: .milliseconds(150))
        }
    }

    final class Rows: @unchecked Sendable {
        var value: [HistoryTransaction] = []
    }

    static func summary(ark: UInt64, onchain: UInt64 = 0, pending: UInt64 = 0) -> WalletSession.Summary {
        .init(network: .regtest, fingerprint: "fixture",
              balance: HomeRulesTests.balance(ark: ark, onchain: onchain, onchainPending: pending), movementCount: 0)
    }

    static func at(_ days: Int, _ hour: Int, _ minute: Int) -> String {
        let calendar = Calendar.current
        let date = calendar.date(byAdding: .day, value: -days, to: calendar.startOfDay(for: Date()))
            .flatMap { calendar.date(bySettingHour: hour, minute: minute, second: 0, of: $0)
            }! // swiftlint:disable:this force_unwrapping - near-today Gregorian arithmetic stays in range
        return HistoryRules.isoDate(date)
    }

    static func row(_ id: String, _ type: String, _ amount: Int64, _ direction: String, _ date: String,
                    to destination: String? = nil, status: String? = "successful", kind: String? = nil,
                    effective: Int64? = nil) -> HistoryTransaction {
        HistoryTransaction(id: id, type: type, amount: amount, date: date, direction: direction, source: "ark",
                           destination: destination, movementStatus: status, movementKind: kind,
                           effectiveBalanceSat: effective ?? (direction == "incoming" ? amount : -amount))
    }

    static var everyday: [HistoryTransaction] {
        [row("m1", "Bolt11", 2_100, "incoming", at(0, 9, 12), kind: "lightning-receive"),
         row("m2", "Bolt11", 4_200, "outgoing", at(1, 8, 40), to: "lnbcrt42u1fixture"),
         row("m3", "Lnurl", 4_842, "outgoing", at(1, 18, 5), to: "lucas@ln.tips"),
         {
             var deposit = row("onchain-wallet-a", "Onchain", 150_000, "incoming", at(10, 10, 0), status: nil)
             deposit.source = "onchain-wallet"
             deposit.hasConfirmation = true
             return deposit
         }(),
         row("m4", "Lnurl", 500, "outgoing", at(13, 14, 30), to: "nitesh@noahwallet.io"),
         row("m5", "Onchain", 10_000, "incoming", at(13, 9, 0), kind: "onboard"),
         row("m6", "Arkoor", 21_000, "incoming", at(20, 16, 45), kind: "arkoor-receive")]
    }

    static var busy: [HistoryTransaction] {
        var arriving = row("onchain-wallet-b", "Onchain", 100_000, "incoming", "1970-01-01T00:00:00.000Z", status: nil)
        arriving.source = "onchain-wallet"
        arriving.hasConfirmation = false
        arriving.dateLabel = "Unconfirmed"
        arriving.balanceChangeSat = 100_000
        // Nothing left the wallet: every delta, fees included, is recorded as zero.
        var failed = row("m8", "Onchain", 4_200, "outgoing", at(0, 9, 40), status: "failed", kind: "offboard", effective: 0)
        failed.offchainFeeSat = 0
        failed.onchainFeeSat = 0
        var canceled = row("m9", "Bolt11", 1_500, "outgoing", at(0, 9, 30), to: "lnbcrt15u1fixture", status: "canceled", effective: 0)
        canceled.offchainFeeSat = 0
        return [arriving,
                row("m7", "Lnurl", 5_000, "outgoing", at(0, 10, 2), to: "bob@getalby.com", status: "pending"),
                failed, canceled]
            + everyday
    }

    private func fixture(_ state: String) async throws -> (AnyView, URL) {
        let hidden = state.hasPrefix("hidden-")
        let name = hidden ? String(state.dropFirst("hidden-".count)) : state
        let root = FileManager.default.temporaryDirectory.appending(path: "home-shot-\(UUID())")
        let settings = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        var server = PersistedServerState()
        server.lightningAddress = name == "fresh-noaddress" ? nil : "maya@noahwallet.io"
        server.isEmailVerified = !["everyday", "error"].contains(name)
        try settings.setPersistedState(server, key: PersistedServerState.key, version: PersistedServerState.version)
        try settings.setPersistedState(PersistedPrivacyState(isHomeBalanceHidden: hidden), key: PersistedPrivacyState.key,
                                       version: PersistedPrivacyState.version)
        if name == "suspended" {
            try settings.setPersistedState(PersistedWalletState(isInitialized: true, isWalletLoaded: true, isWalletSuspended: true),
                                           key: PersistedWalletState.key, version: PersistedWalletState.version)
        }
        let app = AppModel(settings: settings)
        let rows = Rows()
        let fresh = name.hasPrefix("fresh") || name == "celebrate"
        rows.value = fresh ? [] : name == "busy" ? Self.busy : Self.everyday
        let history = HistoryModel(dependencies: .init(
            load: { rows.value }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } },
            blockTime: { _ in nil }, price: { _, _ in nil }))
        await history.refresh(currency: app.preferredCurrency)
        let rounds = name == "rounds" ? [HomeRound(roundID: 42, status: "pending", isFinal: false, isSuccess: false)] : []
        let renewal = name == "busy" ? HomeRenewal(ids: ["fixture"], sats: 20_000, blocksLeft: 108) : nil
        var backup = PersistedBackupState()
        if name == "backedup" {
            backup.lastBackupStatus = .success
            backup.lastBackupAt = Date().timeIntervalSince1970 * 1000
        }
        let home = HomeModel(dependencies: .init(
            price: { _ in 112_000 }, rounds: { rounds }, backup: { [backup] in backup }, retryBackup: {},
            autoBoards: { [] }, backgroundSnapshot: { .init(isRunning: name == "syncing", completionRevision: 0) },
            renewal: { renewal }))
        let summary = fresh ? Self.summary(ark: 0) : name == "busy" ? Self.summary(ark: 142_742, onchain: 10_000, pending: 100_000)
            : Self.summary(ark: 142_742, onchain: 10_000)
        let base = HomeFixture(summary: summary, walletError: name == "error" ? "Couldn't load your balance. Pull down to try again." : nil,
                               needsRecoveryPhraseBackup: fresh)
        let view = FixtureHost(app: app, history: history, home: home, rows: rows, fixture: base,
                               paysAfter: name == "celebrate" ? 2.5 : nil)
        return (AnyView(view), root)
    }
}

private struct FixtureHost: View {
    let app: AppModel
    let history: HistoryModel
    let home: HomeModel
    let rows: HomeShotTests.Rows
    @State private var fixture: HomeFixture
    let paysAfter: Double?

    init(app: AppModel, history: HistoryModel, home: HomeModel, rows: HomeShotTests.Rows, fixture: HomeFixture, paysAfter: Double?) {
        self.app = app; self.history = history; self.home = home; self.rows = rows
        _fixture = State(initialValue: fixture)
        self.paysAfter = paysAfter
    }

    var body: some View {
        WalletTabs(history: history, home: home)
            .environment(app)
            .environment(app.walletLock)
            .environment(\.homeFixture, fixture)
            .environment(\.locale, Locale(identifier: app.localeIdentifier))
            .environment(\.scenePhase, .active)
            .task {
                guard let paysAfter else { return }
                try? await Task.sleep(for: .seconds(paysAfter))
                rows.value = [HomeShotTests.row("first", "Bolt11", 21_000, "incoming", HistoryRules.isoDate(Date()),
                                                kind: "lightning-receive")]
                fixture.summary = HomeShotTests.summary(ark: 21_000)
                await history.refresh(currency: app.preferredCurrency)
            }
    }
}
