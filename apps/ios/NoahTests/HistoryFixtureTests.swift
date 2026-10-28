import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

struct HistoryFixtureTests {
    /// Fixtures retain RN field names; only this test adapter knows the Nitro
    /// shape. Production consumes typed bark-bolt-ffi records directly.
    struct SubsystemInput: Decodable { var name: String; var kind: String }
    struct DestinationInput: Decodable {
        var destination: String?
        var amount_sat: UInt64
        var payment_method: String?
    }
    struct MovementInput: Decodable {
        var id: UInt32
        var status: String
        var subsystem: SubsystemInput
        var metadata_json: String
        var intended_balance_sat: Int64
        var effective_balance_sat: Int64
        var offchain_fee_sat: UInt64
        var sent_to: [DestinationInput]
        var received_on: [DestinationInput]
        var input_vtxos: [String]
        var output_vtxos: [String]
        var exited_vtxos: [String]
        var created_at: String

        var sdk: Movement {
            func destination(_ value: DestinationInput) -> MovementDestination {
                .init(paymentMethod: .init(methodType: value.payment_method ?? "", value: value.destination ?? ""), amountSats: value.amount_sat)
            }
            return Movement(id: id, status: status, subsystemName: subsystem.name, subsystemKind: subsystem.kind,
                            metadataJson: metadata_json, intendedBalanceSats: intended_balance_sat, effectiveBalanceSats: effective_balance_sat,
                            offchainFeeSats: offchain_fee_sat, sentToAddresses: sent_to.map(destination), receivedOnAddresses: received_on.map(destination),
                            inputVtxoIds: input_vtxos, outputVtxoIds: output_vtxos, exitedVtxoIds: exited_vtxos,
                            createdAt: created_at, updatedAt: created_at, completedAt: nil, paymentHash: nil, lightningInvoice: nil, lightningOffer: nil)
        }
    }
    struct ChainInput: Decodable {
        var txid: String
        var tx_hex: String
        var balance_change_sat: Int64
        var has_onchain_fee: Bool
        var onchain_fee_sat: UInt64
        var has_confirmation: Bool
        var confirmation_height: UInt32
        var confirmation_hash: String
        var sdk: WalletTransaction {
            .init(txid: txid, txHex: tx_hex, onchainFeeSats: has_onchain_fee ? onchain_fee_sat : nil,
                  balanceChangeSats: balance_change_sat,
                  confirmation: has_confirmation ? .init(height: confirmation_height, hash: confirmation_hash) : nil, isCpfp: false)
        }
    }
    struct Input: Decodable {
        var date: String?
        var operation: String
        var metadataJson: String?
        var metadata: JSON?
        var movement: MovementInput?
        var outgoing: Bool? // swiftlint:disable:this discouraged_optional_boolean - fixture field only some operations carry
        var amountSat: Int64?
        var destination: String?
        var paymentMethod: String?
        var details: RepeatPayment?
        var currency: String?
        var transaction: HistoryTransaction?
        var movements: [HistoryTransaction]? // swiftlint:disable:this discouraged_optional_collection - fixture field only some operations carry
        var onchain: JSON?
        var transactions: [HistoryTransaction]? // swiftlint:disable:this discouraged_optional_collection - fixture field only some operations carry
        var routeMethod: String?
        var comment: String?
        var existingMovementIds: [UInt32]? // swiftlint:disable:this discouraged_optional_collection - fixture field only some operations carry
        var rawMovements: [MovementInput]? // swiftlint:disable:this discouraged_optional_collection - fixture field only some operations carry
    }

    // JSON equality preserves omitted fields and catches extra/wrong fields,
    // rather than testing a hand-picked subset of each expected transaction.
    indirect enum JSON: Codable, Equatable {
        case object([String: Self]), array([Self]), string(String), number(Double), bool(Bool), null
        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if c.decodeNil() { self = .null }
            else if let v = try? c.decode(Bool.self) { self = .bool(v) }
            else if let v = try? c.decode(Double.self) { self = .number(v) }
            else if let v = try? c.decode(String.self) { self = .string(v) }
            else if let v = try? c.decode([Self].self) { self = .array(v) }
            else { self = .object(try c.decode([String: Self].self)) }
        }
        func encode(to encoder: Encoder) throws {
            var c = encoder.singleValueContainer()
            switch self {
            case .object(let v): try c.encode(v)
            case .array(let v): try c.encode(v)
            case .string(let v): try c.encode(v)
            case .number(let v): try c.encode(v)
            case .bool(let v): try c.encode(v)
            case .null: try c.encodeNil()
            }
        }
        static func value(_ value: some Encodable) throws -> Self {
            try JSONDecoder().decode(Self.self, from: JSONEncoder().encode(value))
        }
        func decode<T: Decodable>(_ type: T.Type) throws -> T {
            try JSONDecoder().decode(type, from: JSONEncoder().encode(self))
        }
    }

    @Test func matchesReactNativeHistory() throws {
        let cases = try Fixtures.load("transaction-history", input: Input.self, expected: JSON.self)
            + Fixtures.load("history-regressions", input: Input.self, expected: JSON.self)
        for fixture in cases {
            guard let result = try Self.historyResult(fixture.input) ?? Self.repeatResult(fixture.input) else {
                Issue.record("Unknown fixture operation: \(fixture.input.operation)"); continue
            }
            #expect(result == fixture.expected, "\(fixture.name)")
        }
    }

    private static func historyResult(_ input: Input) throws -> JSON? {
        switch input.operation {
        case "csv": return try .value(HistoryCSV(transactions: #require(input.transactions), currency: #require(input.currency)).content)
        case "date": return try .value(HistoryRules.parseDate(#require(input.date)).map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) })
        case "metadata": return try .value(HistoryMetadata.parse(#require(input.metadataJson)))
        case "transactionID":
            try #require(input.outgoing != nil)
            return try .value(HistoryMapping.transactionID(
                #require(input.movement).sdk,
                metadata: #require(input.metadata).decode(HistoryMetadata.self),
                outgoing: input.outgoing == true))
        case "boardAmount": return try .value(HistoryMapping.boardAmount(#require(input.movement).sdk))
        case "presentation":
            let transaction = try #require(input.transaction)
            return try .object(["label": .value(transaction.displayLabel), "canceled": .value(transaction.isCanceled),
                                "internalTransfer": .value(transaction.isInternalTransfer), "accounting": .value(transaction.accounting)])
        case "merge": return try .value(HistoryRules.merge(#require(input.movements), onchain: #require(input.onchain).decode([HistoryTransaction].self)))
        case "preimage": return try .value(HistoryMapping.transaction(#require(input.movement).sdk).preimage)
        case "movement":
            let movement = try #require(input.movement).sdk
            return try .object(["included": .value(HistoryMapping.include(movement)), "transaction": .value(HistoryMapping.transaction(movement))])
        case "onchain":
            let chain = try #require(input.onchain).decode(ChainInput.self).sdk
            return try .object(["included": .value(chain.balanceChangeSats != 0), "transaction": .value(HistoryMapping.transaction(chain))])
        case "sort": return try .value(#require(input.transactions).sorted(by: HistoryRules.precedes).map(\.id))
        default: return nil
        }
    }

    private static func repeatResult(_ input: Input) throws -> JSON? {
        switch input.operation {
        case "resolveRepeat":
            return try .value(RepeatPayment.resolve(
                metadata: input.metadata?.decode(RepeatPayment.self),
                destination: input.destination,
                paymentMethod: input.paymentMethod,
                amountSat: #require(input.amountSat)))
        case "prefill": return try .value(#require(input.details).prefill(preferredCurrency: #require(input.currency)))
        case "canRepeat": return try .value(#require(input.transaction).canRepeat)
        case "repeatRoute": return try .value(RepeatPayment.usesDirectArkRoute(method: #require(input.routeMethod), comment: input.comment))
        case "newMovement":
            return try .value(RepeatPayment.newArkoorMovementID(
                existing: Set(#require(input.existingMovementIds)),
                movements: #require(input.rawMovements).map(\.sdk),
                destination: #require(input.destination),
                amountSat: #require(input.amountSat)))
        default: return nil
        }
    }

    @Test func matchesReactNativeMovementFailures() throws {
        struct Input: Decodable { var status: String }
        for fixture in try Fixtures.load("bark-movement", input: Input.self, expected: Bool.self) {
            #expect(HistoryMapping.isFailedOrCanceled(fixture.input.status) == fixture.expected, "\(fixture.name)")
        }
    }
}
