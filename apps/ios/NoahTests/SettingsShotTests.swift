import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Noah

/// Opt-in screenshot fixtures for Settings: the real pages in Home's stack with fake wallet, server and
/// recurring data. The recovery phrase is the prototype's sample phrase, never a real one. Write a state
/// to Documents/set-shot; "stop" ends the session. Light and dark come from the simulator's appearance;
/// a wide window (the Duo open) shows the panes.
/// States: root, profile, lightning-address, email, backup, recovery-gated, recovery-shown, vtxos, vtxo,
/// onchain, recurring, schedule, editor, notifications, advanced, ark-server, esplora, logs, debug,
/// feedback, about, delete, unit, currency, language.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SET_SHOTS"] == "1"))
struct SettingsShotTests {
    static let phrase = "sample phrase only never use this example test word copy nothing real".split(separator: " ").map(String.init)
    nonisolated static let tip: UInt32 = 921_345

    @Test(.timeLimit(.minutes(60))) func presentStates() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.windows.first { $0.isKeyWindow })
        let original = window.rootViewController
        let folder = URL.documentsDirectory
        let request = folder.appending(path: "set-shot"), ready = folder.appending(path: "set-shot-ready")
        // A "stop" left by an earlier session doesn't end this one.
        try? FileManager.default.removeItem(at: request)
        try? FileManager.default.removeItem(at: ready)
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
                try await Task.sleep(for: .milliseconds(150))
                let (view, root) = try await fixture(state)
                roots.append(root)
                host.rootView = view
                try await Task.sleep(for: .milliseconds(1_400))
                try state.write(to: ready, atomically: true, encoding: .utf8)
                current = state
            }
            try await Task.sleep(for: .milliseconds(150))
        }
    }

    nonisolated static func vtxo(_ index: Int, _ amount: UInt64, left: UInt32, kind: String, locked: Bool = false) -> VtxoModel {
        let id = ActivityShotTests.hex("c\(index)") + ":\(index % 3)"
        return VtxoModel(id: id, amount: amount, expiry: tip + left, state: locked ? "Locked" : "Spendable", kind: kind, exitDepth: 3,
                         exitWeight: 1_842, registered: true, serverPubkey: "02" + ActivityShotTests.hex("srv"), exitDelta: 144,
                         anchorPoint: ActivityShotTests.hex("c\(index)a") + ":0", point: ActivityShotTests.hex("c\(index)p") + ":\(index % 2)",
                         expiring: !locked && left <= 144, tip: tip)
    }

    /// The prototype's twelve VTXOs (₿142,742), two expiring within a day and one locked.
    nonisolated static let vtxos: [VtxoModel] = [
        vtxo(1, 31_000, left: 4_176, kind: "round"), vtxo(2, 24_500, left: 3_890, kind: "round"), vtxo(3, 21_000, left: 3_600, kind: "arkoor"),
        vtxo(4, 15_000, left: 3_024, kind: "round"), vtxo(5, 12_000, left: 2_880, kind: "arkoor"), vtxo(6, 9_800, left: 2_600, kind: "round"),
        vtxo(7, 8_400, left: 2_160, kind: "board"), vtxo(8, 7_500, left: 1_872, kind: "arkoor"), vtxo(9, 5_000, left: 1_700, kind: "round"),
        vtxo(10, 4_200, left: 120, kind: "arkoor"), vtxo(11, 3_342, left: 96, kind: "round"), vtxo(12, 1_000, left: 2_950, kind: "arkoor", locked: true),
    ]

    static func onchain() -> OnchainModel {
        let overview = OnchainOverview(walletID: "fixture", vtxos: vtxos, exits: [], confirmed: 10_000, tip: tip, pendingTotal: 0, claimableHeight: nil)
        return OnchainModel(dependencies: .init(ready: { true }, overview: { overview },
            quote: { action, ids, amount, _ in
                let sats = ids.isEmpty ? amount : vtxos.filter { ids.contains($0.id) }.reduce(0) { $0 + $1.amount }
                return .init(action: action, walletID: "fixture", ids: ids, amount: sats, fee: max(8, sats * 28 / 10_000), chainFee: 0, confirmed: 10_000)
            },
            execute: { _ in throw OnchainFailure.unavailable }, attempt: { [] }, acknowledge: { _ in }))
    }

    private func fixture(_ state: String) async throws -> (AnyView, URL) {
        let root = FileManager.default.temporaryDirectory.appending(path: "set-shot-\(UUID())")
        let storage = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        var server = PersistedServerState()
        server.lightningAddress = "maya@noahwallet.io"
        server.emailAddress = "maya.r@proton.me"
        server.isEmailVerified = true
        server.isBackupEnabled = true
        server.isMailboxAuthorizationEnabled = true
        try storage.setPersistedState(server, key: PersistedServerState.key, version: PersistedServerState.version)
        var profile = PersistedProfileState()
        profile.displayName = "Maya"
        try storage.setPersistedState(profile, key: PersistedProfileState.key, version: PersistedProfileState.version)
        var backup = PersistedBackupState()
        backup.lastBackupStatus = .success
        backup.lastBackupAt = (Date().timeIntervalSince1970 - 2 * 3_600) * 1000
        try storage.setPersistedState(backup, key: PersistedBackupState.key, version: PersistedBackupState.version)
        var wallet = PersistedWalletState(isInitialized: true, isWalletLoaded: true)
        wallet.isDebugModeEnabled = state == "debug" || state == "advanced"
        try storage.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        let app = AppModel(settings: storage)

        let recurring = try RecurringExecutorTests.Harness()
        try recurring.store.bind(recurring.owner.withLock { $0 })
        let now = Date().timeIntervalSince1970 * 1000
        for (index, item) in [("Savings", "tark1" + ActivityShotTests.hex("rosa", 60), "ark", UInt64(10_000), "active"),
                              ("Allowance", "lucas@ln.tips", "lnurl", UInt64(2_500), "active"),
                              ("VPN", "lno1qgsq" + ActivityShotTests.hex("vpn", 60), "offer", UInt64(5_000), "active")].enumerated() {
            var schedule = RecurringScheduleTests.schedule(start: now + Double(index + 1) * 2 * 86_400_000)
            schedule.id = "s\(index + 1)"
            schedule.label = item.0
            schedule.destination = item.1
            schedule.destinationType = item.2
            schedule.amountSat = item.3
            schedule.interval = .init(unit: index == 1 ? "week" : "month", every: 1)
            schedule.status = item.4
            schedule.occurrencesPaid = [2, 7, 6][index]
            try recurring.store.upsert(schedule)
        }
        let history = HistoryModel(dependencies: .init(load: { [] }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } },
                                                       blockTime: { _ in nil }, price: { _, _ in nil }, currentPrice: { _ in 112_000 }))
        let summary = SettingsSummary(dependencies: .init(overview: {
            OnchainOverview(walletID: "fixture", vtxos: Self.vtxos, exits: [], confirmed: 10_000, tip: Self.tip, pendingTotal: 0, claimableHeight: nil)
        }, notificationsOn: { true }, storage: storage))
        let fixture = SettingsFixture(
            summary: HomeShotTests.summary(ark: 142_742, onchain: 10_000), storage: storage, onchain: { Self.onchain() },
            phrase: .init(authenticate: { _ in state == "recovery-shown" ? .authenticated : .denied }, hasNoScreenLock: { false },
                          words: { Self.phrase }, generation: { 0 }),
            executor: recurring.executor,
            backups: [("412", 7_200), ("409", 86_400), ("398", 3 * 86_400)].enumerated().map { index, item in
                .init(backupId: "b\(index)", createdAt: Date(timeIntervalSinceNow: -Double(item.1)).formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true)),
                      encryptedSha256: ActivityShotTests.hex("b\(index)"), encryptedSize: Int64(item.0)! * 1024, formatVersion: 2)
            },
            vtxoLifetime: 4_320, boardingMinimum: 20_000,
            sheet: ["delete": .delete, "unit": .unit, "currency": .currency, "language": .language][state])
        let pages: [String: SettingsPage] = [
            "profile": .profile, "lightning-address": .lightningAddress, "email": .email, "backup": .backup,
            "recovery-gated": .recovery, "recovery-shown": .recovery, "vtxos": .vtxos, "vtxo": .vtxo(Self.vtxos[9].id), "onchain": .onchain,
            "recurring": .recurring, "schedule": .schedule("s1"), "editor": .editor(nil), "notifications": .notifications,
            "advanced": .advanced, "ark-server": .arkServer, "esplora": .esplora, "logs": .logs, "debug": .debug,
            "feedback": .feedback, "about": .about,
        ]
        let view = SettingsShotHost(page: pages[state])
            .environment(app)
            .environment(app.walletLock)
            .environment(history)
            .environment(summary)
            .environment(\.settingsFixture, fixture)
            .environment(\.locale, Locale(identifier: app.localeIdentifier))
            .environment(\.scenePhase, .active)
        // Keeps the harness's files until the session ends.
        return (AnyView(view.background { RetainHarness(harness: recurring) }), root)
    }
}

/// Settings in its own stack, with Home's navigator for the window's width.
private struct SettingsShotHost: View {
    let page: SettingsPage?
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())

    var body: some View {
        NavigationStack { SettingsHost(page: page) }
            .environment(\.settings, SettingsNavigator(live: true, wide: window.wide,
                                                       leadingWidth: max(0, window.size.width / 2 - window.insets.leading)))
            .onboardingWindow($window)
    }
}

private struct RetainHarness: View {
    let harness: RecurringExecutorTests.Harness
    var body: some View { Color.clear }
}
