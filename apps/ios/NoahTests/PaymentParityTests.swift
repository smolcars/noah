import BarkBoltFFI
import Foundation
import Testing
@testable import Noah

@MainActor struct PaymentParityTests {
    struct Table: Decodable {
        let copy: [String: String]
        struct Receipt: Decodable {
            let gross: UInt64, arkFee: UInt64, chainFee: UInt64
            let actualChainFee: UInt64?
            let expected: OnchainReceipt.BoardAmounts
        }
        let receipts: [Receipt]
    }
    @Test func sharedCopyAndBoardReceiptTotals() throws {
        let table = try Fixtures.decode("payment-status-copy", as: Table.self)
        let actual = ["notFound": ChainTransactionStatus.notFound.message,
            "inMempool": ChainTransactionStatus.inMempool.message,
            "confirmed": ChainTransactionStatus.confirmed(123).message,
            "lookupError": ChainTransactionStatus.errorText,
            "insufficientFunds": OnchainFailure.insufficientFunds.errorDescription!,
            "belowMinimum": OnchainFailure.belowMinimum.errorDescription!,
            "reservedInputs": OnchainFailure.reservedInputs.errorDescription!,
            "tooManyInputs": OnchainFailure.tooManyInputs.errorDescription!,
            "notSynced": OnchainFailure.notSynced.errorDescription!,
            "boardNotAttempted": OnchainFailure.boardNotAttempted.errorDescription!,
            "broadcastFailed": OnchainReceipt.broadcastFailedMessage,
            "broadcastPending": SendReceipt.broadcastPendingMessage,
            "submitted": SendReceipt.submittedMessage, "uncertain": SendReceipt.uncertainMessage]
        #expect(actual == table.copy)
        for row in table.receipts {
            let quote = OnchainQuote(action: .board, walletID: "wallet", ids: [], amount: row.gross, fee: row.arkFee, chainFee: row.chainFee, confirmed: 100_000)
            let receipt = OnchainReceipt(message: "Submitted", onchainFee: row.actualChainFee)
            #expect(receipt.boardAmounts(quote: quote) == row.expected)
        }
    }
    @Test func sendReceiptRefreshesImmediatelyThenEveryThirtySecondsUntilCancellation() async {
        var events: [String] = []
        var sleeps: [Duration] = []
        var now = 0.0, last: Double?
        let wait = { last.map { Duration.seconds(max(0, 30 - (now - $0))) } ?? .zero }
        await SendReceiptRefresh.observe(wait: wait, tip: { events.append("tip") }, check: { events.append("check"); last = now }, sleep: { duration in
            sleeps.append(duration)
            now += Double(duration.components.seconds)
            if sleeps.count == 2 { throw CancellationError() }
        })
        #expect(events == ["tip", "check", "tip", "check"] && sleeps == [.seconds(30), .seconds(30)])
        // Returning 10 s after the last automatic check waits out the rest; no extra check.
        events = []
        sleeps = []
        now = 10
        last = 0
        await SendReceiptRefresh.observe(wait: wait, check: { events.append("check") }, sleep: { duration in
            sleeps.append(duration)
            throw CancellationError()
        })
        #expect(events.isEmpty && sleeps == [.seconds(20)])
    }
    @Test func sendsEntryAndPeriodicStatusChecksShareOneClock() async {
        let storage = SendPaymentTests.Storage()
        storage.attempt = .init(payment: SendPaymentTests().payment, snapshot: .init(movementIDs: []))
        let model = SendModel(controller: SendPaymentTests().make(.init(), storage))
        let entry = Date()
        #expect(model.untilAutomaticCheck(at: entry) == .zero)
        await model.automaticStatusCheck(at: entry)
        #expect(model.untilAutomaticCheck(at: entry.addingTimeInterval(10)) == .seconds(20))
        #expect(model.untilAutomaticCheck(at: entry.addingTimeInterval(31)) == .zero)
    }
    @Test func sendLookupErrorsDoNotSurviveSerialization() throws {
        let receipt = SendReceipt(amount: 1000, txid: "exact", status: .submitted,
            proof: .preparedTxid, broadcastRetryPending: true, chainStatus: .inMempool,
            chainStatusError: ChainTransactionStatus.errorText)
        let restored = try JSONDecoder().decode(SendReceipt.self, from: JSONEncoder().encode(receipt))
        #expect(restored.chainStatusError == nil)
        #expect(restored.txid == "exact" && restored.chainStatus == .inMempool && restored.broadcastRetryPending == true)
    }
    @Test func boardTypedInsufficientFundsUsesSameRuleBeforeAndAfterReview() {
        let error = BarkError(message: "not for UI", code: .insufficientFunds)
        #expect(OnchainSubmission.boardFailure(error) == .insufficientFunds)
        #expect(OnchainSubmission.preBroadcast(error, action: .board) == .insufficientFunds)
    }
}
