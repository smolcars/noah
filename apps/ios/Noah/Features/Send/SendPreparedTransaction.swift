import BarkBoltFFI
import Foundation

/// Only review values are durable. The SDK handle and its reservations are process-local.
struct SendPreparedTransaction: Codable, Equatable, Sendable {
    var id: String
    var txid: String
    var vsize: UInt64
    var inputCount: Int
    var inputs: [SendTransaction.Input]?
    var createdAt: Date = Date()
    static let reviewLifetime: TimeInterval = 120
    var isCurrent: Bool { Date().timeIntervalSince(createdAt) < Self.reviewLifetime }

    init(_ transaction: PreparedTransaction) {
        id = transaction.id
        txid = transaction.txid
        vsize = transaction.vsize
        inputCount = transaction.inputs.count
        inputs = transaction.inputs.map { .init(txid: $0.txid, vout: $0.vout) }
    }
}
