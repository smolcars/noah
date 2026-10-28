import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// Opt-in screenshot fixtures for Activity: the real tab shell on the Activity tab with fake
/// history, balance and prices (no wallet, server writes or recovery phrase). Write a state
/// name to Documents/act-shot ("hidden-" prefix hides amounts); "stop" ends the session.
/// States: everyday, busy, empty, loading, error, suspended, filter, filter-empty, search,
/// search-none, csv, and detail-<id> (detail-<id>-details opens Details), e.g. detail-m3,
/// detail-p3 (pending on-chain), detail-f1 (failed), detail-f2 (refunded).
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_ACT_SHOTS"] == "1"))
struct ActivityShotTests {
    @Test(.timeLimit(.minutes(60))) func presentStates() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.keyWindow)
        let original = window.rootViewController
        let folder = URL.documentsDirectory
        let request = folder.appending(path: "act-shot"), ready = folder.appending(path: "act-shot-ready")
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
                try await Task.sleep(for: .milliseconds(900))
                try state.write(to: ready, atomically: true, encoding: .utf8)
                current = state
            }
            try await Task.sleep(for: .milliseconds(150))
        }
    }

    nonisolated static let tip: UInt32 = 921_345
    static let address = "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
    /// Deterministic fake hex for IDs, hashes and transactions.
    nonisolated static func hex(_ seed: String, _ count: Int = 64) -> String {
        var value = seed.utf8.reduce(UInt64(5381)) { ($0 << 5) &+ $0 &+ UInt64($1) }
        let digits = Array("0123456789abcdef")
        return String((0..<count).map { _ in
            value = value &* 6364136223846793005 &+ 1442695040888963407
            return digits[Int(value >> 60)]
        })
    }

    static func movement(_ id: String, _ type: String, _ amount: Int64, _ direction: String, _ date: String, to: String? = nil,
                         status: String = "successful", kind: String? = nil, subsystem: String? = nil, fee: Int64 = 0) -> HistoryTransaction {
        var row = HomeShotTests.row(id, type, amount, direction, date, to: to, status: status, kind: kind,
                                    effective: direction == "incoming" ? amount : -(amount + fee))
        row.movementId = UInt32(id.dropFirst().filter(\.isNumber)).map { $0 + 100 }
        row.offchainFeeSat = fee
        row.intendedBalanceSat = direction == "incoming" ? amount : -amount
        row.metadataJson = "{}"
        if let subsystem {
            let parts = subsystem.split(separator: ":").map(String.init)
            row.subsystemName = parts[0]
            row.subsystemKind = parts[1]
        }
        row.inputVtxos = direction == "outgoing" ? [hex(id + "in") + ":0"] : []
        row.outputVtxos = [hex(id + "out") + ":1"]
        row.exitedVtxos = []
        row.txid = row.outputVtxos?.first
        if let to { row.sentTo = [.init(destination: to, amount_sat: amount, paymentMethod: to.contains("@") ? "lightning-address" : "invoice")] }
        return row
    }

    static var everyday: [HistoryTransaction] {
        let at = HomeShotTests.at
        var lucas = movement("m2", "Lnurl", 4_842, "outgoing", at(1, 18, 5), to: "lucas@ln.tips", subsystem: "bark.lightning_send:send", fee: 9)
        lucas.preimage = hex("m2pre")
        lucas.paymentHash = hex("m2hash")
        var coffee = movement("m3", "Bolt11", 4_200, "outgoing", at(1, 8, 40), to: ActivityMappingTests.coffeeInvoice,
                              subsystem: "bark.lightning_send:send", fee: 12)
        coffee.paymentDescription = "Coffee Collective"
        coffee.paymentHash = ActivityMappingTests.coffeeHash
        coffee.preimage = hex("m3pre")
        var bob = movement("m4", "Lnurl", 5_000, "outgoing", at(2, 21, 30), to: "bob@getalby.com", subsystem: "bark.lightning_send:send", fee: 15)
        bob.repeatPayment = RepeatPayment(destination: "bob@getalby.com", comment: "Pizza night", amountMode: "FIAT", amountInput: "5.60",
                                          amountSat: 5_000, fiatCurrency: "USD")
        bob.preimage = hex("m4pre")
        bob.paymentHash = hex("m4hash")
        var savings = movement("m5", "Arkoor", 10_000, "outgoing", at(3, 8, 0), to: "rosa@noahwallet.io", subsystem: "bark.arkoor:send")
        savings.sentTo = [.init(destination: "tark1" + hex("rosa", 60), amount_sat: 10_000, paymentMethod: "ark")]
        savings.recurring = .init(scheduleID: "s1", name: "Savings", interval: .init(unit: "month", every: 1))
        savings.repeatPayment = RepeatPayment(destination: "rosa@noahwallet.io", comment: "", amountMode: "SATS", amountInput: "10000", amountSat: 10_000)
        var nitesh = movement("m6", "Lnurl", 500, "outgoing", at(5, 16, 22), to: "nitesh@noahwallet.io", subsystem: "bark.arkoor:send")
        nitesh.repeatPayment = RepeatPayment(destination: "nitesh@noahwallet.io", comment: "", amountMode: "SATS", amountInput: "500", amountSat: 500)
        var renew = movement("m7", "Arkoor", 45, "outgoing", at(5, 3, 12), status: "successful", kind: "refresh", subsystem: "bark.round:refresh")
        renew.intendedBalanceSat = 0
        renew.effectiveBalanceSat = -45
        renew.offchainFeeSat = 45
        renew.inputVtxos = (0..<3).map { hex("m7in\($0)") + ":\($0)" }
        renew.chainAnchor = hex("m7anchor") + ":0"
        renew.sentTo = []
        // Bark 0.8 records an offboard's one committed fee as the Ark fee.
        var onchain = movement("m8", "Onchain", 53_000, "outgoing", at(7, 10, 47), to: address, kind: "send-onchain",
                               subsystem: "bark.offboard:send_onchain", fee: 2_550)
        onchain.sentTo = [.init(destination: address, amount_sat: 53_000, paymentMethod: "bitcoin")]
        onchain.txid = hex("m8tx")
        onchain.metadataJson = #"{"offboard_txid":"\#(hex("m8tx"))"}"#
        let received = movement("m9", "Arkoor", 21_000, "incoming", at(9, 19, 15), kind: "arkoor-receive", subsystem: "bark.arkoor:receive")
        var board = movement("m10", "Onchain", 140_000, "incoming", at(11, 15, 40), kind: "onboard", subsystem: "bark.board:board")
        board.onchainFeeSat = 310
        board.txid = hex("m10tx")
        board.chainAnchor = hex("m10tx") + ":0"
        board.hasConfirmation = true
        board.confirmationHeight = tip - 1_700
        board.confirmationHash = "0000000000000000000" + hex("m10bh", 45)
        board.txHex = "0200000000010" + hex("m10raw", 180)
        board.balanceChangeSat = -140_310
        var deposit = HomeShotTests.row("onchain-wallet-\(hex("dep"))", "Onchain", 150_000, "incoming", at(11, 14, 2), status: nil)
        deposit.source = "onchain-wallet"
        deposit.txid = hex("dep")
        deposit.hasConfirmation = true
        deposit.confirmationHeight = tip - 1_710
        deposit.confirmationHash = "0000000000000000000" + hex("depbh", 45)
        deposit.balanceChangeSat = 150_000
        deposit.txHex = "0200000000010" + hex("depraw", 180)
        var offer = movement("m11", "Bolt11", 3_300, "outgoing", at(15, 20, 10), to: ActivityMappingTests.offer,
                             subsystem: "bark.lightning_send:send", fee: 10)
        offer.sentTo = [.init(destination: ActivityMappingTests.offer, amount_sat: 3_300, paymentMethod: "offer")]
        offer.paymentDescription = "Satoshi Station"
        offer.preimage = hex("m11pre")
        var canceled = movement("m12", "Lnurl", 2_000, "outgoing", at(35, 21, 0), to: "bob@getalby.com", status: "canceled",
                                subsystem: "bark.lightning_send:send")
        canceled.effectiveBalanceSat = 0
        canceled.outputVtxos = []
        let lightning = movement("m1", "Bolt11", 2_100, "incoming", at(0, 9, 12), kind: "lightning-receive",
                                 subsystem: "bark.lightning_receive:receive")
        return [lightning, lucas, coffee, bob, savings, nitesh, renew, onchain, received, board, deposit, offer, canceled]
            .map { var row = $0; row.btcPrice = 112_000 - Double($0.id.utf8.reduce(0) { $0 + Int($1) } * 37 % 3_000); return row }
    }

    static var busy: [HistoryTransaction] {
        let at = HomeShotTests.at
        var arriving = HomeShotTests.row("onchain-wallet-p1", "Onchain", 100_000, "incoming", "1970-01-01T00:00:00.000Z", status: nil)
        arriving.source = "onchain-wallet"
        arriving.txid = hex("p1")
        arriving.hasConfirmation = false
        arriving.dateLabel = "Unconfirmed"
        arriving.balanceChangeSat = 100_000
        var sending = movement("p2", "Lnurl", 5_000, "outgoing", at(0, 9, 30), to: "bob@getalby.com", status: "pending",
                               subsystem: "bark.lightning_send:send", fee: 15)
        sending.paymentHash = hex("p2hash")
        var swap = movement("p3", "Onchain", 40_000, "outgoing", at(0, 9, 2), to: address, status: "pending", kind: "send-onchain",
                            subsystem: "bark.offboard:send_onchain", fee: 2_550)
        swap.sentTo = [.init(destination: address, amount_sat: 40_000, paymentMethod: "bitcoin")]
        swap.txid = hex("p3tx")
        swap.metadataJson = #"{"offboard_txid":"\#(hex("p3tx"))"}"#
        var round = movement("p4", "Onchain", 30_000, "outgoing", at(0, 8, 50), to: address, status: "pending", kind: "offboard",
                             subsystem: "bark.round:offboard", fee: 150)
        round.sentTo = [.init(destination: address, amount_sat: 30_000, paymentMethod: "bitcoin")]
        var exit = movement("p5", "Onchain", 20_000, "outgoing", at(1, 20, 0), status: "pending", kind: "exit", subsystem: "bark.exit:start")
        exit.exitedVtxos = [hex("p5x") + ":0"]
        exit.sentTo = []
        var failed = movement("f1", "Bolt11", 4_200, "outgoing", at(0, 8, 38), to: ActivityMappingTests.coffeeInvoice, status: "failed",
                              subsystem: "bark.lightning_send:send", fee: 12)
        failed.paymentDescription = "Coffee Collective"
        failed.paymentHash = ActivityMappingTests.coffeeHash
        failed.effectiveBalanceSat = 0
        failed.outputVtxos = []
        var refunded = movement("f2", "Bolt11", 8_900, "outgoing", at(0, 7, 55), to: ActivityMappingTests.blankInvoice, status: "failed",
                                subsystem: "bark.lightning_send:send")
        refunded.paymentDescription = "Nakamoto Books"
        refunded.paymentHash = ActivityMappingTests.blankHash
        refunded.effectiveBalanceSat = 0
        refunded.outputVtxos = [hex("f2rev") + ":0"]
        return [arriving, sending, swap, round, exit, failed, refunded] + everyday
    }

    final class Rows: @unchecked Sendable { var value: [HistoryTransaction] = [] }

    private func fixture(_ state: String) async throws -> (AnyView, URL) {
        let hidden = state.hasPrefix("hidden-")
        let name = hidden ? String(state.dropFirst("hidden-".count)) : state
        let root = FileManager.default.temporaryDirectory.appending(path: "act-shot-\(UUID())")
        let settings = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        var server = PersistedServerState()
        server.lightningAddress = "maya@noahwallet.io"
        server.isEmailVerified = true
        try settings.setPersistedState(server, key: PersistedServerState.key, version: PersistedServerState.version)
        try settings.setPersistedState(PersistedPrivacyState(isHomeBalanceHidden: hidden), key: PersistedPrivacyState.key,
                                       version: PersistedPrivacyState.version)
        if name == "suspended" {
            try settings.setPersistedState(PersistedWalletState(isInitialized: true, isWalletLoaded: true, isWalletSuspended: true),
                                           key: PersistedWalletState.key, version: PersistedWalletState.version)
        }
        let app = AppModel(settings: settings)
        let rows = Rows()
        rows.value = name == "empty" ? [] : name.contains("busy") || name == "confirm" || name.hasPrefix("detail-p") || name.hasPrefix("detail-f")
            ? Self.busy : Self.everyday
        let failing = name == "error"
        let history = HistoryModel(dependencies: .init(
            load: {
                if failing { throw URLError(.notConnectedToInternet) }
                return rows.value
            }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } },
            blockTime: { _ in nil }, price: { _, _ in nil }, currentPrice: { _ in 112_000 },
            chainTip: { Self.tip }, chainStatus: { txid in txid == Self.hex("p3tx") ? .confirmed(Self.tip) : .inMempool }))
        if name != "loading" { await history.refresh(currency: app.preferredCurrency) }
        let home = HomeModel(dependencies: .init(
            price: { _ in 112_000 }, rounds: { [] }, backup: { PersistedBackupState() }, retryBackup: {},
            autoBoards: { [] }, backgroundSnapshot: { .init(isRunning: false, completionRevision: 0) }, renewal: { nil }))
        var activity = ActivityFixture()
        switch name {
        case "filter": activity.filter = .lightning
        case "filter-empty": rows.value = Self.everyday.filter { !HistoryFilter.ark.includes($0) }; activity.filter = .ark
            await history.refresh(currency: app.preferredCurrency)
        case "search": activity.query = "lucas"
        case "search-none": activity.query = "zzz"
        case "csv": activity.exporting = true
        default:
            if name.hasPrefix("detail-") {
                let parts = name.dropFirst("detail-".count).split(separator: "-")
                let id = String(parts[0])
                activity.selectedID = rows.value.first { $0.id == id || $0.id.hasSuffix(id) }?.id
                activity.detailsOpen = parts.contains("details")
            }
        }
        let summary = HomeShotTests.summary(ark: 142_742, onchain: 10_000)
        let view = WalletTabs(history: history, home: home, tab: .history)
            .environment(app)
            .environment(app.walletLock)
            .environment(\.homeFixture, HomeFixture(summary: summary, walletError: nil, needsRecoveryPhraseBackup: false))
            .environment(\.activityFixture, activity)
            .environment(\.locale, Locale(identifier: app.localeIdentifier))
            .environment(\.scenePhase, .active)
        guard name == "confirm" else { return (AnyView(view), root) }
        // A pending deposit confirms while Activity is on screen.
        let confirming = view.task {
            try? await Task.sleep(for: .seconds(2.5))
            rows.value = rows.value.map { row in
                guard row.id == "onchain-wallet-p1" else { return row }
                var row = row
                row.hasConfirmation = true
                row.confirmationHeight = Self.tip + 1
                // With a block time it sorts to the top of Today, on screen.
                row.date = HistoryRules.isoDate(Date())
                row.sortTimestamp = Date().timeIntervalSince1970 * 1000
                row.dateLabel = nil
                return row
            }
            await history.refresh(currency: app.preferredCurrency)
        }
        return (AnyView(confirming), root)
    }
}
