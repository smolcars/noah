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
    @Test func sendReceiptRefreshesImmediatelyThenEveryTenSecondsUntilCancellation() async {
        var checks = 0
        var sleeps: [Duration] = []
        await SendReceiptRefresh.observe(check: { checks += 1 }, sleep: { duration in
            sleeps.append(duration)
            if sleeps.count == 2 { throw CancellationError() }
        })
        #expect(checks == 2 && sleeps == [.seconds(10), .seconds(10)])
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
