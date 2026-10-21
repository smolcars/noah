import BarkBoltFFI
import Foundation
import SQLite3
import Testing

@testable import Noah

struct OnchainOrphanBoardTests {
    @Test(.timeLimit(.minutes(1))) func orphanMovementRemainsUncertainAndExplicitOverrideReleasesBoarding() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let server = try LocalEsploraServer(.notFound)
        defer { server.stop() }
        let config = Config(serverAddress: "http://127.0.0.1:1", esploraAddress: server.url)
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        let session = WalletSession(variant: .regtest, location: location) { mnemonic, datadir, _, _, create in
            let onchain = try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
            let wallet = try await Wallet.openWithOnchain(network: .regtest, mnemonicOrSeed: mnemonic, config: config,
                args: WalletOpenArgs(runDaemon: false, datadir: datadir, createIfNotExists: create,
                    createWithoutServer: true, skipRecovery: true), onchain: onchain)
            return .init(wallet: wallet, onchain: onchain)
        }
        let mnemonic = try generateMnemonic()
        try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        try await session.close()
        let txid = String(repeating: "ab", count: 32)
        // Pinned Bark saves the movement before its board checkpoint and local transaction.
        var db: OpaquePointer?
        try #require(sqlite3_open(location.database.path, &db) == SQLITE_OK)
        let sql = """
            INSERT INTO bark_movements (status, subsystem_name, movement_kind, metadata,
                intended_balance, effective_balance, offchain_fee, created_at, updated_at)
            VALUES ('pending', 'bark.board', 'board', '{"chain_anchor":"\(txid):0"}',
                30000, 29900, 100, '2026-10-21 12:00:00.000', '2026-10-21 12:00:00.000');
            INSERT INTO bark_movements_output_vtxos (movement_id, vtxo_id)
            VALUES (last_insert_rowid(), '\(txid):0');
            """
        let inserted = sqlite3_exec(db, sql, nil, nil, nil)
        sqlite3_close(db)
        try #require(inserted == SQLITE_OK)
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        do {
            let walletID = try await session.withOpenWallet { handles in
                let movements = try await handles.wallet.history()
                #expect(movements.count == 1)
                let movement = try #require(movements.first)
                #expect(HistoryMetadata.parse(movement.metadataJson).chainAnchor == "\(txid):0")
                #expect(movement.outputVtxoIds == ["\(txid):0"])
                await #expect(throws: BarkError.self) { try await handles.wallet.getVtxoById(vtxoId: "\(txid):0") }
                #expect(try await handles.wallet.pendingBoards().isEmpty)
                #expect(try await handles.onchain.transactions().isEmpty)
                #expect(try await handles.wallet.transactionStatus(txid: txid) == .notFound)
                return handles.wallet.fingerprint()
            }
            var quote = BoardPreparedTests.quote
            quote.walletID = walletID
            quote.prepared?.txid = txid
            let file = root.appending(path: "attempt.json")
            let journal = OnchainOperationJournal(file: file)
            let reviewed = quote
            await #expect(throws: OnchainFailure.uncertain) {
                try await journal.perform(reviewed) {
                    try await journal.recordTransaction(txid, for: reviewed.id)
                    throw OnchainFailure.uncertain
                }
            }
            let restarted = OnchainOperationJournal(file: file)
            #expect(try await session.autoBoardFunding(journal: restarted) == nil)
            #expect(try await !session.checkOnchainAttempt(reviewed.id, journal: restarted))
            let uncertain = try #require(try await restarted.load())
            #expect(uncertain.receipt == nil && uncertain.txid == txid)
            #expect(uncertain.blocks(.board, ids: []))
            try await restarted.discardUncertain(reviewed.id)
            let overridden = try #require(try await OnchainOperationJournal(file: file).load())
            #expect(overridden.overrideAudit != nil && !overridden.blocks(.board, ids: []))
            var next = reviewed
            next.id = UUID()
            _ = try await restarted.perform(next) { "next board admitted after explicit override" }
        } catch {
            try await session.close()
            throw error
        }
        try await session.close()
    }

    @Test(arguments: ["checkpoint", "bdk", "mempool", "chain"])
    func realSubmissionEvidenceStillCreatesReceipt(source: String) async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let quote = BoardPreparedTests.quote
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(quote) {
                try await journal.recordTransaction("exact", for: quote.id)
                throw OnchainFailure.uncertain
            }
        }
        let reads = WalletSession.OnchainAttemptReads(walletID: "wallet",
            transactionIDs: { ["checkpoint", "bdk"].contains(source) ? ["exact"] : [] },
            vtxoState: { _ in Issue.record("No VTXO matching for boards"); return "Spendable" },
            exitState: { _ in nil }, transactionStatus: { _ in
                source == "chain" ? .confirmed(100) : source == "mempool" ? .inMempool : .notFound
            })
        #expect(try await WalletSession.checkOnchainAttempt(quote.id, journal: journal, reads: reads))
        let saved = try #require(try await journal.load())
        #expect(saved.receipt?.txid == "exact")
        #expect(saved.receipt?.broadcastRetryPending == (source == "chain" ? nil : true))
        #expect(saved.blocks(.board, ids: []) == (source != "chain"))
        await #expect(throws: OnchainFailure.busy) { try await journal.discardUncertain(quote.id) }
    }
}
