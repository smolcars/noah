import Foundation
import Testing

@testable import Noah

/// Reviews time out on the continuous clock: moving the device clock back or forward during a review never
/// lengthens it. A review read back from storage has no continuous-clock reading and keeps its wall-clock cap.
@MainActor
struct ReviewClockTests {
    /// How far the device clock moved after the review opened.
    nonisolated static let clockMoves: [TimeInterval] = [-86_400, -300, 300, 86_400]

    /// A review opened `elapsed` seconds ago, as the continuous clock and the moved device clock record it.
    static func opened(_ elapsed: Double, clockMoved move: TimeInterval) -> (ContinuousClock.Instant?, Date) {
        (ContinuousClock.now - .seconds(elapsed), Date().addingTimeInterval(-elapsed - move))
    }

    @Test(arguments: clockMoves) func sendBoardAndExitReviewsExpireOnTheContinuousClock(move: TimeInterval) {
        var send = SendPreparedTests.quote.prepared!
        var board = BoardPreparedTests.quote
        var exit = OnchainJournalTests.quote
        exit.action = .startExit
        for (elapsed, current) in [(119.0, true), (120.0, false)] {
            let (reviewedAt, createdAt) = Self.opened(elapsed, clockMoved: move)
            send.reviewedAt = reviewedAt; send.createdAt = createdAt
            board.reviewedAt = reviewedAt; board.createdAt = createdAt
            board.prepared?.reviewedAt = reviewedAt; board.prepared?.createdAt = createdAt
            #expect(send.isCurrent == current && board.isCurrent == current)
        }
        for (elapsed, current) in [(29.0, true), (30.0, false)] {
            (exit.reviewedAt, exit.createdAt) = Self.opened(elapsed, clockMoved: move)
            #expect(exit.isCurrent == current)
        }
    }

    @Test func aSendReviewCantBeConfirmedAfterItsDeadlineWhenTheClockWasSetBack() async {
        let session = WalletSession()
        var quote = SendPreparedTests.quote
        let (reviewedAt, createdAt) = Self.opened(120, clockMoved: -120)
        quote.prepared?.reviewedAt = reviewedAt; quote.prepared?.createdAt = createdAt
        await session.retainSendReview(SendPreparedTests.payment, quote: quote, discard: {})
        await #expect(throws: SendFailure.feeChanged) {
            try await session.claimSendReview(SendPreparedTests.payment, quote: quote)
        }
        await session.discardSend(quote)
        #expect(await session.sendPreparations.isEmpty)
    }

    @Test func anExitStartCantBeConfirmedAfterItsDeadlineWhenTheClockWasSetBack() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let access = ExitTypedErrorTests.access { _ in ExitTypedErrorTests.fee }
        var quote = try await WalletSession.startQuote(ids: ["a"], access: access)
        #expect(quote.reviewedAt != nil && quote.isCurrent)
        (quote.reviewedAt, quote.createdAt) = Self.opened(30, clockMoved: -30)
        await #expect(throws: OnchainFailure.changed) {
            try await WalletSession.executeStart(quote, journal: OnchainOperationJournal(file: file), access: access)
        }
        #expect(try await OnchainOperationJournal(file: file).loadAll().isEmpty)
    }

    @Test func aRenewalCantBeConfirmedAfterItsDeadlineWhenTheClockWasSetBack() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        var quote = RenewSubmissionTests.quote
        (quote.reviewedAt, quote.createdAt) = Self.opened(30, clockMoved: -30)
        let stale = quote
        await #expect(throws: OnchainFailure.changed) {
            try await WalletSession.submitRenewal(
                stale, threshold: 144, journal: OnchainOperationJournal(file: file), flight: WalletSync(),
                requote: { stale }, expiring: { _ in [RenewSubmissionTests.coin] }, tip: { 1_000 },
                refresh: { _ in Issue.record("A stale review never refreshes"); return OnchainReceipt(message: "submitted") })
        }
    }

    @Test func theContinuousClockReadingIsNeverPersisted() throws {
        var board = BoardPreparedTests.quote
        board.reviewedAt = .now
        let data = try JSONEncoder().encode(board)
        #expect(!String(decoding: data, as: UTF8.self).contains("reviewedAt"))
        #expect(!String(decoding: try JSONEncoder().encode(SendPreparedTests.quote), as: UTF8.self).contains("reviewedAt"))
        let decoded = try JSONDecoder().decode(OnchainQuote.self, from: data)
        #expect(decoded.reviewedAt == nil && decoded.prepared?.reviewedAt == nil && decoded.createdAt == board.createdAt)
        // Read back, an exit review keeps the wall-clock cap it was saved with.
        var exit = decoded
        exit.action = .startExit; exit.prepared = nil
        for (age, current) in [(29.0, true), (30.0, false), (-1.0, false)] {
            exit.createdAt = Date().addingTimeInterval(-age)
            #expect(exit.isCurrent == current)
        }
    }
}
