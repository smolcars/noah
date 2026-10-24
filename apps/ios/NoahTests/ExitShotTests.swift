import BarkBoltFFI
import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Noah

/// Opt-in screenshot fixtures for the emergency exit (docs/native-rewrite/design/exit.md): the real
/// pages in Settings' stack with fake wallet data shaped like the prototype's (tip 921,345; the
/// exit followed is ₿31,000, four transactions). Write a state to Documents/exit-shot; "stop" ends
/// the session. Light and dark follow the simulator; a wide window (the Duo open) shows the panes.
/// States: warn, warn-live, choose, choose-all, choose-unfunded, review, review-unfunded,
/// progress-<lift|publish|sending|mempool|confirm|onchain|wait|claimable|claiming|claimed>,
/// progress-<nofunds|feesrose|stuck|shared|stale|canceled|spent>, review-again, claim-other, claim-phone, claim-again, list,
/// list-plain.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_EXIT_SHOTS"] == "1"))
struct ExitShotTests {
    nonisolated static let tip: UInt32 = 921_345
    nonisolated static let vtxos = SettingsShotTests.vtxos
    /// The round each VTXO's path starts from: the first three share one (prototype `ROUND`).
    nonisolated static let rounds = ["r1", "r1", "r1", "r2", "r3", "r4", "b", "r5", "r6", "r7", "r8", "r2"]

    @Test(.timeLimit(.minutes(60))) func presentStates() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.windows.first { $0.isKeyWindow })
        let original = window.rootViewController
        let folder = URL.documentsDirectory
        let request = folder.appending(path: "exit-shot"), ready = folder.appending(path: "exit-shot-ready")
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
                let (view, root) = try fixture(state)
                roots.append(root)
                host.rootView = view
                try await Task.sleep(for: .milliseconds(2_200))
                try state.write(to: ready, atomically: true, encoding: .utf8)
                current = state
            }
            try await Task.sleep(for: .milliseconds(150))
        }
    }

    // MARK: Wallet data

    typealias Tx = ExitDetails.Transaction
    nonisolated static func tx(_ id: String, _ kind: String, height: UInt32? = nil, child: String? = nil, origin: String = "wallet") -> Tx {
        .init(txid: ActivityShotTests.hex(id), status: .init(kind: kind, child_txid: child.map { ActivityShotTests.hex($0) },
              block: height.map { .init(height: $0, hash: ActivityShotTests.hex("h\($0)")) }, origin: origin))
    }

    /// The exit followed through the states: tip at start 921,345; blocks 921,346–349 for its path.
    nonisolated static func followed(_ state: String) -> ExitModel {
        let id = vtxos[0].id, names = ["root", "l2", "l1", "leaf"]
        func path(_ confirmed: Int, next: String? = nil, child: String = "c", origin: String = "wallet") -> [Tx] {
            names.enumerated().map { index, name in
                if index < confirmed { return tx(name, "confirmed", height: tip + 1 + UInt32(index), child: "\(child)\(index)") }
                if index == confirmed, let next {
                    return tx(name, next, child: next == "awaiting-confirmation" ? "\(child)\(index)" : nil, origin: origin)
                }
                return tx(name, "verify-inputs")
            }
        }
        func processing(_ txs: [Tx], tip at: UInt32) -> ExitDetails { .init(kind: "processing", tip_height: at, transactions: txs) }
        func model(_ name: String, _ details: ExitDetails, _ history: [ExitDetails] = []) -> ExitModel {
            ExitModel(id: id, amount: 31_000, state: name, details: details, history: history.map { _ in "Processing" },
                      historyDetails: history, claimable: name == "Claimable")
        }
        let leaf = tip + 4, claimable = leaf + 144
        let mined = processing(path(4), tip: leaf)
        let locked = ExitDetails(kind: "awaiting-delta", tip_height: leaf, confirmed_block: .init(height: leaf, hash: "h"), claimable_height: claimable)
        switch state {
        case "lift": return model("Start", .init(kind: "start", tip_height: tip))
        case "publish": return model("Processing", processing(path(0), tip: tip))
        case "sending": return model("Processing", processing(path(1, next: "awaiting-cpfp-broadcast"), tip: tip + 1))
        case "mempool": return model("Processing", processing(path(1, next: "awaiting-confirmation"), tip: tip + 1))
        case "confirm": return model("Processing", processing(path(2), tip: tip + 2))
        case "onchain": return model("Processing", mined)
        case "nofunds":
            let parked = processing(path(1, next: "awaiting-cpfp-broadcast"), tip: tip + 1)
            return model("Processing", processing(path(1, next: "awaiting-cpfp-broadcast"), tip: tip + 3), [parked])
        case "feesrose":
            return model("Processing", processing(path(1, next: "awaiting-confirmation", child: "rbf"), tip: tip + 2),
                         [processing(path(1, next: "awaiting-confirmation"), tip: tip + 1)])
        case "stuck":
            return model("Processing", processing(path(2, next: "awaiting-confirmation"), tip: tip + 5),
                         [processing(path(2, next: "awaiting-confirmation"), tip: tip + 2)])
        case "shared": return model("Processing", processing(path(0, next: "awaiting-confirmation", origin: "mempool"), tip: tip))
        case "stale": return model("Processing", processing(path(2, next: "awaiting-confirmation"), tip: tip - 52))
        case "wait": return model("AwaitingDelta", .init(kind: "awaiting-delta", tip_height: leaf + 45,
                                  confirmed_block: .init(height: leaf, hash: "h"), claimable_height: claimable), [mined])
        case "claimable": return model("Claimable", .init(kind: "claimable", tip_height: claimable,
                                       claimable_since: .init(height: claimable, hash: "h")), [mined, locked])
        case "claiming": return model("ClaimInProgress", .init(kind: "claim-in-progress", tip_height: claimable,
                                      claimable_since: .init(height: claimable, hash: "h"), claim_txid: ActivityShotTests.hex("claim")), [mined, locked])
        case "claimed": return model("Claimed", .init(kind: "claimed", tip_height: claimable + 1, block: .init(height: claimable + 1, hash: "h"),
                                     txid: ActivityShotTests.hex("claim")), [.init(kind: "start", tip_height: tip), mined, locked])
        case "canceled": return model("Canceled", .init(kind: "canceled", tip_height: tip + 2), [processing(path(1), tip: tip + 1)])
        case "spent": return model("VtxoAlreadySpent", .init(kind: "vtxo-already-spent", tip_height: tip + 1))
        default: return model("Processing", processing(path(1, next: "awaiting-confirmation"), tip: tip + 1))
        }
    }

    /// The list's exits: three from one round (shared root), one ready, one waiting, finished ones.
    nonisolated static func listed() -> [ExitModel] {
        func exit(_ index: Int, _ state: String, _ details: ExitDetails, history: [ExitDetails] = []) -> ExitModel {
            ExitModel(id: vtxos[index].id, amount: vtxos[index].amount, state: state, details: details,
                      history: history.map { _ in "Processing" }, historyDetails: history, claimable: state == "Claimable")
        }
        func shared(_ leaf: String, sent: Int) -> ExitDetails {
            .init(kind: "processing", tip_height: tip, transactions: [
                tx("r1-root", "confirmed", height: tip - 1, child: "c-r1"),
                tx("r1-l2", sent > 1 ? "confirmed" : "awaiting-confirmation", height: sent > 1 ? tip : nil, child: "c-r1-l2"),
                tx("r1-l1-\(leaf)", "verify-inputs"), tx(leaf, "verify-inputs"),
            ])
        }
        let done = ExitDetails(kind: "processing", tip_height: tip - 70, transactions: [tx("r2-root", "confirmed", height: tip - 74), tx("r2-leaf", "confirmed", height: tip - 73)])
        return [
            exit(3, "Claimable", .init(kind: "claimable", tip_height: tip, claimable_since: .init(height: tip - 15, hash: "h")), history: [done]),
            exit(6, "AwaitingDelta", .init(kind: "awaiting-delta", tip_height: tip, confirmed_block: .init(height: tip - 83, hash: "h"), claimable_height: tip + 61),
                 history: [.init(kind: "processing", transactions: [tx("b-board", "confirmed", height: tip - 83)])]),
            exit(0, "Processing", shared("a", sent: 2)), exit(1, "Processing", shared("b", sent: 2)), exit(2, "Processing", shared("c", sent: 1)),
            exit(4, "Processing", .init(kind: "processing", tip_height: tip, transactions: [tx("r3-root", "confirmed", height: tip - 3),
                 tx("r3-l2", "awaiting-cpfp-broadcast"), tx("r3-leaf", "verify-inputs")]),
                 history: [.init(kind: "processing", tip_height: tip - 2, transactions: [tx("r3-root", "confirmed", height: tip - 3),
                 tx("r3-l2", "awaiting-cpfp-broadcast"), tx("r3-leaf", "verify-inputs")])]),
            exit(5, "Claimed", .init(kind: "claimed", tip_height: tip, block: .init(height: tip - 133, hash: "h"), txid: ActivityShotTests.hex("old"))),
            exit(10, "Canceled", .init(kind: "canceled", tip_height: tip - 200)),
        ]
    }

    /// bark's estimator over the prototype's package sizes: shared transactions once, 3 sat/vB with
    /// the 1.2 margin, and a claim at 2 sat/vB.
    nonisolated static func estimate(_ ids: [String]) -> EmergencyExitFeeEstimate {
        var txs = Set<String>(), vbytes = 0
        for id in ids {
            guard let index = vtxos.firstIndex(where: { $0.id == id }) else { continue }
            let round = rounds[index]
            let path = round == "b" ? ["b\(index)"] : [round, "\(round)-l2-\(index / 2)", "\(round)-l1-\(index)", "leaf\(index)"]
            for (step, name) in path.enumerated() where txs.insert(name).inserted { vbytes += round == "b" ? 276 : step == 3 ? 322 : 405 }
        }
        let broadcast = UInt64((Double(vbytes * 3) * 1.2).rounded()), claim = UInt64((54 + 77 * ids.count) * 2)
        return .init(exitBroadcastFeeSats: broadcast, claimFeeSats: claim, totalFeeSats: broadcast + claim, feeRateSatPerVb: 3,
                     txsToBroadcast: UInt64(txs.count), excludedVtxos: [])
    }

    nonisolated static func overview(exits: [ExitModel], confirmed: UInt64 = 18_400, tip at: UInt32 = tip, packageFees: [String: UInt64] = [:],
                                     progressing: Bool = true) -> OnchainOverview {
        var overview = OnchainOverview(walletID: "fixture", vtxos: vtxos.filter { !Set(exits.map(\.id)).contains($0.id) }
                                        + [VtxoModel(id: "dust", amount: 290, expiry: tip + 2_000, state: "Spendable", kind: "arkoor", exitDepth: 2,
                                                     exitWeight: 900, registered: true, serverPubkey: "02", exitDelta: 144, anchorPoint: "a:0",
                                                     point: "p:0", expiring: false, tip: tip)],
                                       exits: exits, confirmed: confirmed, tip: at, pendingTotal: 0, claimableHeight: nil)
        overview.exitDelta = 144
        overview.packageFees = packageFees
        overview.daemonSince = progressing ? .distantPast : nil
        // A completed Check the chain left these waiting for a fee child.
        overview.provenParked = [vtxos[0].id: episode("l2", "awaiting-cpfp-broadcast"), vtxos[4].id: episode("r3-l2", "awaiting-cpfp-broadcast")]
        overview.childFees = Dictionary(uniqueKeysWithValues: ["c0", "c1", "c2", "c3", "rbf0", "rbf1"].map { (ActivityShotTests.hex($0), $0.hasPrefix("rbf") ? 2_916 : 1_215) })
        overview.received = [ActivityShotTests.hex("claim"): 30_738]
        // What Noah recorded when it built that claim, to this phone.
        overview.claimRecords = [ActivityShotTests.hex("claim"): .init(ids: [vtxos[0].id], fee: 262, toPhone: true)]
        return overview
    }

    /// The "stuck" exit's last transaction was first seen in the mempool three blocks ago.
    nonisolated static let sightings = ExitSightings(seen: [episode("l1", "awaiting-confirmation"): .init(tip: tip + 2, at: .distantPast)])

    /// The fixtures' episodes all began at bark's first record (`ExitModel.episode`).
    nonisolated static func episode(_ name: String, _ kind: String) -> String { "\(ActivityShotTests.hex(name))|\(kind)|0" }

    static func model(_ overview: OnchainOverview, detail: ExitModel? = nil, quote: Bool = true, reviewFails: Bool = false) -> OnchainModel {
        OnchainModel(dependencies: .init(ready: { true }, overview: { overview },
            quote: { action, ids, _, destination in
                if reviewFails { throw OnchainFailure.changed }
                guard quote else { throw ExitEstimateUnavailable(review: .init(action: action, walletID: "fixture", ids: ids, amount: 0, confirmed: overview.confirmed)) }
                let amount = action == .startExit ? overview.vtxos.filter { ids.contains($0.id) }.reduce(0) { $0 + $1.amount }
                    : overview.exits.filter { ids.contains($0.id) }.reduce(0) { $0 + $1.amount }
                let fee = estimate(ids)
                return .init(action: action, walletID: "fixture", ids: ids, destination: destination, destinationIsMine: destination.map { $0.hasPrefix("bcrt1p") },
                             amount: amount, fee: action == .claim ? 262 : fee.claimFeeSats, chainFee: action == .claim ? 0 : fee.exitBroadcastFeeSats,
                             confirmed: overview.confirmed, feeRate: fee.feeRateSatPerVb)
            },
            execute: { _ in throw OnchainFailure.unavailable }, attempt: { [] }, acknowledge: { _ in },
            detail: { _ in detail },
            claimAddress: { "bcrt1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297" },
            claimOwnership: { $0.hasPrefix("bcrt1p") },
            estimate: { estimate($0) },
            sightings: .init(read: { String(decoding: try! JSONEncoder().encode(["fixture": sightings]), as: UTF8.self) }, write: { _ in })),
            detailID: detail?.id)
    }

    private func fixture(_ state: String) throws -> (AnyView, URL) {
        let root = FileManager.default.temporaryDirectory.appending(path: "exit-shot-\(UUID())")
        let storage = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        try storage.setPersistedState(PersistedWalletState(isInitialized: true, isWalletLoaded: true), key: PersistedWalletState.key,
                                      version: PersistedWalletState.version)
        let app = AppModel(settings: storage)
        let page: SettingsPage
        let onchain: @MainActor () -> OnchainModel
        switch state {
        case "warn", "warn-live":
            let exits = state == "warn" ? [] : [Self.followed("mempool")]
            page = .exit; onchain = { Self.model(Self.overview(exits: exits)) }
        case "choose", "choose-all", "choose-unfunded":
            page = .exitChoose; onchain = { Self.model(Self.overview(exits: [], confirmed: state == "choose-unfunded" ? 310 : 18_400)) }
        case "review", "review-unfunded", "review-unknown", "review-again":
            page = .exitReview([Self.vtxos[0].id])
            onchain = { Self.model(Self.overview(exits: [], confirmed: state == "review-unfunded" ? 310 : 18_400), quote: state != "review-unknown",
                                   reviewFails: state == "review-again") }
        case "claim-other", "claim-phone", "claim-invalid", "claim-again":
            page = .exitClaim
            onchain = { Self.model(Self.overview(exits: [Self.followed("claimable")], tip: Self.tip + 148), reviewFails: state == "claim-again") }
        case "list", "list-plain":
            page = .exitList
            let exits = state == "list" ? Self.listed() : Array(Self.listed().prefix(2))
            onchain = { Self.model(Self.overview(exits: exits, packageFees: [Self.vtxos[4].id: 1_215])) }
        default:
            let name = String(state.dropFirst("progress-".count))
            let exit = Self.followed(name)
            let tip: UInt32 = switch name {
            case "stale": Self.tip + 2
            case "wait": Self.tip + 4 + 45
            case "claimable", "claiming": Self.tip + 4 + 144
            case "claimed": Self.tip + 4 + 145
            default: exit.details.tip_height ?? Self.tip
            }
            page = .exitProgress(exit.id)
            onchain = { Self.model(Self.overview(exits: [exit], confirmed: name == "nofunds" ? 310 : 18_400, tip: tip,
                                                 packageFees: [exit.id: 1_215], progressing: name != "stale"), detail: exit) }
        }
        let summary = SettingsSummary(dependencies: .init(overview: { Self.overview(exits: []) }, notificationsOn: { true }, storage: storage))
        let recurring = try RecurringExecutorTests.Harness()
        let fixture = SettingsFixture(summary: HomeShotTests.summary(ark: 142_742, onchain: 18_400), storage: storage, onchain: onchain,
                                      phrase: .init(authenticate: { _ in .denied }, hasNoScreenLock: { false }, words: { [] }, generation: { 0 }),
                                      executor: recurring.executor, exitSelection: state == "choose" ? [Self.vtxos[0].id] : nil,
                                      exitClaimTo: ["claim-other": "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202", "claim-again": "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202", "claim-phone": "phone",
                                                    "claim-invalid": "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"][state])
        let view = ExitShotHost(page: page)
            .environment(app)
            .environment(app.walletLock)
            .environment(summary)
            .environment(\.settingsFixture, fixture)
            .environment(\.locale, Locale(identifier: app.localeIdentifier))
            .environment(\.scenePhase, .active)
        return (AnyView(view), root)
    }
}

/// The exit page in Settings' stack, with Home's navigator for the window's width.
private struct ExitShotHost: View {
    let page: SettingsPage
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())

    var body: some View {
        NavigationStack { SettingsHost(page: page) }
            .environment(\.settings, SettingsNavigator(live: true, wide: window.wide,
                                                       leadingWidth: max(0, window.size.width / 2 - window.insets.leading)))
            .onboardingWindow($window)
    }
}
