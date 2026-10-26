import Foundation
import Testing

@testable import Noah

struct ExitFixtureTests {
    struct FeeInput: Decodable {
        var operation: String
        var state: String?
        var amount: UInt64?
        var claimFee: UInt64?
        var totalFee: UInt64?
        var claimOnly: Bool?
        var reviewedAt: Double?
        var now: Double?
        var inputs: ExitReviewInputs?
        var current: ExitReviewInputs?
    }
    @Test func feesMatchRN() throws {
        for test in try Fixtures.load(
            "exit-fee-estimate", input: FeeInput.self, expected: SendFixtureTests.JSON.self)
        {
            let i = test.input
            switch i.operation {
            case "eligible":
                #expect(
                    try ExitRules.eligible(state: i.state!, amount: i.amount!)
                        == test.expected.decode(Bool.self), "\(test.name)")
            case "receive":
                #expect(
                    try ExitRules.received(amount: i.amount!, claimFee: i.claimFee!)
                        == test.expected.decode(UInt64.self), "\(test.name)")
            case "warning":
                #expect(
                    try ExitRules.warning(
                        amount: i.amount!, claimFee: i.claimFee!, totalFee: i.totalFee!, claimOnly: i.claimOnly!
                    ) == test.expected.decode(String?.self), "\(test.name)")
            case "current":
                let actual =
                    i.inputs!.matches(i.current!)
                    && ExitRules.current(elapsed: .milliseconds(i.now! - i.reviewedAt!))
                #expect(try actual == test.expected.decode(Bool.self), "\(test.name)")
            default: Issue.record("Unknown exit fixture")
            }
        }
    }
    struct CancelInput: Decodable {
        var state: String
        var details: ExitDetails
    }
    @Test func cancellationMatchesRN() throws {
        for test in try Fixtures.load("exit-cancellation", input: CancelInput.self, expected: Bool.self) {
            #expect(
                ExitRules.cancelable(
                    state: test.input.state,
                    finalTransactionKind: test.input.details.transactions?.last?.status.kind)
                    == test.expected, "\(test.name)")
        }
    }
    struct TimelineInput: Decodable {
        var history: [String]
        var historyDetails: [ExitDetails]
        var currentState: String
        var currentDetails: ExitDetails
    }
    struct TimelineExpected: Decodable {
        var state: String
        var label: String
        var count: Int
        var startHeight: UInt32?
        var endHeight: UInt32?
        var isCurrent: Bool
    }
    @Test func timelinesMatchRN() throws {
        for test in try Fixtures.load(
            "exit-timeline", input: TimelineInput.self, expected: [TimelineExpected].self)
        {
            let i = test.input
            let actual = ExitTimeline.build(
                history: i.history, historyDetails: i.historyDetails, state: i.currentState,
                details: i.currentDetails)
            #expect(actual.count == test.expected.count)
            for (item, expected) in zip(actual, test.expected) {
                #expect(
                    item.state == expected.state && item.label == expected.label
                        && item.count == expected.count, "\(test.name)")
                #expect(
                    item.startHeight == expected.startHeight && item.endHeight == expected.endHeight
                        && item.isCurrent == expected.isCurrent)
            }
        }
    }
    struct ReadyInput: Decodable {
        var isInitialized: Bool
        var isWalletLoaded: Bool
        var isWalletSuspended: Bool
        var isBackgroundJobRunning: Bool
    }
    struct ReadyExpected: Decodable {
        var enabled: Bool
        var succeeds: Bool
        var reads: [String]
    }
    @Test func overviewCoordinationMatchesRN() throws {
        for test in try Fixtures.load(
            "exit-overview-coordination", input: ReadyInput.self, expected: ReadyExpected.self)
        {
            let i = test.input
            let ready = ExitRules.ready(
                initialized: i.isInitialized, loaded: i.isWalletLoaded, suspended: i.isWalletSuspended,
                background: i.isBackgroundJobRunning)
            #expect(ready == test.expected.enabled && ready == test.expected.succeeds)
            #expect(ready == !test.expected.reads.isEmpty)
        }
    }
    struct BoardInput: Decodable {
        var minimum: UInt64
        var balance: UInt64
        var arkFee: UInt64
        var chainFee: UInt64
    }
    @Test func autoBoardingMatchesRN() throws {
        for test in try Fixtures.load("auto-boarding", input: BoardInput.self, expected: UInt64?.self) {
            let i = test.input
            #expect(
                BoardingRules.autoAmount(
                    balance: i.balance, minimum: i.minimum, onchainFee: i.chainFee, arkFee: i.arkFee)
                    == test.expected, "\(test.name)")
        }
        #expect(BoardingRules.onchainFee(satsPerKwu: 250) == 208)
        #expect(BoardingRules.onchainFee(satsPerKwu: 375) == 312)
    }
}
