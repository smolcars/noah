import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

@MainActor struct PaymentParityTests {
    struct ReceiptCase: Decodable {
        let gross: UInt64, arkFee: UInt64, chainFee: UInt64
        let actualChainFee: UInt64?
        let expected: OnchainReceipt.BoardAmounts
    }
    struct Table: Decodable {
        let copy: [String: String]
        let receipts: [ReceiptCase]
    }
    @Test func sharedCopyAndBoardReceiptTotals() throws {
        let table = try Fixtures.decode("payment-status-copy", as: Table.self)
        let actual = try ["notFound": ChainTransactionStatus.notFound.message,
                          "inMempool": ChainTransactionStatus.inMempool.message,
                          "confirmed": ChainTransactionStatus.confirmed(123).message,
                          "lookupError": ChainTransactionStatus.errorText,
                          "insufficientFunds": #require(OnchainFailure.insufficientFunds.errorDescription),
                          "belowMinimum": #require(OnchainFailure.belowMinimum.errorDescription),
                          "reservedInputs": #require(OnchainFailure.reservedInputs.errorDescription),
                          "tooManyInputs": #require(OnchainFailure.tooManyInputs.errorDescription),
                          "notSynced": #require(OnchainFailure.notSynced.errorDescription),
                          "boardNotAttempted": #require(OnchainFailure.boardNotAttempted.errorDescription),
                          "broadcastFailed": OnchainReceipt.broadcastFailedMessage,
                          "broadcastPending": SendReceipt.broadcastPendingMessage,
                          "submitted": SendReceipt.submittedMessage, "uncertain": SendReceipt.uncertainMessage]
        #expect(actual == table.copy)
        for row in table.receipts {
            let quote = OnchainQuote(
                action: .board,
                walletID: "wallet",
                ids: [],
                amount: row.gross,
                fee: row.arkFee,
                chainFee: row.chainFee,
                confirmed: 100_000)
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
    /// An uncertain Lightning payment has no txid, so no periodic check: an entry check
    /// skipped inside the 30 s window still runs once the window passes.
    @Test func aSkippedEntryCheckRunsOnceTheWindowPasses() async {
        let checks = RecurringExecutorTests.Locked(0)
        let storage = SendPaymentTests.Storage()
        storage.attempt = .init(payment: SendPayment(destination: SendReceiveShotTests.invoice, method: "lightning", amount: 1_500),
                                snapshot: .init(movementIDs: []))
        let controller = SendPaymentController(dependencies: .init(
            prepare: { _, _ in SendSnapshot(movementIDs: []) }, pay: { _, _, _ in SendReceipt(amount: 1_500) },
            reconcile: { _ in checks.withLock { $0 += 1 }; return .uncertain },
            read: { storage.attempt }, write: { try storage.write($0) }))
        #expect(controller.state == .uncertain && controller.attempt?.receipt?.txid == nil)
        let model = SendModel(controller: controller)
        let start = Date()
        await model.automaticStatusCheck(at: start)
        #expect(checks.withLock { $0 } == 1)
        // Back at Send 10 s later: nothing now, then the check once the window has passed.
        var now = start.addingTimeInterval(10)
        var slept: [Duration] = []
        await model.entryStatusCheck(now: { now }, sleep: { duration in
            slept.append(duration)
            now += Double(duration.components.seconds) + 0.01
        })
        #expect(slept == [.seconds(20)])
        #expect(checks.withLock { $0 } == 2 && model.lastAutomaticCheck == now)
        // Once due, it checks at once.
        now += 31
        await model.entryStatusCheck(now: { now }, sleep: { _ in Issue.record("Due checks don't wait") })
        #expect(checks.withLock { $0 } == 3)
    }
    /// Leaving Send while the tip is read ends observation before any check.
    @Test func leavingSendWhileTheTipIsReadStartsNoCheck() async {
        let checks = RecurringExecutorTests.Locked(0)
        let observation = Task {
            await SendReceiptRefresh.observe(tip: { withUnsafeCurrentTask { $0?.cancel() } }, check: { checks.withLock { $0 += 1 } },
                                             sleep: { _ in })
        }
        await observation.value
        #expect(checks.withLock { $0 } == 0)
    }
    /// The entry check runs while the tip is read: the periodic one waits out its window.
    @Test func anEntryCheckDuringTheTipReadIsNotFollowedByAnother() async {
        var events: [String] = []
        var sleeps: [Duration] = []
        var last: Double?
        await SendReceiptRefresh.observe(wait: { last.map { .seconds(max(0, 30 - (0 - $0))) } ?? .zero },
                                         tip: { events.append("tip"); last = 0 },
                                         check: { events.append("check") },
                                         sleep: { duration in sleeps.append(duration); throw CancellationError() })
        #expect(events == ["tip"] && sleeps == [.seconds(30)])
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
