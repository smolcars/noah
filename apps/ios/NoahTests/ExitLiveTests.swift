import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// Dedicated, disposable simulator only. Explicit mutation stages authorize regtest broadcasts.
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
            try await create(session: session, settings: settings, seed: seed)
            return
        }
        guard case .found(let mnemonic) = seed.read() else { throw OnchainFailure.unavailable }
        let owned = try String(contentsOf: URL.documentsDirectory.appending(path: "m6-live-owned-wallet.txt"), encoding: .utf8)
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "m6-acceptance")
        guard try await session.summary()?.fingerprint == owned else { throw OnchainFailure.invalid }
        if !["estimate", "uncertain-board", "check-uncertain-board", "cancel-cycle", "cancel-reopen", "board-missing-fee", "reconcile-fee"].contains(stage) {
            try await session.close()
            try await session.ensureOpen(mnemonic: mnemonic, runDaemon: true, appVersion: "m6-acceptance")
        }
        switch stage {
        case "board", "board-missing-fee": try await board(session, stage: stage)
        case "reconcile-fee": try await reconcileFee(session)
        case "uncertain-board": try await uncertainBoard(session)
        case "check-uncertain-board": try await checkUncertainBoard(session)
        case "cancel-cycle", "cancel-reopen": try await cancel(session, stage: stage)
        case "refresh": try await refresh(session)
        case "estimate": try await estimate(session)
        case "start", "observe", "progress", "claim", "max": try await exitStage(session, stage: stage)
        default: throw OnchainFailure.invalid
        }
    }

    private func create(session: WalletSession, settings: SettingsStorage, seed: KeychainPassword) async throws {
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
        let fingerprint = try #require(try await session.summary()?.fingerprint)
        try Data(fingerprint.utf8).write(
            to: URL.documentsDirectory.appending(path: "m6-live-owned-wallet.txt"), options: .atomic)
        let address = try await session.exitDepositAddress()
        try JSONEncoder().encode(["address": address]).write(
            to: URL.documentsDirectory.appending(path: "m6-live-address.json"), options: .atomic)
    }

    private func board(_ session: WalletSession, stage: String) async throws {
        try await session.sync()
        let quote = try await session.quoteOnchain(.board, amount: 30000)
        let chainFee = try #require(quote.chainFee)
        #expect(quote.confirmed >= quote.amount + chainFee)
        // Explicit opt-in stage authorizes only this reviewed 30,000-sat regtest board.
        let receipt = try await session.executeOnchain(quote)
        let arkFee = try #require(quote.fee)
        try JSONEncoder().encode([
            "receipt": receipt, "gross": "\(quote.amount)", "arkFee": "\(arkFee)",
            "chainFee": "\(chainFee)",
        ]).write(to: URL.documentsDirectory.appending(path: "m6-live-board.json"), options: .atomic)
        if stage == "board-missing-fee" {
            let file = WalletDataLocation(variant: .regtest).directory.appending(path: "noah-onchain-attempt.json")
            var attempts = try await OnchainOperationJournal.shared.loadAll()
            let index = try #require(attempts.firstIndex { $0.id == quote.id })
            // Simulate the optional initial fee lookup being unavailable, retaining the real txid.
            attempts[index].receipt?.onchainFee = nil
            attempts[index].receipt?.message = OnchainReceipt.boardingMessage(fee: nil)
            try JSONEncoder().encode(attempts).write(to: file, options: .atomic)
            try JSONEncoder().encode(attempts[index]).write(
                to: URL.documentsDirectory.appending(path: "m6-live-board-missing-fee.json"), options: .atomic)
        } else { try await OnchainOperationJournal.shared.acknowledge(quote.id) }
    }

    private func reconcileFee(_ session: WalletSession) async throws {
        let before = try #require(try await OnchainOperationJournal.shared.load())
        #expect(before.receipt?.onchainFee == nil && before.receipt?.txid != nil)
        let transactionsBefore = try await session.withOpenWallet { try await $0.onchain.transactions().map(\.txid) }
        let model = OnchainModel()
        await model.load()
        let after = try #require(model.attempt)
        #expect(after.receipt?.onchainFee != nil)
        #expect(after.receipt?.txid == before.receipt?.txid)
        let transactionsAfter = try await session.withOpenWallet { try await $0.onchain.transactions().map(\.txid) }
        #expect(Set(transactionsBefore) == Set(transactionsAfter))
        try JSONEncoder().encode(after).write(
            to: URL.documentsDirectory.appending(path: "m6-live-reconcile-fee.json"), options: .atomic)
        try await OnchainOperationJournal.shared.acknowledge(after.id)
    }

    private func uncertainBoard(_ session: WalletSession) async throws {
        try await session.sync()
        let quote = try await session.quoteOnchain(.board, amount: 30000)
        _ = try await session.executeOnchain(quote)
        let file = WalletDataLocation(variant: .regtest).directory.appending(path: "noah-onchain-attempt.json")
        var attempts = try await OnchainOperationJournal.shared.loadAll()
        let index = try #require(attempts.firstIndex { $0.id == quote.id })
        #expect(attempts[index].txid != nil)
        // Simulate loss of the final receipt write after the submitted txid was persisted.
        // No second board call; the next invocation reopens this exact on-disk uncertainty.
        attempts[index].receipt = nil
        try JSONEncoder().encode(attempts).write(to: file, options: .atomic)
        try JSONEncoder().encode(attempts[index]).write(
            to: URL.documentsDirectory.appending(path: "m6-live-uncertain-board.json"), options: .atomic)
    }

    private func checkUncertainBoard(_ session: WalletSession) async throws {
        let attempt = try #require(try await OnchainOperationJournal.shared.loadAll().first {
            $0.quote.action == .board && $0.receipt == nil
        })
        let before = try await session.withOpenWallet { try await $0.onchain.transactions().map(\.txid) }
        let model = OnchainModel()
        await model.load()
        let ids = try #require(model.overview).vtxos.filter(\.eligible).map(\.id)
        #expect(!ids.isEmpty)
        await model.review(.startExit, ids: ids)
        #expect(model.quote?.action == .startExit && !model.locked)
        await model.checkStatus(attempt.id)
        let resolved = try #require(try await OnchainOperationJournal.shared.loadAll().first { $0.id == attempt.id })
        #expect(resolved.receipt?.txid == attempt.txid)
        let after = try await session.withOpenWallet { try await $0.onchain.transactions().map(\.txid) }
        #expect(Set(before) == Set(after))
        try JSONEncoder().encode(resolved).write(
            to: URL.documentsDirectory.appending(path: "m6-live-check-uncertain-board.json"), options: .atomic)
        try await OnchainOperationJournal.shared.acknowledge(attempt.id)
    }

    private func cancel(_ session: WalletSession, stage: String) async throws {
        let marker = URL.documentsDirectory.appending(path: "m6-live-canceled-id.txt")
        let id: String
        if stage == "cancel-cycle" {
            try await session.sync()
            let overview = try await session.onchainOverview()
            guard overview.exits.isEmpty else { throw OnchainFailure.invalid }
            id = try #require(overview.vtxos.first { $0.eligible && $0.amount <= 30000 }).id
            let start = try await session.quoteOnchain(.startExit, ids: [id])
            _ = try await session.executeOnchain(start)
            try await OnchainOperationJournal.shared.acknowledge(start.id)
            let cancel = try await session.quoteOnchain(.cancel, ids: [id])
            _ = try await session.executeOnchain(cancel)
            try await OnchainOperationJournal.shared.acknowledge(cancel.id)
            try Data(id.utf8).write(to: marker, options: .atomic)
        } else {
            id = try String(contentsOf: marker, encoding: .utf8)
        }
        let detail = try #require(try await session.exitDetail(id))
        #expect(detail.state == "Canceled" && !detail.cancelable && !detail.claimable)
        #expect(detail.timeline.last?.state == "Canceled")
        #expect(detail.timeline.contains { $0.state == "Start" })
        #expect(try await session.onchainOverview().exits.contains { $0.id == id && $0.state == "Canceled" })
        let active = try await session.withOpenWallet { try await $0.wallet.getExitVtxos() }
        #expect(!active.contains { $0.vtxoId == id })
        try JSONEncoder().encode([
            "vtxo": id, "state": detail.state,
            "timeline": detail.timeline.map(\.state).joined(separator: ","),
            "activeCount": String(active.count),
        ]).write(to: URL.documentsDirectory.appending(path: "m6-live-\(stage).json"), options: .atomic)
    }

    private func refresh(_ session: WalletSession) async throws {
        try await session.sync()
        let overview = try await session.onchainOverview()
        let refreshable = overview.vtxos.first(where: \.refreshable)
        let vtxo = try #require(refreshable)
        let quote = try await session.quoteOnchain(.refresh, ids: [vtxo.id])
        let receipt = try await session.executeOnchain(quote)
        try JSONEncoder().encode([
            "receipt": receipt, "vtxo": vtxo.id, "amount": "\(quote.amount)", "fee": String(describing: quote.fee),
        ]).write(to: URL.documentsDirectory.appending(path: "m6-live-refresh.json"), options: .atomic)
        try await OnchainOperationJournal.shared.acknowledge()
    }

    private func estimate(_ session: WalletSession) async throws {
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
            "amount": "\(quote.amount)", "claimFee": String(describing: quote.fee),
            "broadcastFee": String(describing: quote.chainFee), "vtxos": "\(ids.count)",
            "exitsBefore": "\(before.exits.count)", "exitsAfter": "\(after.exits.count)",
        ]).write(
            to: URL.documentsDirectory.appending(path: "m6-live-estimate.json"), options: .atomic)
        let fees = try await session.withOpenWallet { handles in
            try await handles.onchain.transactions().map {
                ["txid": $0.txid, "fee": $0.onchainFeeSats.map(String.init) ?? "unknown"]
            }
        }
        try JSONEncoder().encode(fees).write(
            to: URL.documentsDirectory.appending(path: "m6-live-onchain-fees.json"), options: .atomic)
    }

    private func exitStage(_ session: WalletSession, stage: String) async throws {
        try await session.sync()
        let overview = try await session.onchainOverview()
        if stage == "start" {
            guard overview.exits.isEmpty else { throw OnchainFailure.invalid }
            let vtxo = try #require(overview.vtxos.first { $0.eligible && $0.amount <= 30_000 })
            let quote = try await session.quoteOnchain(.startExit, ids: [vtxo.id])
            _ = try await session.executeOnchain(quote)
            try await OnchainOperationJournal.shared.acknowledge()
        } else if stage == "progress" {
            let ids = overview.exits.filter {
                !["Canceled", "Claimed", "VtxoAlreadySpent", "VtxoSwept"].contains($0.state)
            }.map(\.id)
            let quote = try await session.quoteOnchain(.progress, ids: ids)
            _ = try await session.executeOnchain(quote)
            try await OnchainOperationJournal.shared.acknowledge()
        } else if stage == "claim" {
            let ids = overview.exits.filter(\.claimable).map(\.id)
            guard !ids.isEmpty else { throw OnchainFailure.invalid }
            let address = try await session.exitDepositAddress()
            let quote = try await session.quoteOnchain(.claim, ids: ids, destination: address)
            _ = try await session.executeOnchain(quote)
            let attempt = try #require(try await OnchainOperationJournal.shared.load())
            try JSONEncoder().encode(attempt).write(
                to: URL.documentsDirectory.appending(path: "m6-live-claim-receipt.json"), options: .atomic)
            try await OnchainOperationJournal.shared.acknowledge()
        } else if stage == "max" {
            let quote = try await session.quoteOnchain(.board)
            #expect(quote.boardAll == true)
            _ = try await session.executeOnchain(quote)
            let receipt = try #require(try await OnchainOperationJournal.shared.load()?.receipt)
            #expect(receipt.onchainFee != nil)
            try JSONEncoder().encode(receipt).write(
                to: URL.documentsDirectory.appending(path: "m6-live-max.json"), options: .atomic)
            try await OnchainOperationJournal.shared.acknowledge()
        }
        // Observe lets the production daemon progress without a manual progressExits call.
        try await Task.sleep(for: .seconds(20))
        let after = try await session.onchainOverview()
        let entries = after.exits.map {
            ["vtxo": $0.id, "amount": "\($0.amount)", "state": $0.state,
             "claimable": "\($0.claimable)", "cancelable": "\($0.cancelable)",
             "claimableHeight": $0.details.claimable_height.map(String.init) ?? "unknown",
             "details": String(data: (try? JSONEncoder().encode($0.details)) ?? Data(), encoding: .utf8) ?? ""]
        }
        try JSONEncoder().encode(entries).write(
            to: URL.documentsDirectory.appending(path: "m6-live-\(stage).json"), options: .atomic)
    }
}
