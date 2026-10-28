import Foundation

/// "Recent" on the To screen: people and addresses you've paid, newest first, read from
/// history (no new storage). Invoices and offers are single-use, so they're left out.
struct SendRecent: Equatable, Identifiable {
    var id: String { destination.lowercased() }
    let destination: String
    let label: SendRecipientLabel

    static func from(_ transactions: [HistoryTransaction], ownDomain: String, limit: Int = 4) -> [Self] {
        var seen: Set<String> = []
        var recents: [Self] = []
        for row in transactions.sorted(by: HistoryRules.precedes)
            where row.direction == "outgoing" && row.movementStatus == "successful" {
            guard let destination = reusable(row), seen.insert(destination.lowercased()).inserted else { continue }
            recents.append(Self(destination: destination, label: .of(destination: destination, ownDomain: ownDomain)))
            if recents.count == limit { break }
        }
        return recents
    }

    /// A destination you could pay again as it is: a Lightning address, or a Bitcoin or Ark address.
    static func reusable(_ row: HistoryTransaction) -> String? {
        if let address = (row.repeatPayment?.destination ?? row.destination).flatMap(RepeatPayment.normalizeLightningAddress),
           SendDestination.isLightningAddress(address) {
            return address
        }
        guard let destination = row.destination, !destination.isEmpty else { return nil }
        let parsed = SendDestination.parse(destination)
        guard parsed.error == nil, ["onchain", "ark"].contains(parsed.destinationType) else { return nil }
        return destination
    }

    /// The recents whose name or address starts with what's typed.
    static func matching(_ recents: [Self], query: String) -> [Self] {
        let query = SendDestination.cleaned(query).lowercased()
        guard !query.isEmpty else { return [] }
        return recents.filter { $0.destination.lowercased().hasPrefix(query) || ($0.label.nameIsVerbatim && $0.label.name.lowercased().hasPrefix(query)) }
    }
}
