import Foundation
@testable import Noah
import Testing

struct OnchainReceiptFeeTests {
    @Test func missingBoardFeeRefreshesByExactTxidAcrossRelaunchWithoutSubmission() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let quote = OnchainJournalTests.quote
        _ = try await journal.perform(quote) {
            .init(message: OnchainReceipt.boardingMessage(fee: nil), txid: "board-txid")
        }
        let restarted = OnchainOperationJournal(file: file)
        try await restarted.refreshBoardFees(walletID: "wallet", fees: ["unrelated-txid": 999])
        #expect(try await restarted.load()?.receipt?.onchainFee == nil)
        try await restarted.refreshBoardFees(walletID: "other-wallet", fees: ["board-txid": 1234])
        #expect(try await restarted.load()?.receipt?.onchainFee == nil)
        try await restarted.refreshBoardFees(walletID: "wallet", fees: ["board-txid": 1543])
        let receipt = try #require(try await OnchainOperationJournal(file: file).load()?.receipt)
        #expect(receipt.txid == "board-txid" && receipt.onchainFee == 1543)
        #expect(receipt.message.contains("Actual onchain fee: 1543 sats."))
        #expect(!receipt.message.contains("not yet available"))
        try await restarted.refreshBoardFees(walletID: "wallet", fees: ["board-txid": 9876])
        #expect(try await restarted.load()?.receipt?.onchainFee == 1543)
    }
    @MainActor
    @Test func modelRetriesOptionalFeeLookupAndRetainsReceiptWhenLookupFails() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        _ = try await journal.perform(OnchainJournalTests.quote) { .init(message: "submitted", txid: "txid") }
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.attempt = { try await journal.loadAll() }
        dependencies.refreshReceipts = { throw OnchainFailure.unavailable }
        let failed = OnchainModel(dependencies: dependencies)
        await failed.load()
        #expect(failed.attempt?.receipt?.txid == "txid" && failed.error == nil)
        dependencies.refreshReceipts = { try await journal.refreshBoardFees(walletID: "wallet", fees: ["txid": 1691]) }
        let relaunched = OnchainModel(dependencies: dependencies)
        await relaunched.load()
        #expect(relaunched.attempt?.receipt?.onchainFee == 1691)
        #expect(await calls.mutations == 0)
    }
}
