import Foundation

/// Read-only presentation. History supplies proof without changing the durable Send receipt.
struct SendSuccessDetails {
    struct Proof: Equatable {
        let label: String
        let value: String
    }
    let paidVia: String
    let destination: String
    let proof: Proof?
    let completedAt: Date

    init(receipt: SendReceipt, payment: SendPayment, history: [HistoryTransaction], completedAt: Date) {
        paidVia = payment.method == "ark" ? "Ark" : payment.method == "onchain" ? "Onchain" : "Lightning"
        destination = payment.destination
        let movement = history.first { row in
            row.direction == "outgoing" && (receipt.movementID.map { row.movementId == $0 }
                ?? (receipt.txid.map { !$0.isEmpty && row.txid == $0 } ?? false))
        }
        if let txid = receipt.txid, !txid.isEmpty {
            proof = Proof(label: "Transaction ID", value: txid)
        } else if ["lightning", "lnurl", "offer"].contains(payment.method),
                  movement?.movementStatus == "successful", let preimage = movement?.preimage, !preimage.isEmpty {
            proof = Proof(label: "Payment preimage", value: preimage)
        } else {
            proof = nil
        }
        self.completedAt = movement?.timestamp.flatMap { $0.timeIntervalSince1970 > 0 ? $0 : nil } ?? completedAt
    }

    static func truncate(_ value: String) -> String {
        value.count > 32 ? String(value.prefix(14)) + "…" + String(value.suffix(10)) : value
    }

    func formattedCompletedAt(locale: Locale) -> String {
        completedAt.formatted(.dateTime.day(.twoDigits).month(.abbreviated).year().locale(locale))
            + " · " + completedAt.formatted(.dateTime.hour(.twoDigits(amPM: .abbreviated)).minute(.twoDigits).locale(locale))
    }
}
