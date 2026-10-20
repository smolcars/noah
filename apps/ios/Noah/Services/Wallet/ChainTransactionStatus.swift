import BarkBoltFFI
import Foundation

/// Read-only chain-source evidence. Absence never proves that a payment was not delivered.
enum ChainTransactionStatus: Codable, Equatable, Sendable {
    case notFound, inMempool, confirmed(UInt32)
    init(_ status: TransactionStatus) {
        switch status {
        case .notFound: self = .notFound
        case .inMempool: self = .inMempool
        case .confirmed(let height): self = .confirmed(height)
        }
    }
    static let errorText = "Couldn't check network status. Showing the last known network status. Try again."
    var message: String {
        switch self {
        case .notFound: "Not yet seen by the network. This chain source does not currently know the transaction. Do not submit it again."
        case .inMempool: "In mempool. Waiting for confirmation."
        case .confirmed(let height): "Confirmed at block \(height)."
        }
    }
}
