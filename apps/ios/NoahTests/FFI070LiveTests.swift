import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// Opt-in stages for bark-bolt-ffi 0.7.0 on a dedicated simulator, against a Bark 0.8 stack selected with
/// NOAH_REGTEST_ARK_URL / NOAH_REGTEST_BITCOIND_URL / NOAH_REGTEST_SERVER_URL. The wallet was made by the
/// 0.6.0 build (see SendTests/README.md); the host mines between stages and each stage writes non-secret
/// evidence to Documents.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_FFI070_STAGE"] != nil))
struct FFI070LiveTests {
    let session = WalletSession.shared

    @Test func acceptance() async throws {
        try #require(AppVariant.current == .regtest)
        let stage = try #require(ProcessInfo.processInfo.environment["NOAH_FFI070_STAGE"])
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: .regtest).read() else { throw SendFailure.unresolved }
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "ffi070-live")
        switch stage {
        case "upgrade": try await upgrade()
        case "autoboard": try await autoBoard()
        case "exit-all": try await exitAll()
        default: Issue.record("Unknown stage \(stage)")
        }
        try await session.close()
    }

    /// The 0.6.0 build's wallet opens unchanged, before and after a sync.
    private func upgrade() async throws {
        let baseline = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf:
            URL.documentsDirectory.appending(path: "ffi070-upgrade-baseline.json"))) as? [String: Any])
        func check() async throws {
            let summary = try #require(try await session.summary())
            #expect(summary.fingerprint == baseline["fingerprint"] as? String)
            #expect(summary.spendableSats == (baseline["spendable"] as? UInt64))
            #expect(summary.balance.onchain.confirmed == (baseline["onchainConfirmed"] as? UInt64))
            #expect(try await rows() == baseline["movements"] as? [[String: String]])
            #expect(try await utxos() == baseline["utxos"] as? [String])
            #expect(try await vtxos() == baseline["vtxos"] as? [String])
        }
        try await check()
        try await session.sync()
        try await check()
        try evidence("upgrade", ["movements": UInt64((baseline["movements"] as? [Any])?.count ?? 0),
                                 "spendable": try await session.summary()?.spendableSats ?? 0])
    }

    /// The biggest confirmed coin stands in for an exit claim to this phone, marked in auto-board's ledger
    /// exactly as a claim to this phone marks its output. Auto-board leaves it on-chain; Move to Ark doesn't.
    private func autoBoard() async throws {
        try await session.sync()
        let coins = try await session.withOpenWallet { try await $0.onchain.utxos() }.compactMap { utxo -> (String, UInt64)? in
            guard case .local(let point, let amount, let height) = utxo, height != nil else { return nil }
            return ("\(point.txid):\(point.vout)", amount)
        }
        try #require(coins.count >= 2)
        let claim = try #require(coins.max { $0.1 < $1.1 })
        let walletID = try await session.withOpenWallet { $0.wallet.fingerprint() }
        try AutoBoardFunding.markClaimed([claim.0], walletID: walletID)
        let manual = try await session.quoteOnchain(.board, amount: 0)
        #expect(manual.spends([claim.0]))
        await session.discardBoard(manual)
        let quote = try #require(try await session.autoBoardQuote())
        let inputs = quote.prepared?.inputs?.map { "\($0.txid):\($0.vout)" } ?? []
        #expect(!inputs.isEmpty && !quote.spends([claim.0]))
        #expect(try quote.confirmed - quote.amount - #require(quote.chainFee) >= claim.1 + BoardingRules.reserve)
        _ = try await session.executeOnchain(quote)
        let attempt = try #require(try await OnchainOperationJournal.shared.loadAll().first { $0.id == quote.id })
        #expect(attempt.receipt?.txid == quote.prepared?.txid)
        try write("autoboard-attempt.txt", quote.id.uuidString)
        try write("claim-outpoint.txt", claim.0)
        try evidence("autoboard", ["claimSats": claim.1, "boardSats": quote.amount, "chainFee": quote.chainFee ?? 0,
                                   "inputs": UInt64(inputs.count), "manualInputs": UInt64(manual.prepared?.inputs?.count ?? 0),
                                   "coins": UInt64(coins.count)])
    }

    /// After the board's confirmations: it resolved, the claimed coin is still on-chain, and the whole-wallet
    /// ("All") estimate prices the wallet and lists what bark would leave out.
    private func exitAll() async throws {
        let id = try #require(UUID(uuidString: try read("autoboard-attempt.txt")))
        let claim = try read("claim-outpoint.txt")
        let deadline = ContinuousClock.now.advanced(by: .seconds(240))
        while ContinuousClock.now < deadline {
            try await session.sync()
            if try await session.withOpenWallet({ try await $0.wallet.pendingBoards() }).isEmpty { break }
            try await Task.sleep(for: .seconds(2))
        }
        #expect(try await session.withOpenWallet { try await $0.wallet.pendingBoards() }.isEmpty)
        #expect(try await session.checkOnchainAttempt(id))
        let receipt = try #require(try await OnchainOperationJournal.shared.loadAll().first { $0.id == id }?.receipt)
        switch receipt.chainStatus {
        case .confirmed: break
        default: Issue.record("Board not confirmed: \(String(describing: receipt.chainStatus))")
        }
        #expect(try await utxos().contains { $0.hasPrefix(claim + "=") })
        let all = try await session.withOpenWallet {
            try await $0.wallet.estimateEmergencyExitFee(vtxoIds: [], feeRateSatPerVb: nil, destination: nil)
        }
        #expect(all.exitBroadcastFeeSats > 0 && all.txsToBroadcast > 0 && all.totalFeeSats == all.exitBroadcastFeeSats + all.claimFeeSats)
        let model = OnchainModel(dependencies: .live(session: session))
        let excluded = await model.exitExclusions()
        #expect(Set(excluded.keys) == Set(all.excludedVtxos.map(\.vtxoId)))
        let spendable = try await session.withOpenWallet { try await $0.wallet.vtxos() }.filter { $0.state == .spendable }
        let explicit = try await session.exitEstimate(spendable.map(\.id).filter { excluded[$0] == nil })
        try write("exit-all.json", #require(String(bytes: JSONSerialization.data(withJSONObject: [
            "all": ["exitBroadcastFeeSats": all.exitBroadcastFeeSats, "claimFeeSats": all.claimFeeSats,
                    "txsToBroadcast": all.txsToBroadcast, "feeRateSatPerVb": all.feeRateSatPerVb,
                    "excluded": all.excludedVtxos.map { ["amountSats": $0.amountSats, "code": $0.code.rawValue] }],
            "explicitSpendable": ["vtxos": spendable.count, "exitBroadcastFeeSats": explicit.exitBroadcastFeeSats,
                                  "txsToBroadcast": explicit.txsToBroadcast],
        ], options: [.sortedKeys]), encoding: .utf8)))
    }

    // MARK: Helpers

    private func rows() async throws -> [[String: String]] {
        try await session.withOpenWallet { try await $0.wallet.history() }.sorted { $0.id < $1.id }.map {
            ["id": "\($0.id)", "status": $0.status, "subsystem": $0.subsystemName, "kind": $0.subsystemKind,
             "intended": "\($0.intendedBalanceSats)", "effective": "\($0.effectiveBalanceSats)", "fee": "\($0.offchainFeeSats)"]
        }
    }

    private func utxos() async throws -> [String] {
        try await session.withOpenWallet { try await $0.onchain.utxos() }.compactMap { utxo -> String? in
            guard case .local(let point, let amount, let height) = utxo else { return nil }
            return "\(point.txid):\(point.vout)=\(amount)@\(height.map(String.init) ?? "-")"
        }.sorted()
    }

    private func vtxos() async throws -> [String] {
        try await session.withOpenWallet { try await $0.wallet.vtxos() }.map { "\($0.id)=\($0.amountSats)" }.sorted()
    }

    private func read(_ name: String) throws -> String {
        try String(contentsOf: URL.documentsDirectory.appending(path: "ffi070-\(name)"), encoding: .utf8)
    }

    private func write(_ name: String, _ text: String) throws {
        try text.write(to: URL.documentsDirectory.appending(path: "ffi070-\(name)"), atomically: true, encoding: .utf8)
    }

    private func evidence(_ stage: String, _ values: [String: UInt64]) throws {
        try JSONEncoder().encode(values).write(to: URL.documentsDirectory.appending(path: "ffi070-\(stage).json"))
    }
}
