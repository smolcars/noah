import Foundation
@testable import Noah
import Testing

/// What a recurring schedule stores for a recipient, from the fixture Android runs too. The cases use test-network
/// addresses, as the test variants parse them.
struct RecurringRecipientTests {
    struct Input: Decodable { let recipient: String }
    struct Expected: Decodable { let destination: String; let type: String }

    @Test func matchesSharedFixture() throws {
        for fixture in try Fixtures.load("recurring-recipient", input: Input.self, expected: Expected.self) {
            let resolved = try RecurringRecipient.resolve(fixture.input.recipient)
            #expect(resolved.destination == fixture.expected.destination && resolved.type == fixture.expected.type, "\(fixture.name)")
        }
    }
}
