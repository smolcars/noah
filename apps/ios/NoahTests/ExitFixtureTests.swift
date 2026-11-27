import Foundation
@testable import Noah
import Testing

struct ExitFixtureTests {
    struct FeeInput: Decodable {
        var operation: String
        var state: String?
        var amount: UInt64?
        var claimFee: UInt64?
        var totalFee: UInt64?
        var claimOnly: Bool? // swiftlint:disable:this discouraged_optional_boolean - fixture field only some operations carry
        var reviewedAt: Double?
        var now: Double?
        var inputs: ExitReviewInputs?
        var current: ExitReviewInputs?
    }
    @Test func feesMatchRN() throws {
        for test in try Fixtures.load(
            "exit-fee-estimate", input: FeeInput.self, expected: SendFixtureTests.JSON.self) {
            let i = test.input
            switch i.operation {
            case "eligible":
                let state = try #require(i.state), amount = try #require(i.amount)
                #expect(
                    try ExitRules.eligible(state: state, amount: amount)
                        == test.expected.decode(Bool.self), "\(test.name)")
            case "receive":
                let amount = try #require(i.amount), claimFee = try #require(i.claimFee)
                #expect(
                    try ExitRules.received(amount: amount, claimFee: claimFee)
                        == test.expected.decode(UInt64.self), "\(test.name)")
            case "warning":
                let amount = try #require(i.amount), claimFee = try #require(i.claimFee)
                let totalFee = try #require(i.totalFee)
                try #require(i.claimOnly != nil)
                let claimOnly = i.claimOnly == true
                #expect(
                    try ExitRules.warning(
                        amount: amount, claimFee: claimFee, totalFee: totalFee, claimOnly: claimOnly) == test.expected.decode(String?.self),
                    "\(test.name)")
            case "current":
                let inputs = try #require(i.inputs), current = try #require(i.current)
                let now = try #require(i.now), reviewedAt = try #require(i.reviewedAt)
                let actual = inputs.matches(current) && ExitRules.current(elapsed: .milliseconds(now - reviewedAt))
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
            "exit-timeline", input: TimelineInput.self, expected: [TimelineExpected].self) {
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
            "exit-overview-coordination", input: ReadyInput.self, expected: ReadyExpected.self) {
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

    struct UtxoFixtureItem: Decodable {
        var txid: String
        var vout: UInt32
        var amount: UInt64
        var confirmationHeight: UInt32?
        var model: OnchainUtxoModel { .init(txid: txid, vout: vout, amount: amount, confirmationHeight: confirmationHeight) }
    }
    struct UtxoSelectionInput: Decodable {
        var operation: String
        // swiftlint:disable discouraged_optional_collection - fixture inputs only include fields for their operation
        var utxos: [UtxoFixtureItem]?
        var selected: [String]?
        var preparedInputs: [String]?
        // swiftlint:enable discouraged_optional_collection
        // swiftlint:disable:next discouraged_optional_boolean - fixture inputs only include drainAll for inputsValid
        var drainAll: Bool?
    }
    enum UtxoSelectionExpected: Decodable, Equatable {
        case bool(Bool)
        case list([String])
        case null
        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if container.decodeNil() { self = .null }
            else if let flag = try? container.decode(Bool.self) { self = .bool(flag) }
            else { self = .list(try container.decode([String].self)) }
        }
    }
    @Test func boardUtxoSelectionMatchesFixture() throws {
        for test in try Fixtures.load(
            "board-utxo-selection", input: UtxoSelectionInput.self, expected: UtxoSelectionExpected.self) {
            let i = test.input
            let models = (i.utxos ?? []).map(\.model)
            switch i.operation {
            case "sort":
                #expect(UtxoSelectionExpected.list(BoardUtxoSelection.sorted(models).map(\.id)) == test.expected, "\(test.name)")
            case "exclusions":
                let actual = BoardUtxoSelection.excludedOutpoints(utxos: models, selected: i.selected ?? []).map { $0.sorted() }
                #expect((actual.map(UtxoSelectionExpected.list) ?? .null) == test.expected, "\(test.name)")
            case "inputsValid":
                let valid = BoardUtxoSelection.inputsValid(
                    preparedInputs: Set(i.preparedInputs ?? []), selected: Set(i.selected ?? []), drainAll: i.drainAll == true)
                #expect(UtxoSelectionExpected.bool(valid) == test.expected, "\(test.name)")
            case "prune":
                let kept = BoardUtxoSelection.prune(Set(i.selected ?? []), in: models).sorted()
                #expect(UtxoSelectionExpected.list(kept) == test.expected, "\(test.name)")
            default: Issue.record("Unknown operation \(i.operation)")
            }
        }
    }
}
