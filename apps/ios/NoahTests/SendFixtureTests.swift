import Foundation
import Testing
@testable import Noah

struct SendFixtureTests {
    struct ParseInput: Decodable { let destination: String; let network: String }
    struct ParseExpected: Decodable {
        let valid: Bool
        let destinationType: String?
        let amount: Decimal?
        var isAmountEditable: Bool? = nil
        let error: String?
        let bip321: [String: String]?
    }
    @Test func destinationsMatchRN() throws {
        for fixture in try Fixtures.load("send-utils", input: ParseInput.self, expected: ParseExpected.self) {
            #expect((SendDestination.parse(fixture.input.destination, network: fixture.input.network).destinationType != nil) == fixture.expected.valid)
        }
        for fixture in try Fixtures.load("send-utils", input: ParseInput.self, expected: ParseExpected.self) {
            let actual = SendDestination.parse(fixture.input.destination, network: fixture.input.network)
            #expect(actual.destinationType == fixture.expected.destinationType, "\(fixture.name)")
            #expect(actual.amount == fixture.expected.amount, "\(fixture.name)")
            #expect(actual.isAmountEditable == (fixture.expected.isAmountEditable ?? true), "\(fixture.name)")
            #expect(actual.bip321 == fixture.expected.bip321, "\(fixture.name)")
            // Parser-library wording is not a contract; rejection and error presence are.
            #expect((actual.error != nil) == (fixture.expected.error != nil), "\(fixture.name)")
        }
    }
    enum JSON: Codable {
        case string(String), bool(Bool), object([String: JSON]), array([JSON]), number(Int), null
        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if c.decodeNil() { self = .null }
            else if let x = try? c.decode(String.self) { self = .string(x) }
            else if let x = try? c.decode(Bool.self) { self = .bool(x) }
            else if let x = try? c.decode(Int.self) { self = .number(x) }
            else if let x = try? c.decode([JSON].self) { self = .array(x) }
            else { self = .object(try c.decode([String: JSON].self)) }
        }
        func encode(to encoder: Encoder) throws {
            var c = encoder.singleValueContainer()
            switch self { case .null: try c.encodeNil(); case .string(let x): try c.encode(x); case .bool(let x): try c.encode(x)
            case .number(let x): try c.encode(x); case .array(let x): try c.encode(x); case .object(let x): try c.encode(x) }
        }
        func decode<T: Decodable>(_ type: T.Type) throws -> T { try JSONDecoder().decode(type, from: JSONEncoder().encode(self)) }
    }
    struct FlowInput: Decodable { let operation: String; let args: [JSON] }
    @Test func flowMatchesRN() throws {
        for fixture in try Fixtures.load("send-flow", input: FlowInput.self, expected: JSON.self) {
            let args = fixture.input.args
            switch fixture.input.operation {
            case "stage": #expect(try args[0].decode(SendProgress.self).next.rawValue == fixture.expected.decode(String.self), "\(fixture.name)")
            case "rails": #expect(try SendFlow.rails(type: args[0].decode(String?.self), request: args[1].decode([String:String]?.self)) == fixture.expected.decode([SendRail].self))
            case "label": #expect(try SendFlow.label(type: args[0].decode(String?.self)) == fixture.expected.decode(String?.self))
            case "note": #expect(try SendFlow.canNote(type: args[0].decode(String?.self), allowed: args[1].decode(Int.self)) == fixture.expected.decode(Bool.self))
            case "max": #expect(try SendFlow.canMax(type: args[0].decode(String?.self), request: args[1].decode([String:String]?.self)) == fixture.expected.decode(Bool.self))
            case "method": #expect(try SendFlow.method(rail: args[0].decode(SendRail.self), request: args[1].decode([String:String].self)) == fixture.expected.decode(String?.self))
            case "recommend": #expect(try SendFlow.recommend(args[0].decode([SendRail].self), availability: args[1].decode([String:Bool].self)) == fixture.expected.decode(SendRail?.self))
            default: Issue.record("Unknown fixture")
            }
        }
    }
    struct MaxInput: Decodable { let source: SendSource?; let onchain: UInt64; let offchain: UInt64 }
    @Test func maxMatchesRN() throws {
        for fixture in try Fixtures.load("onchain-send", input: MaxInput.self, expected: UInt64.self) {
            #expect(SendFlow.maxBalance(source: fixture.input.source, onchain: fixture.input.onchain, offchain: fixture.input.offchain) == fixture.expected)
        }
    }
    struct DescriptionInput: Decodable { let description: String? }
    struct DescriptionExpected: Decodable { let normalized: String?; let length: Int; let valid: Bool }
    @Test func descriptionsMatchRN() throws {
        for fixture in try Fixtures.load("lightning-invoice", input: DescriptionInput.self, expected: DescriptionExpected.self) {
            let trimmed = (fixture.input.description ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            #expect((trimmed.isEmpty ? nil : trimmed) == fixture.expected.normalized)
            #expect(trimmed.utf16.count == fixture.expected.length)
            #expect(((try? ReceiveRequest.validate(amountSat: 1, description: trimmed)) != nil) == fixture.expected.valid)
        }
    }
    @Test func feeUnitsAndIntegerSDKRounding() {
        #expect(SendFlow.satsPerVByte(satsPerKwu: 2500) == 10)
        #expect(SendFlow.satsPerVByte(satsPerKwu: 375) == 1.5)
        #expect(SendFlow.sdkFeeRate(satsPerKwu: 375) == 2)
        #expect(SendFlow.sdkFeeRate(satsPerKwu: 250) == 1)
        #expect(SendFlow.sdkFeeRate(satsPerKwu: UInt64.max) > 0)
    }
    @Test func rejectsWrongNetworkAndInvalidChecksum() {
        #expect(SendDestination.parse("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", network: "regtest").error != nil)
        #expect(SendDestination.parse("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb", network: "mainnet").destinationType == nil)
        #expect(SendDestination.parse("bitcoin:?req-foo=1", network: "regtest").destinationType == nil)
    }
}
