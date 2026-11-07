import Foundation
@testable import Noah
import Testing

/// `apps/fixtures/assistant-replies.json`: the English Siri says, as Android's Gemini actions say it.
struct AssistantReplyTests {
    struct Input: Decodable {
        var kind: String
        var sats: Int64?
        var fiat: String?
        var address: String?
        var direction: String?
        var counterparty: String?
        var when: String?
    }

    @Test func matchesTheSharedFixture() throws {
        let cases = try Fixtures.load("assistant-replies", input: Input.self, expected: String.self)
        #expect(cases.count == 14)
        var phaseTwo = 0
        for testCase in cases {
            let input = testCase.input
            let reply: String
            switch input.kind {
            case "balance": reply = AssistantReply.balance(sats: UInt64(try #require(input.sats)), fiat: input.fiat, locale: "en")
            case "request": reply = AssistantReply.request(sats: try #require(input.sats), locale: "en")
            case "arkAddress": reply = AssistantReply.arkAddress(locale: "en")
            case "lightningAddress": reply = AssistantReply.lightningAddress(input.address, locale: "en")
            case "lastPayment":
                let payment = try input.direction.map { direction in
                    AssistantReply.Payment(incoming: direction == "incoming", sats: try #require(input.sats), counterparty: input.counterparty,
                                           when: try #require(input.when))
                }
                reply = AssistantReply.lastPayment(payment, locale: "en")
            case "pay":
                // Pay someone arrives with Phase 2.
                phaseTwo += 1
                continue
            default:
                Issue.record("Unknown kind \(input.kind) in \(testCase.name)")
                continue
            }
            #expect(reply == testCase.expected, "\(testCase.name)")
        }
        #expect(phaseTwo == 1)
    }

    @Test func repliesFollowTheAppLanguage() {
        let grouped = 694_375.formatted(.number.locale(Locale(identifier: "sv")))
        #expect(AssistantReply.balance(sats: 694_375, fiat: nil, locale: "sv") == "Du har \(grouped) sats.")
        #expect(AssistantReply.lastPayment(.init(incoming: true, sats: 5000, counterparty: nil, when: "2時間前"), locale: "ja")
            == "2時間前、5,000 satsを受け取りました。")
    }

    private func row(_ id: String, direction: String = "incoming", amount: Int64 = 5000, date: String = "2026-11-06T10:00:00.000Z",
                     source: String = "ark", kind: String? = nil, status: String? = "successful", destination: String? = nil) -> HistoryTransaction {
        HistoryTransaction(id: id, type: "Arkoor", amount: amount, date: date, direction: direction, source: source, destination: destination,
                           movementStatus: status, movementKind: kind)
    }

    @Test func lastPaymentIsTheNewestSettledPayment() throws {
        let now = try #require(HistoryRules.parseDate("2026-11-06T12:00:00.000Z"))
        let history = [
            row("older", amount: 1, date: "2026-11-01T12:00:00.000Z"),
            row("sent", direction: "outgoing", amount: 21000, date: "2026-11-05T09:00:00.000Z", destination: "Lightning:SAM@noahwallet.com"),
            // Newer, but not payments: a renewal, a move to Ark, an exit, and pending, failed and canceled rows.
            row("renewal", direction: "outgoing", date: "2026-11-06T11:00:00.000Z", kind: "refresh"),
            row("board", direction: "incoming", date: "2026-11-06T11:10:00.000Z", kind: "onboard"),
            row("exit", direction: "outgoing", date: "2026-11-06T11:20:00.000Z", kind: "exit"),
            row("pending", date: "2026-11-06T11:30:00.000Z", status: "pending"),
            row("failed", direction: "outgoing", date: "2026-11-06T11:40:00.000Z", status: "failed"),
            row("canceled", direction: "outgoing", date: "2026-11-06T11:50:00.000Z", status: "canceled"),
        ]
        let sent = try #require(AssistantReply.lastPayment(in: history, now: now, locale: "en"))
        #expect(sent == .init(incoming: false, sats: 21000, counterparty: "sam@noahwallet.com", when: "yesterday"))
        #expect(AssistantReply.lastPayment(sent, locale: "en") == "You sent 21,000 sats to sam@noahwallet.com yesterday.")

        let received = try #require(AssistantReply.lastPayment(in: history + [row("new", date: "2026-11-06T10:00:00.000Z")], now: now, locale: "en"))
        #expect(AssistantReply.lastPayment(received, locale: "en") == "You received 5,000 sats 2 hours ago.")
        #expect(AssistantReply.lastPayment(in: Array(history.dropFirst(2)), now: now, locale: "en") == nil)
    }

    @Test func failuresSayWhatToDoNext() {
        #expect(AssistantFailure(AppServices.WalletSuspended()) == .suspended)
        #expect(AssistantFailure(AppServices.SeedUnavailable()) == .notReady)
        #expect(AssistantFailure(AppServices.RestoreNotSettled()) == .notReady)
        #expect(AssistantFailure(WalletSession.OpenError.walletDatabaseMissing) == .notReady)
        #expect(AssistantFailure(WalletSession.OpenError.busy) == .busy)
        #expect(AssistantFailure(URLError(.notConnectedToInternet)) == .unreachable)
    }
}
