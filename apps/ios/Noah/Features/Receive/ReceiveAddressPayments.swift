import Foundation

/// Ark and Lightning payments that reach you through your Lightning address rather than the shown request's own Ark
/// address or invoice (Android's `ReceiveArrivals.viaAddress`). Bark records them as successful receives; those already
/// complete when Receive appeared are settled, so only new ones arrive, including one that was still pending then.
/// Receives to this screen's own Ark addresses or invoices come through `ReceiveCompletion` instead, so they're never
/// announced twice.
enum ReceiveAddressPayments {
    /// The receipts already complete when Receive appeared.
    static func settled(_ rows: [HistoryTransaction]) -> Set<String> {
        Set(rows.filter { $0.movementStatus == "successful" }.map(\.id))
    }

    /// The next payment through your Lightning address that hasn't been settled or shown.
    static func next(_ rows: [HistoryTransaction], settled: Set<String>, ownArk: Set<String>,
                     ownHashes: Set<String>) -> HistoryTransaction? {
        rows.first { row in
            !settled.contains(row.id) && row.direction == "incoming" && row.movementStatus == "successful" && row.amount > 0
                && ["arkoor-receive", "lightning-receive"].contains(row.movementKind ?? "")
                && !(row.receivedOn ?? []).contains { ownArk.contains($0.destination) }
                && !(row.paymentHash.map(ownHashes.contains) ?? false)
        }
    }

    /// The arrival a payment plays: Lightning for a Lightning receive, Ark for one over Ark.
    static func rail(of row: HistoryTransaction) -> ReceiveArrival.Rail {
        row.movementKind == "lightning-receive" ? .lightning : .ark
    }
}
