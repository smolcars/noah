import CoreTransferable
import Foundation
import UniformTypeIdentifiers

struct HistoryCSV: Transferable, Sendable {
    let content: String

    init(transactions: [HistoryTransaction], currency: String) {
        let header = ["Payment ID", "Date", "Type", "Status", "Direction", "Amount (sats)",
                      "BTC Price (\(currency))", "Transaction ID", "Destination"]
        let rows = transactions.map { transaction in
            [transaction.id, transaction.confirmationLabel ?? String(transaction.date.prefix(10)),
             transaction.displayLabel, transaction.statusLabel ?? "", transaction.accounting.direction,
             String(transaction.accounting.amount), transaction.btcPrice.map { String($0) } ?? "",
             transaction.txid ?? "", transaction.destination ?? ""]
        }
        let escaped = [header.map { Self.escape($0) }] + rows.map { row in
            row.enumerated().map { Self.escape($0.element, numeric: $0.offset == 5 || $0.offset == 6) }
        }
        content = escaped.map { $0.joined(separator: ",") }.joined(separator: "\n") + "\n"
    }

    private static func escape(_ raw: String, numeric: Bool = false) -> String {
        let value = !numeric && raw.first.map { ["=", "+", "-", "@", "\t", "\r"].contains($0) } == true ? "'" + raw : raw
        // RFC 4180: destinations/comments can contain commas, quotes or newlines.
        if value.contains(where: { [",", "\"", "\n", "\r"].contains($0) }) {
            return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return value
    }

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .commaSeparatedText) { Data($0.content.utf8) }
            .suggestedFileName("noah_transactions.csv")
    }
}
