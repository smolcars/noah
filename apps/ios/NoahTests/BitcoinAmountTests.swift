import Foundation
import Testing

@testable import Noah

struct BitcoinAmountTests {
    struct Input: Decodable {
        let sats: Int64
        let unit: BitcoinAmountUnit
        let locale: String
    }

    @Test func matchesReactNativeFormatting() throws {
        let cases = try Fixtures.load("bitcoin-amount", input: Input.self, expected: String.self)
        #expect(cases.count == 70)
        for testCase in cases {
            let formatted = BitcoinAmount.format(
                sats: testCase.input.sats, unit: testCase.input.unit,
                locale: Locale(identifier: testCase.input.locale))
            #expect(formatted == testCase.expected, "\(testCase.name)")
        }
    }
}
