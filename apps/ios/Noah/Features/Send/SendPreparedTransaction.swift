import BarkBoltFFI
import Foundation

/// Only review values are durable. The SDK handle and its reservations are process-local.
struct SendPreparedTransaction: Codable, Equatable, Sendable {
    var id: String
    var txid: String
    var vsize: UInt64
    var inputCount: Int
    // swiftlint:disable:next discouraged_optional_collection - journals written before inputs were recorded lack it
    var inputs: [SendTransaction.Input]?
    var createdAt: Date = .init()
    /// When this review opened on `ReviewClock`. Never persisted, so nil once read back from storage.
    var reviewedAt: ContinuousClock.Instant?
    static let reviewLifetime: TimeInterval = 120
    var isCurrent: Bool { ReviewClock.elapsed(since: reviewedAt, createdAt: createdAt) < .seconds(Self.reviewLifetime) }

    private enum CodingKeys: String, CodingKey { case id, txid, vsize, inputCount, inputs, createdAt }

    init(_ transaction: PreparedTransaction) {
        id = transaction.id
        txid = transaction.txid
        vsize = transaction.vsize
        inputCount = transaction.inputs.count
        inputs = transaction.inputs.map { .init(txid: $0.txid, vout: $0.vout) }
        reviewedAt = .now
    }

    init(_ board: PreparedBoard) {
        id = board.id
        txid = board.txid
        vsize = board.vsize
        inputCount = board.inputs.count
        inputs = board.inputs.map { .init(txid: $0.txid, vout: $0.vout) }
        reviewedAt = .now
    }
}
