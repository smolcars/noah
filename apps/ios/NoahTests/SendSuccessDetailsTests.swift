import Foundation
import Testing
@testable import Noah

struct SendSuccessDetailsTests {
    private let date = Date(timeIntervalSince1970: 1_700_000_000)
    private func history(id: UInt32 = 7, status: String = "successful", preimage: String? = "proof") -> HistoryTransaction {
        HistoryTransaction(id: "movement-\(id)", type: "Bolt11", amount: 1000, date: HistoryRules.isoDate(date),
                           direction: "outgoing", movementId: id, movementStatus: status, preimage: preimage)
    }
    @Test(arguments: ["lightning", "lnurl", "offer"])
    func lightningProofFromExactSuccessfulMovement(method: String) {
        let details = SendSuccessDetails(receipt: .init(amount: 1000, movementID: 7),
                                        payment: .init(destination: "invoice", method: method, amount: 1000),
                                        history: [history(id: 8, preimage: "unrelated"), history()], completedAt: .distantFuture)
        #expect(details.proof == .init(label: "Payment preimage", value: "proof"))
        #expect(details.paidVia == "Lightning")
        #expect(details.destination == "invoice")
        #expect(details.completedAt == date)
    }
    @Test(arguments: ["onchain", "ark", "lightning"])
    func txidTakesPriority(method: String) {
        let details = SendSuccessDetails(receipt: .init(amount: 1000, txid: "txid", movementID: 7),
                                        payment: .init(destination: "destination", method: method, amount: 1000),
                                        history: [history()], completedAt: date)
        #expect(details.proof == .init(label: "Transaction ID", value: "txid"))
        #expect(details.paidVia == (method == "ark" ? "Ark" : method == "onchain" ? "Onchain" : "Lightning"))
    }
    @Test func missingOrUnrelatedOrUnsuccessfulProofIsOmitted() {
        let receipt = SendReceipt(amount: 1000, txid: "", movementID: 7)
        let payment = SendPayment(destination: "invoice", method: "lightning", amount: 1000)
        for rows in [[], [history(id: 8)], [history(status: "pending")], [history(preimage: "")], [history(preimage: nil)]] {
            let details = SendSuccessDetails(receipt: receipt, payment: payment, history: rows, completedAt: date)
            #expect(details.proof == nil)
            #expect(details.completedAt == date)
        }
        #expect(SendSuccessDetails(receipt: receipt, payment: .init(destination: "ark", method: "ark", amount: 1000),
                                   history: [history()], completedAt: date).proof == nil)
    }
    @Test func historyCannotSupplyProofWithoutExactReceiptIdentity() {
        let payment = SendPayment(destination: "invoice", method: "lightning", amount: 1000)
        #expect(SendSuccessDetails(receipt: .init(amount: 1000), payment: payment,
                                   history: [history()], completedAt: date).proof == nil)
        var incoming = history()
        incoming.direction = "incoming"
        #expect(SendSuccessDetails(receipt: .init(amount: 1000, movementID: 7), payment: payment,
                                   history: [incoming], completedAt: date).proof == nil)
        var chain = history()
        chain.txid = "txid"
        let details = SendSuccessDetails(receipt: .init(amount: 1000, txid: "txid"), payment: payment,
                                        history: [chain], completedAt: .distantFuture)
        #expect(details.completedAt == date)
    }
    @Test func truncatesOnlyOver32AndKeepsFullProof() {
        let value = "12345678901234567890123456789012345"
        #expect(SendSuccessDetails.truncate(String(value.prefix(32))) == String(value.prefix(32)))
        #expect(SendSuccessDetails.truncate(value) == "12345678901234…6789012345")
        #expect(SendSuccessDetails.truncate("") == "")
        let details = SendSuccessDetails(receipt: .init(amount: 1000, txid: value),
                                        payment: .init(destination: value, method: "onchain", amount: 1000),
                                        history: [], completedAt: date)
        #expect(details.proof?.value == value && details.destination == value)
        #expect(details.formattedCompletedAt(locale: Locale(identifier: "en_US")).contains("2023"))
    }
}
