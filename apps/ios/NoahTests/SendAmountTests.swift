import Foundation
import Testing
@testable import Noah

struct SendAmountTests {
    struct Input: Decodable { let amount: String }
    struct Expected: Decodable { let valid: Bool; let sats: String? }

    @Test func untrustedURIAmountsMatchSharedSafetyVectors() throws {
        let fixtures = try Fixtures.load("send-amount-safety", input: Input.self, expected: Expected.self)
        #expect(fixtures.count == 108)
        for fixture in fixtures {
            let encoded = try #require(fixture.input.amount.addingPercentEncoding(withAllowedCharacters: .alphanumerics))
            let parsed = SendDestination.parse("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=\(encoded)", network: "mainnet")
            #expect((parsed.destinationType != nil) == fixture.expected.valid, "\(fixture.name)")
            #expect((parsed.error == nil) == fixture.expected.valid, "\(fixture.name)")
            #expect(parsed.amount == fixture.expected.sats.flatMap { Decimal(string: $0) }, "\(fixture.name)")
            #expect(parsed.isAmountEditable == (parsed.amount == nil), "\(fixture.name)")
            #expect(SendDestination.parse("bitcoin:?amount=\(encoded)", network: "mainnet").destinationType == nil)
        }
    }
}
