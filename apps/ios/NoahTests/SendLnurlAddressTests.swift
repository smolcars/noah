import Foundation
@testable import Noah
import Testing

/// An LNURL to LUD-16's well-known path reads back as the Lightning address it stands for (Noah's own QR codes), from
/// the fixture Android runs too.
struct SendLnurlAddressTests {
    struct Input: Decodable { let value: String }
    struct Expected: Decodable { let cleaned: String }

    @Test func matchesSharedFixture() throws {
        for fixture in try Fixtures.load("send-lnurl-address", input: Input.self, expected: Expected.self) {
            #expect(SendDestination.cleaned(fixture.input.value) == fixture.expected.cleaned, "\(fixture.name)")
        }
    }
}
