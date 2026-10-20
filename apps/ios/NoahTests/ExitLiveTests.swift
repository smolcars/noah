import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// Dedicated, disposable simulator only. Stages are explicit; none broadcasts an exit.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_M6_LIVE_STAGE"] != nil))
struct ExitLiveTests {
    @Test func regtestAcceptance() async throws {
        guard AppVariant.current == .regtest else { throw OnchainFailure.invalid }
        let stage = try #require(ProcessInfo.processInfo.environment["NOAH_M6_LIVE_STAGE"])
        let session = WalletSession.shared
        let settings = SettingsStorage.shared
        let seed = KeychainPassword.mnemonic(for: .regtest)
        if stage == "create" {
            guard !WalletDataLocation(variant: .regtest).hasAnyData, case .notFound = seed.read() else {
                throw OnchainFailure.invalid
            }
            let mnemonic = try generateMnemonic()
            try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "m6-acceptance")
            try seed.save(mnemonic)
            try settings.setPersistedState(
                PersistedWalletState(
                    isInitialized: true, isWalletLoaded: true,
                    staticVtxoPubkey: try await session.peekPublicKey(index: 0)),
                key: PersistedWalletState.key, version: 0)
            var transaction = PersistedTransactionState()
            transaction.setAutoBoarding(false)
            try settings.setPersistedState(transaction, key: PersistedTransactionState.key, version: 1)
            let address = try await session.exitDepositAddress()
            try JSONEncoder().encode(["address": address]).write(
                to: URL.documentsDirectory.appending(path: "m6-live-address.json"), options: .atomic)
            return
        }
        guard case .found(let mnemonic) = seed.read() else { throw OnchainFailure.unavailable }
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "m6-acceptance")
        if stage == "board" {
            try await session.sync()
            let quote = try await session.quoteOnchain(.board, amount: 30000)
            #expect(quote.confirmed >= quote.amount + quote.chainFee)
            // Explicit opt-in stage authorizes only this reviewed 30,000-sat regtest board.
            let receipt = try await session.executeOnchain(quote)
            try JSONEncoder().encode([
                "receipt": receipt, "gross": "\(quote.amount)", "arkFee": "\(quote.fee)",
                "chainFee": "\(quote.chainFee)",
            ]).write(to: URL.documentsDirectory.appending(path: "m6-live-board.json"), options: .atomic)
            try await OnchainOperationJournal.shared.acknowledge()
        } else if stage == "refresh" {
            try await session.sync()
            let overview = try await session.onchainOverview()
            let vtxo = try #require(overview.vtxos.first { $0.refreshable })
            let quote = try await session.quoteOnchain(.refresh, ids: [vtxo.id])
            let receipt = try await session.executeOnchain(quote)
            try JSONEncoder().encode([
                "receipt": receipt, "vtxo": vtxo.id, "amount": "\(quote.amount)", "fee": "\(quote.fee)",
            ]).write(to: URL.documentsDirectory.appending(path: "m6-live-refresh.json"), options: .atomic)
            try await OnchainOperationJournal.shared.acknowledge()
        } else if stage == "estimate" {
            try await session.sync()
            let before = try await session.onchainOverview()
            let ids = before.vtxos.filter(\.eligible).map(\.id)
            #expect(!ids.isEmpty)
            let transactionsBefore = try await session.transactionHistory()
            let quote = try await session.quoteOnchain(.startExit, ids: ids)
            let after = try await session.onchainOverview()
            #expect(before.exits == after.exits)
            #expect(try await session.transactionHistory().count == transactionsBefore.count)
            try JSONEncoder().encode([
                "amount": "\(quote.amount)", "claimFee": "\(quote.fee)",
                "broadcastFee": "\(quote.chainFee)", "vtxos": "\(ids.count)",
                "exitsBefore": "\(before.exits.count)", "exitsAfter": "\(after.exits.count)",
            ]).write(
                to: URL.documentsDirectory.appending(path: "m6-live-estimate.json"), options: .atomic)
        } else {
            throw OnchainFailure.invalid
        }
    }
}
