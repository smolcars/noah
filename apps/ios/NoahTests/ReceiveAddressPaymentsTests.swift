import Foundation
@testable import Noah
import Testing

/// Payments through your Lightning address that Receive shows arriving, from the fixture Android runs too.
struct ReceiveAddressPaymentsTests {
    struct Row: Decodable {
        let id: String
        let kind: String
        let status: String
        let direction: String
        let amount: Int64
        // swiftlint:disable:next discouraged_optional_collection - mirrors the fixture, where absent means none
        let receivedOn: [String]?
        let paymentHash: String?

        var transaction: HistoryTransaction {
            HistoryTransaction(id: id, type: "Bolt11", amount: amount, date: "2026-11-25T10:00:00Z", direction: direction,
                               movementStatus: status, movementKind: kind,
                               receivedOn: receivedOn?.map { .init(destination: $0, amount_sat: 1_000, paymentMethod: "ark") },
                               paymentHash: paymentHash)
        }
    }
    struct Input: Decodable {
        let rows: [Row]
        // swiftlint:disable discouraged_optional_collection - absent in the cases that only check `settled`
        let settled: [String]?
        let ownArk: [String]?
        let ownHashes: [String]?
        // swiftlint:enable discouraged_optional_collection
    }
    struct Expected: Decodable {
        let next: String?
        // swiftlint:disable:next discouraged_optional_collection - present only in the cases that check it
        let settled: [String]?
    }

    @Test func matchesSharedFixture() throws {
        for fixture in try Fixtures.load("receive-address-payments", input: Input.self, expected: Expected.self) {
            let rows = fixture.input.rows.map(\.transaction)
            if let settled = fixture.expected.settled {
                #expect(ReceiveAddressPayments.settled(rows) == Set(settled), "\(fixture.name)")
                continue
            }
            let next = ReceiveAddressPayments.next(rows, settled: Set(fixture.input.settled ?? []), ownArk: Set(fixture.input.ownArk ?? []),
                                                   ownHashes: Set(fixture.input.ownHashes ?? []))
            #expect(next?.id == fixture.expected.next, "\(fixture.name)")
        }
    }

    /// A payment through your Lightning address leaves the shown request (an invoice, say) in place; one to it uses it up.
    @Test func onlyAPaymentToTheRequestUsesItUp() {
        #expect(!ReceiveArrival(rail: .lightning, sats: 1, start: Date(), viaAddress: true).usesUpRequest)
        #expect(!ReceiveArrival(rail: .ark, sats: 1, start: Date(), viaAddress: true).usesUpRequest)
        #expect(ReceiveArrival(rail: .lightning, sats: 1, start: Date()).usesUpRequest)
        #expect(!ReceiveArrival(rail: .onchain, sats: 1, start: Date()).usesUpRequest)
    }

    @Test func aPaymentPlaysTheRailItCameIn() {
        let lightning = HistoryTransaction(id: "a", type: "Bolt11", amount: 1, date: "", direction: "incoming", movementKind: "lightning-receive")
        let ark = HistoryTransaction(id: "b", type: "Arkoor", amount: 1, date: "", direction: "incoming", movementKind: "arkoor-receive")
        #expect(ReceiveAddressPayments.rail(of: lightning) == .lightning && ReceiveAddressPayments.rail(of: ark) == .ark)
    }
}
